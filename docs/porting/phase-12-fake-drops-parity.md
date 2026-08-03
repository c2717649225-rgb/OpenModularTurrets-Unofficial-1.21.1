# Phase 12 - Fake Drops parity

## Scope and 1.12.2 baseline

This batch restores the original Fake Drops combat contract only. The authoritative
sources are `OMTFakePlayer`, `AbstractOMTDamageSource`, `OMTEventHandler` and OMLib's
diamond-material `FakeSword` in the 1.12.2 reference sources.

- Zero addons maps to level `-1`: the turret is not player-attributed.
- One through four-or-more addons map to levels `0` through `3`.
- Levels `0` through `3` use the fixed profile UUID
  `c5c97afa-fc98-44ab-944a-e67681a66b19`.
- The FakePlayer has Luck equal to the level and holds a diamond sword with Looting
  equal to the level.
- Vanilla death-loot processing, player-only predicates, experience and hooks see the
  FakePlayer as the causing entity. OMT only removes drops for the configured global
  loot and Loot Deleter branches; it does not add random counts to every stack.

## Required 1.21.1 adaptation

NeoForge's `FakePlayerFactory` owns a player per server level and profile. The port
requests and prepares that player for each damage source and never caches it in OMT,
so unloaded levels are not retained. The immutable projectile snapshot still owns the
Fake Drops level and Loot Deleter flag.

The 1.12.2 entity-tag relay is intentionally not reproduced. A modern `DamageSource`
can carry both the direct projectile and the causing FakePlayer, which supplies the
same vanilla loot context without mutating or persisting tags on victims.

## Acceptance for this batch

1. Source factories for levels `-1`, `0` and `3` expose the expected causing entity,
   Luck, Looting and direct entity.
2. A real server-side hurt attributes kill credit to the FakePlayer.
3. Loot Deleter and generic-damage control branches remain covered.
4. The obsolete manual random stack amplification helper is removed.
5. Contract, compile/static and GameTest gates pass.

## Deferred edge cases

- The rocket-versus-Ender-Dragon compatibility branch directly changes health and can
  bypass normal kill attribution; it remains a separate special-turret parity decision.
- Third-party loot code that inspects a particular registered OMLib FakeSword item
  instead of normal player/Looting context is outside this batch. The merged port uses
  a vanilla diamond sword because the legacy item was private infrastructure rather
  than user-facing gameplay content.

## Verification evidence

Completed on 2026-08-01:

```text
contract_gate.py --require: PASS (11 contracts, 0 errors)
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
gametest_gate.py --require-tests --run: PASS (43/43 required tests)
pipeline.py --profile major: PASS
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
```

The new `addonFakeDropsAttribution` GameTest proves levels `-1`, `0` and `3`,
direct/causing entity separation, the legacy UUID, diamond sword, Luck/Looting reset,
and real lethal kill credit. `addonLootAttribution` continues to prove the Loot Deleter
and generic-damage control branches.

The major pipeline's traceability stage remains advisory because ten existing contracts
are schema v1 and the targeting contract is v2 without mappings. This pre-existing
contract-migration backlog did not fail the pipeline and is not caused by Fake Drops.
