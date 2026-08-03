# OpenModularTurrets 1.21.1 acceptance design

Revision: `confirmed-2026-08-01`

## Authority and fidelity

The shipped OpenModularTurrets and OMLib 1.12.2 sources are the gameplay authority.
The port preserves their observable mechanics, balance formulas, content, resources
and interaction rules unless Minecraft 1.21.1 or NeoForge requires a different safe
implementation. Necessary adaptations must retain server authority, reject forged
client input, avoid loading client classes on a dedicated server and be documented.

The detailed registry, persistence, Data Component, payload and capability mapping is
defined by `openmodularturrets-1.21.1-design-intake.md`. The phased implementation and
manual release boundary are defined by `openmodularturrets-1.21.1-supplement-plan.md`
and `phase-16-release-boundary-and-decisions.md`.

## Acceptance surfaces

### Core turret loop and configuration

- Each registered turret obeys its legacy base-tier, range, energy, ammunition,
  interval, damage, accuracy and upgrade rules.
- Resource reservation is atomic and server authoritative; insufficient volleys do
  not partially consume resources.
- Common/server code starts without client classes, and configuration values affect
  the matching turret only where the legacy config was per-turret.

### Base, GUI, targeting and persistence

- Base tiers expose their legacy turret capacities, energy limits, inventory growth,
  range bounds, addon limits and redstone truth table.
- GUI actions are intent-only C2S requests tied to the open menu, dimension, position,
  distance and access level; the server owns every mutation.
- Owner, trust, team, creative/spectator, tameable, tamed-horse and blacklist rules
  protect targets according to the legacy defaults and configured overrides.
- Current saved state round-trips. Explicitly supported legacy field shapes migrate
  deterministically; malformed or absent fields use documented safe defaults.
- Memory-card item state uses a Data Component and does not make the item authoritative
  over the target base.

### Projectiles and special turrets

- Projectile movement, collision, damage attribution, explosions, incendiary/plasma
  effects, homing, lifetime, save/load and fake-player drops are server resolved.
- Laser and Rail Gun preserve their distinct armor response, damage type and beam
  presentation. Relativistic rejects already-slowed targets. Teleporter uses a bounded
  loaded, supported and collision-free destination search as a 1.21.1 safety
  adaptation.
- Scatter multiplies paid special-turret energy but executes Teleporter and
  Relativistic actions once, matching 1.12.2.

### Addons, camouflage and attachments

- Recycler, damage amplifier, redstone reactor, solar generator, concealer and serial
  port retain their legacy base-tier placement, inventory, energy and side-effect
  rules where the old dependency was functional.
- Camouflage state is server owned, persistent, synchronized and rendered without
  replacing collision, ownership or machine identity.
- Expanders and the manual charger attach to valid base tiers and faces, expose the
  correct capability/state contribution and retain consistent item/world transforms.

### Survival content, audio and client presentation

- All first-party recipes, loot, tags, damage types, sounds, language keys and models
  reconcile through DataGen or checked manual assets.
- Combat audio is emitted once by the server with the legacy sound id, category,
  volume and pitch contract.
- Every base, turret, attachment and expander has a valid inventory model and placed
  presentation. GUI tabs, labels, buttons and state changes remain readable and map
  to the matching server command.

## Evidence policy

- A GameTest reference is used only when that exact method executes the claimed
  behavior.
- DataGen, asset, compile/static, dedicated-server and manual-client checks remain
  their real test kinds; they are never relabeled as GameTests for traceability.
- Required gameplay/authority/persistence failures are P0 or P1. Cosmetic-only and
  non-blocking presentation checks may be P2.
- A release claim requires clean major/release gates plus the controlled client and
  multiplayer manual matrix from the final built artifact.

## Deferred product decisions

The decisions listed in `phase-16-release-boundary-and-decisions.md` are outside the
current fidelity claim until the maintainer chooses them. In particular, the port does
not silently choose optional computer-mod integrations, direct raw 1.12.2 world
conversion, Teleporter failed-safe-destination cost semantics or reproduction of the
legacy special-turret cooldown defect.
