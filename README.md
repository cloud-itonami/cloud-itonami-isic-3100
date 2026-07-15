# cloud-itonami-isic-3100: Manufacture of furniture

Open Business Blueprint for **ISIC Rev.5 3100**: manufacture of furniture — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office furniture-factory **plant operations**: production-batch data logging (quality-grade/unit-count/defect-rate across cutting, joinery/assembly and finishing/upholstery lines), cutting/sanding/finishing-equipment maintenance scheduling, safety-concern flagging, and outbound furniture-shipment coordination.

This repository designs a forkable OSS business for furniture-factory
plant operations: run by a qualified operator so a furniture
manufacturer keeps its own operating records instead of renting a
closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — cutting/assembly/finishing batch and output-quality (quality-grade/unit-count/defect-rate) data logging (administrative, not an operational decision)
- `:schedule-maintenance` — cutting/sanding/finishing-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety (VOC finish-fume exposure)/equipment-safety/labor-safety concern (always escalates)
- `:coordinate-shipment` — outbound furniture-shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(cutting-blade injury risk, sanding-dust/wood-dust hazard, VOC finish-
fume/spray-booth fire and respiratory hazard, heavy material
handling):

- Does NOT control cutting saws, sanders, or finishing-line (spray booth) equipment directly
- Does NOT make plant-safety or hazard decisions (that's the plant supervisor's exclusive human authority)
- Does NOT authorize or finalize a cutting/joinery/finishing-line run (human plant supervisor decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`furnituremfg.operation/build`, a langgraph-clj StateGraph):
1. **`furnituremfg.advisor`** (sealed intelligence node, `FurnitureAdvisor`): proposes decisions only, never commits
2. **`furnituremfg.governor`** (independent, `Furniture Plant Operations Governor`): validates against domain rules, re-derived from `furnituremfg.registry`'s pure functions and `furnituremfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct cutting/joinery/finishing-line-equipment control)
     - Finalizing/directly executing a cutting/joinery/finishing-line run (`:finalize? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped unit-count past its own logged unit-count (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:quality-grade` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`furnituremfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`furnituremfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
