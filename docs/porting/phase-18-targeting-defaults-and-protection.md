# Phase 18 - Targeting defaults and protection parity

## Legacy source contract

The 1.12.2 base constructs `TargetingSettings(false, true, false, 0, 0)` in
player/hostile/neutral order (`OMLib` `TargetingSettings` constructor and
`TurretBase.java` field initializer). Therefore a newly placed base targets hostile
mobs only; players and neutral mobs are disabled until configured. The legacy target
blacklist contains `ArmorStand`, and every tamed horse is protected independently of
its owner.

> Note: an earlier revision of this document misread `targetMobs=true` as "neutral
> mobs only" and shipped a neutral-only default. The 2026-06-19 audit
> (`D:\c128\audits\omt-audit-20260619`, finding F-K1) confirmed the hostile-only
> reading against the 1.12.2 sources and reverted the default.

## Corrected parity gaps

- New bases default to hostile `true`, neutral `false`, players `false` (legacy
  hostile-only behavior, restored after the F-K1 audit finding).
- Missing target fields during current-schema BlockEntity migration use those same
  legacy defaults.
- `MemoryCardProfile.DEFAULT` uses the same target flags as a new base.
- The generated `openmodularturrets:target_blacklist` entity-type tag now contains
  `minecraft:armor_stand`.
- Tamed `AbstractHorse` entities are rejected by base damage policy.
- The shared owner/trust decision is centralized in
  `TargetingRules.ownershipAllowsTarget`, so the `damage_trusted_players` switch
  applies identically to players and owned tameable entities while owners remain
  protected.

The existing 1.21.1 session-bound target-setting network path is retained. It is a
necessary security adaptation and preserves the old effective permissions: `USE` may
change target flags and multi-targeting, while `VIEW`/`NONE` cannot.

## Executable evidence

`OpenModularTurretsGameTests#targetProtectionPolicy` now verifies:

- new-base and default-memory-card flags;
- owner, owner-team, creative and spectator protection;
- owner/trusted tameable protection;
- both branches of `damage_trusted_players` ownership/trust policy;
- tamed-horse protection;
- the loaded ArmorStand blacklist tag;
- hostile category enable/disable behavior.

`OpenModularTurretsGameTests#baseCommandAuthorityAndBounds` already provides the exact
network authorization, invalid operand, forged session and distance evidence for
target flags and multi-targeting.

## Verification

Completed on 2026-08-01:

```text
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
pipeline.py --profile major: PASS
GameTest: PASS (46/46 exact runtime symbols)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
target_blacklist.json: minecraft:armor_stand present
```

One existing projectile collision fixture initially failed when the neutral-only
default was shipped, because it implicitly relied on the legacy hostile-on default.
The fixture now explicitly enables hostile targets, preserving the purpose of that
test without relying on the (now restored) hostile-only default.

