# Phase 15 - Per-turret upgrade configuration

## Scope

The 1.12.2 `TurretSetting` stored `accuracyUpgrade`, `efficiencyUpgrade` and
`recyclerNegateChance` on every turret definition. Their shipped values were the same,
but server owners could tune each turret independently. The first 1.21.1 pass replaced
them with global Java constants, preserving defaults but losing that configuration
surface.

This batch restores three bounded `0..1` SERVER config entries under every turret:

- `accuracy_upgrade = 0.2`
- `efficiency_upgrade = 0.08`
- `recycler_negate_chance = 0.10`

Formula code reads values at use time through `ModServerConfig.turret(definition)` and
never captures config values during static initialization. Recycler evaluation receives
the firing `TurretDefinition`, matching the old per-turret setting owner.

## Deferred configuration decisions

- Per-turret `enabled` was a 1.12.2 registration-time gate and cannot safely remove
  runtime registry entries in 1.21.1. Its modern behavior needs an explicit user choice.
- `useWhitelistForAmmo`, the mob blacklist, and third-party recipe selectors need data
  tags/compatibility policy decisions and remain out of this mechanical-default batch.
- The unused legacy `recyclerAddChance` field remains unported because no 1.12.2 runtime
  code consumed it.

## Acceptance

1. All eleven turret config records expose the three legacy defaults.
2. Accuracy and efficiency formulas read the firing turret values.
3. Recycler rolls use the firing turret's configured negate chance.
4. Existing energy, volley and config-default GameTests remain green.
5. Major pipeline passes with zero static/resource warnings.

## Verification evidence

Completed on 2026-08-01:

```text
subagent compile_and_repair.py --with-static: PASS (0 errors, 0 warnings)
root review: PASS (authorized six-file scope only)
pipeline.py --profile major: PASS
GameTest: PASS (44/44 required tests)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```

`legacyConfigDefaults` now checks all three values for every turret definition. Existing
upgrade-formula and recycler-volley boundary tests pass through the new per-definition
accessors. The schema-v1 acceptance-traceability advisory is unchanged and remains a
separate documentation migration backlog.
