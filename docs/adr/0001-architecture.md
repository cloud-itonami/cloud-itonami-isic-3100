# ADR-0001: FurnitureAdvisor ⊣ Furniture Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-3100` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-3100` publishes an OSS blueprint for furniture-
factory **plant operations coordination** (production-batch quality-
grade/unit-count/defect-rate data logging across cutting, joinery/
assembly and finishing/upholstery lines, cutting/sanding/finishing-
equipment maintenance scheduling, safety-concern flagging, and
outbound furniture-shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-1610` (Sawmilling and
planing of wood): both are back-office coordination actors for a fixed
wood-products processing **plant** (not a **site** being harvested
under a **permit**) with a production-batch ground-truth entity and a
maintainable-equipment ground-truth entity. Furniture manufacturing
differs in the shape of the equipment/hazard set it coordinates
around: 1610 coordinates saw/planer/kiln equipment around a lumber-
grade/volume/moisture-content batch; 3100 coordinates cutting/
joinery/sanding/finishing-line equipment (including spray-booth
finishing) around a quality-grade/unit-count/defect-rate batch, with
its own distinct safety profile (VOC finish-fume exposure/spray-booth
fire risk alongside cutting-blade and wood-dust hazards, rather than
kiln-fire risk).

This vertical has NO pre-existing `kotoba-lang/furnituremfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic — pure functions in
`furnituremfg.registry` (equipment/batch verification, shipment-unit
recompute, quality-grade validation, defect-rate plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-1610`'s `sawmilling.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:furniture-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "furniture-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external furniture-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
furniture-manufacturing vertical has NO pre-existing capability
library to wrap. The equipment/batch-verification / shipment-unit /
quality-grade / defect-rate validation functions live as pure
functions in `furnituremfg.registry` and are re-verified independently
by `furnituremfg.governor` — the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1610`'s `sawmilling.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of furniture-
factory plant operations. It does NOT:
- Control cutting saws, sanders, or finishing-line (spray booth) equipment directly
- Make plant-safety or hazard decisions (exclusive to the human plant supervisor)
- Authorize or finalize a cutting/joinery/finishing-line run

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority —
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY BOUNDARY**: furniture manufacturing is a safety-
critical domain (cutting-blade injury risk, sanding/wood-dust
respiratory and explosion hazard, VOC finish-fume exposure and
spray-booth fire risk, heavy material handling). Safety-concern
flagging NEVER auto-commits. All safety concerns escalate immediately
to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (equipment hazard, VOC finish-fume exposure,
wood-dust hazard, crew fatigue/repetitive-strain concern) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" —
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-1610`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "factory/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date unit-count
plus the proposal's own claimed unit-count would exceed the batch's
own recorded unit-count — never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`furnituremfg.governor`, mirroring `cloud-itonami-isic-1610`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Factory/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's unit-count must independently recompute within the batch's own logged production output
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct cutting/joinery/finishing-line-equipment control or line-run finalization is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Furniture-factory plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or line-run finalization. Safety
concerns are a circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and line-run execution remain
human-controlled via external channels.

(-) No integration with real factory-management databases (equipment
telemetry, batch tracking, freight dispatch) — this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-3100`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-units-exceeded, line-finalize-blocked,
  already-scheduled, invalid-grade, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
