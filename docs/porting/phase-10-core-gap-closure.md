# Phase 10: Core gap closure

## Scope

Second-pass comparison of the current 1.21.1 implementation against the 1.12.2 core source after runtime configuration was completed. Third-party integrations, additional locales, direct 1.12 world loading and the user-deferred rendering/UI defects remain outside this phase.

## Completed corrections

- Persisted and synchronized all five legacy per-head target priority weights; older head data migrates to the exact turret defaults.
- Persisted the owner's scoreboard team and restored unconditional owner/team protection.
- Applied `damage_trusted_players` consistently to trusted players and their tameable pets while preserving owner/team immunity.
- Unified configured beam and projectile damage at the same validated maximum instead of silently clamping projectiles to 2048.

## Audit disposition

- No remaining P0 core survival-loop, server-authority, network or persistence gap was found.
- Intermediate sensor/chamber/barrel/I/O items remain recipe components, matching 1.12.2.
- Throwable ammunition had no right-click implementation in 1.12.2 and is not expanded into new gameplay.
- Serial Port and Potentia behavior belongs to the explicitly deferred CC/OC/IC2 integrations.
- Item and entity allowlists remain modern datapack tags rather than conflicting TOML lists.

## Evidence

- Major feature contracts pass.
- Compile and static analysis pass with zero warnings.
- 42/42 GameTests pass, including `turretPriorityPersistence` and expanded `targetProtectionPolicy`.

## Next boundary

The remaining user-visible work is the deferred client phase: placed turret/attachment rendering, transparency and transform correctness, item/block model parity, then base-screen interaction and text layout defects.
