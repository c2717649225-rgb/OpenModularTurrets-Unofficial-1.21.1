# Phase 19 - Sound dispatch parity

## Scope

This phase restores the 1.12.2 server-side combat sound contract without changing
turret damage, targeting, ammunition or energy behavior. Sound dispatch remains
server authoritative so a dedicated server produces one audible event and clients do
not duplicate it locally.

## Restored legacy behavior

- Ordinary turret launch sounds use the configured turret volume and a randomized
  pitch in the legacy `[0.5, 1.5)` interval.
- Damage Amplifier launch pitch uses the same legacy random interval.
- Relativistic and Teleporter launch sounds retain their legacy fixed volume `0.6`
  and pitch `1.0`.
- Beam impacts and bullet impacts use `SoundSource.AMBIENT`, the configured sound
  volume and the legacy randomized pitch interval.
- Concealment deployment and retraction use the configured sound volume and the
  legacy randomized pitch interval.

The 1.21.1 sound registry and `PlayLevelSoundEvent.AtPosition` event surface are
modern platform adaptations; the observable category, volume, pitch and single
server-side dispatch preserve the old gameplay contract.

## Executable evidence

`OpenModularTurretsGameTests#launchSoundDispatchContract` drives a real machine-gun
turret through its firing loop and verifies exactly one server-side launch event at
the fixture, `SoundSource.BLOCKS`, configured volume and pitch in `[0.5, 1.5)`.

`OpenModularTurretsGameTests#bulletImpactSoundDispatchContract` drives a real bullet
collision and verifies exactly one server-side impact event near the fixture,
`SoundSource.AMBIENT`, configured volume and pitch in `[0.5, 1.5)`. The listener is
spatially filtered and always unregistered, so parallel GameTests cannot pollute the
result.

## Deferred special-turret decision

Whether a Teleporter shot with no safe 1.21.1 destination should consume resources,
enter cooldown or play its launch sound belongs to the special-turret state-machine
audit. The old mod did not perform the same modern collision-safe destination search,
so this phase deliberately does not guess at that compatibility boundary.

## Verification

Completed on 2026-08-01:

```text
gametest-sound-stability-2.json: PASS (48/48)
gametest-sound-stability-3.json: PASS (48/48)
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
pipeline.py --profile major: PASS
GameTest: PASS (48/48 exact runtime symbols)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```
