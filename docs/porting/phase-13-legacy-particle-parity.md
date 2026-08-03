# Phase 13 - Legacy particle parity

## Scope and baseline

This batch ports only the particle behavior visible in the 1.12.2 source. It does not
redesign projectile mechanics, models or sounds.

| Source behavior | 1.21.1 mapping |
| --- | --- |
| Rocket creates 21 normal-smoke particles per client tick with Gaussian `0.1` spread and no flame | Client projectile effect using vanilla `SMOKE` |
| Plasma has no flight trail | No client-tick plasma particle |
| Plasma impact sends `amount=15` through an inclusive legacy loop (16 flame + 16 large smoke), box ranges `2/1/2`, speed factor `0.2` | Server vanilla particle packets using the same effective count and extents |
| Rail Gun display tick creates six dust particles at block Y while expanded | `TurretHeadBlock.animateTick`, blue-weighted dust |
| Relativistic display tick creates six pale dust particles at Y+0.5 | `TurretHeadBlock.animateTick`, pale dust |
| Teleporter marks one 26-portal burst at its own block after a successful shot | Immediate server particle burst at the firing head; avoids the legacy block-singleton flag race |
| Four water bubbles trail every projectile in water | Existing client effect retained unchanged |

The server continues to choose impact and teleport moments. Client-only continuous
trails and random block-display effects stay presentation-only. Vanilla particle
packets replace OMLib's removed `MessageSpawnParticleQuad`; no new custom payload is
needed because no mod-specific state crosses the network.

## Acceptance

1. Shared visual-rule constants expose the exact effective legacy counts.
2. The client rocket/plasma branches match the table and contain no synthetic flame or
   electric-spark additions.
3. Plasma impact and Teleporter shot use server-driven vanilla particle packets.
4. Rail Gun concealment and both idle-dust positions match the old block hooks.
5. Contract, compile/static, GameTest and major gates pass.

## Explicitly deferred

- The modern bounded beam payload remains the required replacement for the removed
  1.12.2 GL ray renderer and is not changed in this batch.
- Rocket/grenade `0.1` fallback explosion semantics and projectile collision filtering
  affect gameplay as well as visuals; they will be handled in a dedicated projectile
  parity batch rather than hidden inside this cosmetic change.

## Verification evidence

Completed on 2026-08-01:

```text
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
pipeline.py --profile major: PASS
GameTest: PASS (43/43 required tests)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```

`visualStateContract` now fixes the effective legacy counts at `21`, `16`, `6` and
`26`. Continuous rocket/plasma behavior remains isolated in the physical-client class;
server particle packets are used only at authoritative impact/teleport moments. The
pre-existing schema-v1 traceability advisory remains unchanged and non-blocking.
