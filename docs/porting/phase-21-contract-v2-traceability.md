# Phase 21 - Acceptance contract v2 traceability

## Purpose

The upgraded project toolkit can validate exact GameTest symbols only from schema-v2
contracts. This phase migrates the eleven existing feature contracts without changing
gameplay or weakening non-GameTest release requirements.

## Migration result

- All eleven formal contracts now use schema v2, contract version 2 and lifecycle
  status `verifying`.
- Every contract references
  `docs/porting/openmodularturrets-1.21.1-acceptance-design.md`, revision
  `confirmed-2026-08-01`, with a verified SHA-256 digest.
- All migrated review items were resolved: criteria have P0/P1/P2 risk, externally
  decidable observations and reviewed test links; `review_required` is empty.
- GameTest entries use unique, fully qualified runtime symbols only when the named
  method executes the claimed behavior.
- Mixed integration checks were split where a truthful one-method GameTest existed.
  Dedicated-server, DataGen, migration, performance and manual checks retained their
  real kinds.
- The special-turret contract includes the restored legacy scatter rule, and the
  survival/audio contract includes the real launch and bullet-impact dispatch tests.

The migration drafts, diffs and review reports remain under
`build/contract-v2-drafts` and `build/reports`; the formal contracts live under
`docs/features`.

## Traceability interpretation

The final advisory joins 57 acceptance criteria to exact passing runtime GameTest
symbols with no duplicate symbol ownership. Sixty-four required criteria remain
outside L4 because their declared evidence is deliberately DataGen, migration,
performance, dedicated-server, integration or manual-client verification. These are
honest release obligations, not missing schema fields, and must not be relabeled or
made optional merely to manufacture a strict GameTest-only pass.

## Verification

Completed on 2026-08-01:

```text
contract_gate: PASS (11/11 schema-v2 contracts, 0 errors)
traceability advisory: 57 exact runtime mappings, 0 duplicate GameTest refs
pipeline.py --profile major: PASS
compile/static: PASS (85 Java files, 0 errors, 0 warnings)
GameTest: PASS (48/48 exact runtime symbols)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```
