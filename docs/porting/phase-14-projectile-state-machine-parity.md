# Phase 14 - Projectile state-machine parity

## Scope

This batch ports deterministic collision and lifetime behavior shared by the seven
1.12.2 projectile classes. It follows the legacy classes directly while retaining the
modern server authority, entity persistence and target-protection system.

## Corrections

- Normal projectiles process age 40 and expire when age is greater than 40; plasma
  processes age 30 and expires when greater than 30.
- Grenades explode at age 39. Their first entity collision multiplies X/Z velocity by
  `0.2`, Y by `1.2`, sets age exactly to 30 and cannot repeat that transition.
- Every family passes through OMT turret heads and other OMT projectiles. A living
  entity rejected by the source base's authoritative safety policy is also passed
  through instead of consuming or detonating the shot.
- Plasma moves forward by `motion * 0.8` and stops before its impact particles, AABB and
  damage resolve.
- Area effects use an exact center-plus/minus-radius AABB rather than inflating the
  projectile entity's own bounding box.
- Vanilla damage immunity is reset after a complete legacy projectile damage sequence,
  preserving rapid fire while retaining the old ordering of split normal/piercing hits.
- Rocket and grenade terrain-disabled explosions use the legacy `0.1` strength instead
  of a synthetic multi-particle replacement.

## Necessary 1.21.1 adaptations

- Disposable and potato shots remain one tracked `ThrowableItemProjectile`. The old
  extra `EntityItem` was a rendering proxy with an unreachable pickup delay; vanilla
  tracked item rendering now supplies the same visible ammunition without doubling
  server entities.
- The current owner/trust/team and global target policy remains authoritative. The old
  AoE helper could damage almost every nearby non-tame entity even when it was not a
  legal selected target. Reverting that broader collateral behavior is a manual gameplay
  decision, not an incidental collision fix.
- Projectiles remain saveable with bounded snapshots. The old code deliberately killed
  them when serialization was attempted; persistence is the safer modern equivalent.

## Acceptance

1. Pure lifecycle and explosion-strength vectors match the declared legacy boundaries.
2. Collision policy rejects protected living entities, OMT projectiles and turret heads.
3. Grenade first-hit state survives entity save/load.
4. Existing live collision, persistence, Fake Drops and special-turret tests stay green.
5. Major pipeline passes with zero static/resource warnings.

## Verification evidence

Completed on 2026-08-01:

```text
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
gametest_gate.py --require-tests --run: PASS (44/44 required tests)
pipeline.py --profile major: PASS
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```

The new `projectileCollisionPolicy` test covers protected-owner pass-through, OMT
projectile pass-through, legal-hostile collision and turret-head pass-through.
`projectileLifetime` now fixes the `40/41`, `30/31` and grenade-39 boundaries, while
`projectileStatePersistence` covers the first-grenade-hit latch. Existing live projectile
damage, Fake Drops and target-protection tests remain green.
