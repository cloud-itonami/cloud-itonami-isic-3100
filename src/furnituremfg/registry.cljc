(ns furnituremfg.registry
  "Pure-function domain logic for the furniture-manufacturing plant-
  operations coordination actor -- equipment/batch verification,
  shipment-unit recompute, quality-grade validation, defect-rate
  plausibility validation, and draft maintenance-schedule/shipment-
  coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/furnituremfg`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `furnituremfg.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `sawmilling.registry/shipment-volume-exceeded?`,
  `logging.registry/permit-allowance-exceeded?`): never trust a
  proposal's own self-reported unit-count/status when the inputs
  needed to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real factory-operations system. It builds the DRAFT
  record a plant coordinator would keep (a scheduled maintenance
  window, a coordinated shipment), not the act of actuating cutting/
  joinery/finishing-line equipment or dispatching a real freight
  carrier (this actor NEVER does either -- see README `What this actor
  does NOT do`).")

;; ----------------------------- constants -----------------------------

(def valid-grades
  "The closed set of furniture quality-grade values a production-batch
  record may declare (furniture-industry QC classification, informed
  by common wholesale/export grading practice: premium showroom stock
  down through rejects). Anything else is a fabricated/unrecognized
  grade -- the governor HARD-holds rather than let an invented grade
  pass through."
  #{:premium :grade-a :grade-b :commercial :seconds :reject})

(def defect-rate-min-percent
  "Physical floor for a batch's own logged defect-rate reading (a
  batch cannot have a negative fraction of defective units)."
  0.0)

(def defect-rate-max-percent
  "Physical ceiling for a batch's own logged defect-rate reading (a
  defect rate is a percentage of the batch's own unit-count and can
  never exceed the whole batch). A reading above this is implausible
  QC/scale data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('factory/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its quality-grade/unit-count/defect-rate claims have actually
  been QC-inspected, not merely logged from an unverified intake
  patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('factory/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-units-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-unit-count` + `new-unit-count` exceed
  `batch`'s own recorded `:unit-count` (the batch's own logged
  production output)? Needs no proposal inspection or stored-verdict
  lookup -- its inputs are permanent fields already on the batch's
  own record, the same shape every sibling actor's own cost/total-
  matching check uses."
  [batch new-unit-count]
  (let [capacity (:unit-count batch)
        so-far (:shipped-unit-count batch 0.0)]
    (and (number? capacity)
         (number? new-unit-count)
         (> (+ (double so-far) (double new-unit-count)) (double capacity)))))

(defn grade-valid?
  "Is `grade` one of the closed, known furniture quality-grade values?
  nil/blank is treated as invalid (a production-batch patch must
  declare a real grade, not omit it silently)."
  [grade]
  (contains? valid-grades grade))

(defn defect-rate-valid?
  "Is `percent` a physically plausible batch defect-rate reading?
  Rejects nil, non-numbers, negative values, and values beyond
  `defect-rate-max-percent` -- a fabricated or sensor/QC-scale-error
  reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) defect-rate-min-percent)
       (<= (double percent) defect-rate-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  cutting/sanding/finishing-line maintenance window against a
  verified, registered piece of equipment. Pure function -- does not
  actuate cutting/joinery/finishing-line equipment or execute any
  maintenance; it builds the RECORD a plant coordinator would keep.
  `furnituremfg.governor` independently re-verifies the equipment's
  own verified/registered ground truth, and permanently blocks any
  attempt to set `:finalize? true` (a direct cutting/joinery/
  finishing-line-equipment control/execution request, see README
  `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound furniture shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep. `furnituremfg.governor` independently re-verifies the
  shipment's own claimed unit-count against `shipment-units-
  exceeded?`, before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
