# Phase 17 - Base control authority and mode evidence

## Scope

This phase adds truthful executable evidence for the base-control behavior already
implemented in the port. It does not change gameplay.

The 1.12.2 OMLib/OMT source remains authoritative for the four redstone modes:

| Mode | Unpowered | Powered |
|---|---:|---:|
| Always On | on | on |
| Always Off | off | off |
| Inverted | on | off |
| Non-Inverted | off | on |

The current server-authoritative menu validation is retained as a necessary 1.21.1
security adaptation: the player must have the matching open menu, container id, base
position, dimension, loaded BlockEntity and access level, and be within eight blocks.

## Compatibility boundary

- The stable mode order remains `ALWAYS_ON`, `ALWAYS_OFF`, `INVERTED`,
  `NONINVERTED`; the default remains `INVERTED`.
- Current `mode_id`, legacy integer `mode`, legacy boolean `active`, and missing-mode
  fallback paths are covered.
- Direct conversion of every 1.12.2 base NBT shape is not claimed. Old nested
  `targetingSettings`, camelCase keys and registry metadata require a dedicated world
  converter if that becomes a release goal.
- `USE` access may change range/target flags/multi-targeting, matching the old GUI's
  visible permission model and fixing the old range handler's contradictory ADMIN-only
  check. Mode and destructive operations remain ADMIN-only.

## New executable evidence

- `OpenModularTurretsGameTests#baseModePersistenceAndMigration`
  verifies the complete truth table, cycle order, default, `setActive` compatibility,
  live redstone response, current save/load and legacy mode fallbacks.
- `OpenModularTurretsGameTests#baseCommandAuthorityAndBounds`
  verifies session binding, unknown commands, operand bounds, NONE/VIEW/USE/ADMIN
  permissions, range/target/multi mutations, mode mutation, distance rejection and
  both destructive commands.

## Verification

Completed on 2026-08-01:

```text
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
gametest_gate.py --require-tests --run: PASS (46/46 exact runtime symbols)
pipeline.py --profile major: PASS
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```

The L4 contract-traceability report remains advisory because the feature contracts are
still schema v1. Phase 16 records the truthful migration boundary; no non-GameTest
provider was relabeled to suppress that advisory.
