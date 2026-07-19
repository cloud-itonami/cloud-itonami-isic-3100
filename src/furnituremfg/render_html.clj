(ns furnituremfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6 template, iteration 16): this repo
  previously had NO demo page and no generator at all. This namespace
  drives the REAL actor stack (`furnituremfg.operation` ->
  `furnituremfg.governor` -> `furnituremfg.store`) through a scenario
  adapted from this repo's own `furnituremfg.sim` demo driver (`clojure
  -M:dev:run`) -- that driver was run first and its printed
  `:disposition`/`:rule` output was cross-checked against this repo's
  own `store/sample-data!` seed ids (`batch-001`/`batch-002`/
  `batch-003`, `equip-001`/`equip-002`) before being trusted: every
  scenario below reached exactly the disposition its own comment
  claims, so (unlike `cloud-itonami-isic-851`'s `schoolops.sim`, which
  turned out to drive ids that don't exist in its own seed data) this
  repo's `sim.cljc` was safe to mine directly rather than author from
  scratch.

  Scenario mirrors a representative subset of `sim.cljc`: one clean
  `:log-production-batch` auto-commit at phase 3, three
  escalate->human-approve lifecycles (`:schedule-maintenance` on a
  verified/registered cutting-saw, `:flag-safety-concern` -- always
  escalates, `:coordinate-shipment` within capacity), and three
  DISTINCT HARD-hold rules that never reach a human
  (`:equipment-not-verified` scheduling maintenance against an
  unverified/unregistered finishing-line unit, `:batch-not-verified`
  coordinating a shipment against an unverified/unregistered batch,
  `:shipment-units-exceeded` coordinating a shipment whose own claimed
  unit-count would blow through the batch's own logged production
  output). Rendered deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns against
  the same seed (verify by diffing two consecutive runs before
  shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [furnituremfg.store :as store]
            [furnituremfg.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: batch-001 clears a clean production-batch log
  (auto-commit, phase-3, no physical/financial risk); mnt-1 schedules
  maintenance on equip-001 (verified+registered cutting-saw -- ALWAYS
  escalates, approved); concern-1 flags a safety concern on equip-001
  (ALWAYS escalates regardless of verification state -- approved);
  ship-1 coordinates a 50-unit shipment against batch-001 (verified,
  registered, well within its 500-unit capacity given 100 already
  shipped -- ALWAYS escalates, approved). Then three HARD holds, none
  reaching a human: mnt-2 tries to schedule maintenance against
  equip-002 (UNVERIFIED/unregistered finishing-line unit ->
  `:equipment-not-verified`); ship-2 tries to coordinate a shipment
  against batch-003 (UNVERIFIED/unregistered batch -> `:batch-not-
  verified`); ship-3 tries to coordinate a 10-unit shipment against
  batch-002 (80-unit capacity, 75 already shipped -- 75+10=85 > 80 ->
  `:shipment-units-exceeded`). Returns the resulting store -- every
  field read by `render` below is real governor/store output, not a
  hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (exec! actor "t1-batch001-log"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:quality-grade :grade-a :last-assessed "2026-07-14"}})

    (exec! actor "t2-mnt1"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "equip-001" :maintenance-type :blade-change
                     :scheduled-date "2026-08-01" :finalize? false}})
    (approve! actor "t2-mnt1")

    (exec! actor "t3-concern1"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "equip-001" :severity :moderate
                     :description "仕上げライン付近のVOC濃度上昇"}})
    (approve! actor "t3-concern1")

    (exec! actor "t4-ship1"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :unit-count 50.0
                     :destination "buyer-showroom-north"}})
    (approve! actor "t4-ship1")

    (exec! actor "t5-mnt2"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "equip-002" :maintenance-type :finishing-line-inspection
                     :scheduled-date "2026-08-01" :finalize? false}})

    (exec! actor "t6-ship2"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :unit-count 100.0
                     :destination "buyer-showroom-south"}})

    (exec! actor "t7-ship3"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :unit-count 10.0
                     :destination "buyer-showroom-east"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id product quality-grade unit-count defect-rate-percent
                                  verified? registered? shipped-unit-count]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc product) (esc (name (or quality-grade :n-a)))
          (esc unit-count) (esc defect-rate-percent)
          (esc (if verified? "yes" "no")) (esc (if registered? "yes" "no"))
          (str (esc shipped-unit-count) " &middot; " (status-cell ledger id))))

(defn- equipment-row [{:keys [id kind verified? registered? last-maintenance-date]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or kind :n-a)))
          (esc (if verified? "yes" "no")) (esc (if registered? "yes" "no"))
          (esc (or last-maintenance-date "never"))))

(def ^:private coordination-requests
  ;; Static description of the specific requests `run-demo!` above
  ;; literally issues (their own op + subject + target equipment/batch
  ;; id) -- fixed by the code above, not runtime telemetry. The
  ;; disposition/rule status in each row is derived from the real
  ;; ledger via `status-cell`, never hand-typed.
  [{:subject "mnt-1" :op :schedule-maintenance :target "equip-001"}
   {:subject "concern-1" :op :flag-safety-concern :target "equip-001"}
   {:subject "ship-1" :op :coordinate-shipment :target "batch-001"}
   {:subject "mnt-2" :op :schedule-maintenance :target "equip-002"}
   {:subject "ship-2" :op :coordinate-shipment :target "batch-003"}
   {:subject "ship-3" :op :coordinate-shipment :target "batch-002"}])

(defn- request-row [ledger {:keys [subject op target]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc subject) (esc (name op)) (esc target) (status-cell ledger subject)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `furnituremfg.governor`/`furnituremfg.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">auto-commit when clean at phase 3 &middot; grade &amp; defect-rate independently validated</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; equipment must be independently verified &amp; registered &middot; finalize permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval (high-stakes), regardless of equipment/batch verification state</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; batch must be independently verified &amp; registered &middot; unit-count independently recomputed against logged output</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map equipment-row equipment))
        request-rows (str/join "\n" (map (partial request-row ledger) coordination-requests))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-3100 &middot; manufacture of furniture</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of furniture (ISIC 3100) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance/shipment coordination always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>furnituremfg.store</code> via <code>furnituremfg.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product</th><th>Grade</th><th>Unit count</th><th>Defect rate %</th><th>Verified?</th><th>Registered?</th><th>Shipped so far &middot; log status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Ground-truth verification/registration state the Governor independently re-checks before any maintenance may be scheduled.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Verified?</th><th>Registered?</th><th>Last maintenance</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Coordination requests (this run)</h2>\n"
     "    <p class=\"muted\">Every maintenance-schedule / safety-concern / shipment-coordination proposal this demo issued, and what the Governor + phase gate actually did with it.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Request</th><th>Op</th><th>Target</th><th>Outcome</th></tr></thead>\n"
     "      <tbody>\n"
     request-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Furniture Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Equipment/batch verification is independently re-checked, never trusted from the proposal; shipment unit-counts are independently recomputed against the batch's own logged production output.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
