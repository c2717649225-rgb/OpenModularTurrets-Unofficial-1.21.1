# Phase 9: Core runtime configuration

## Objective

Replace duplicated gameplay constants with a server-authoritative NeoForge configuration while preserving the OpenModularTurrets 1.12.2 defaults and every existing registry id.

## Bounded scope

1. Register one `SERVER` config through `ModContainer`.
2. Expose the active base-tier energy/receive/turret-limit values.
3. Expose all eleven active turret definitions used by firing and upgrade formulas.
4. Expose power-expander capacity and solar/redstone-reactor generation.
5. Expose active scan/warning and legacy global behavior switches only when the current gameplay has a real consumer.
6. Add automated default and consumer tests, then run the contract, compile/static, DataGen/assets and GameTest gates.

## Compatibility rules

- Configuration never controls registration.
- Default values must match `OMTConfig.java` from the 1.12.2 reference source.
- Runtime code reads the active server value; values are not frozen during enum static initialization.
- Registry identity fields such as turret id, required base tier, ammunition tag and shot kind stay immutable.
- Legacy fields that only controlled removed third-party integrations are documented, not exposed as no-op settings.

## Deferred after this phase

- Editable target priority persistence/network/UI.
- ComputerCraft/OpenComputers and Serial Port behavior.
- Known model, texture, transparency and GUI interaction bugs reported during manual testing.

## Interruption handoff

The executable contract is `docs/features/core_runtime_configuration.contract.json`. If interrupted, resume from the first unchecked item in this document and run the contract gate before editing runtime code.

## 2026-07-31 checkpoint: numeric tuning slice complete

- Registered `ModServerConfig.SPEC` as a NeoForge `SERVER` config.
- Base energy capacity, receive rate and turret count now read live values.
- All eleven turret combat/upgrade numeric definitions now read live values.
- All five power-expander capacities and both addon generators now read live values.
- Added an unloaded-config fallback that exactly preserves the 1.12.2 defaults for early class loading and DataGen.
- `compile_and_repair --with-static` passes with zero warnings.
- All 40 GameTests pass, including `legacyConfigDefaults`.

Next bounded slice: active legacy behavior switches (ammo requirement, warnings/sound, camouflage/concealment, loot policy and optional projectile behavior). Known visual/UI bugs remain deferred by user instruction.

## 2026-07-31 checkpoint: active behavior switches complete

- Added live server settings for ammunition requirement, camouflage permission and concealment without an addon.
- Added live target-search interval, warning message/sound/distance and turret sound volume.
- Added mob-loot policy plus the legacy loot-addon override rule.
- Wired every setting to its real server-side consumer; no no-op configuration keys were introduced.
- Compile/static remains zero-warning and all 40 GameTests pass.

Next bounded slice: audit and implement the remaining projectile/world-interaction and global targeting switches according to their actual 1.12.2 call sites. Do not infer new behavior from config names alone.

## 2026-07-31 checkpoint: global targeting policy complete

- Added the three legacy global target category switches and applied them after each base's local choices.
- Added the legacy trusted-player damage switch while always preserving owner immunity.
- Kept the entity blacklist as the existing datapack entity-type tag and ammunition extension as existing item tags; duplicating them in TOML would create conflicting authorities.
- All 40 GameTests pass after the policy wiring.

Next exact implementation boundary: base/attachment breakability and explosion resistance, followed by the optional rocket/grenade/railgun world-interaction switches. Reference audit shows current defaults already match the old `false` projectile-destruction defaults, but configurable enablement is not yet ported.

## 2026-07-31 checkpoint: durability and projectile options complete

- Bases are directly unbreakable by default and expose the five legacy hardness/resistance pairs when `base_breakable` is enabled.
- Turret heads remain directly unbreakable as in 1.12.2; base attachments and both expander families honor the legacy attachment breakability switch.
- The manual charger keeps its independent legacy hardness 2 / resistance 15 behavior.
- Optional rocket homing, Ender Dragon damage, rocket/grenade block destruction and railgun block destruction are implemented with their old defaults and thresholds.
- `legacyDurabilityRules` plus `specialTurretRules` cover the new server rules; all 41 GameTests pass.

This closes the active core runtime configuration phase. Removed third-party recipe/integration switches remain intentional non-goals, while item/entity allowlists remain represented by modern datapack tags.
