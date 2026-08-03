# Phase 20 - Special-turret scatter parity

## Legacy source contract

In 1.12.2, `TurretHead#updateChecks` multiplied energy cost by `1 + scatter`, but
`TeleporterTurretTileEntity#update` and `RelativisticTurretTileEntity#update` each
performed their special action exactly once. Unlike projectile and ray turrets, their
action was not inside a scatter loop. Neither special turret required ammunition.

## Corrected parity gap

The port previously iterated every shot kind by `VolleyResources.projectileCount()`.
With a scatter upgrade this repeated teleport destination searches, teleports and
portal bursts, or repeatedly reapplied the Relativistic effects. That was not legacy
behavior.

`SpecialTurretRules.shotExecutions` now makes the execution cardinality explicit:

- Teleporter and Relativistic actions execute once per paid volley.
- Projectile and beam turrets execute the complete scatter volley.
- Resource reservation is unchanged, so scatter still multiplies special-turret
  energy cost and remains atomic, matching the old formula.
- Special turrets still consume no ammunition.

## Compatibility boundary

The collision-safe, loaded-chunk Teleporter destination search remains a necessary
1.21.1 safety adaptation. Its new failure state has no exact 1.12.2 equivalent, so
failure cost/sound/cooldown semantics are recorded in the phase-16 decision ledger.
The current common special-turret cooldown is likewise retained pending a decision on
whether to reproduce the old missing-tick-reset defect.

## Executable evidence

`OpenModularTurretsGameTests#specialTurretRules` verifies that a four-projectile paid
volley executes Teleporter and Relativistic actions once, while Machine Gun and Laser
execute four times. Existing volley tests continue to prove the scatter energy
multiplier and atomic resource reservation.

## Verification

Completed on 2026-08-01:

```text
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
pipeline.py --profile major: PASS
GameTest: PASS (48/48 exact runtime symbols)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```
