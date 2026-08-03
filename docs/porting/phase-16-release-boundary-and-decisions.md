# Phase 16 - Release boundary and deferred decisions

## Purpose

This phase closes the boundary between the faithful OpenModularTurrets 1.12.2 port
and optional modern integrations. It does not change gameplay. The default rule is:

> Preserve the behavior of the shipped 1.12.2 OpenModularTurrets and OMLib sources;
> adapt only where the 1.21.1 engine or NeoForge API requires a different mechanism.

## Included in the core port

- All OpenModularTurrets registry content and survival recipes that exist without an
  optional third-party mod.
- Base tiers, turret heads, ammunition, upgrades, addons, projectiles, targeting,
  security, persistence, menus, sounds and client presentation.
- The OMLib facilities actually required by OMT, merged into this mod's security,
  energy, menu and networking implementation. OMLib is not retained as a separate
  1.21.1 dependency.
- Necessary platform substitutions: metadata variants become stable registry ids,
  item NBT becomes Data Components, block/entity state uses modern persistence, and
  the old projectile class family is represented by one entity type plus a stable
  projectile-kind discriminator.

## Not separate missing content

- `FakeSword` was internal FakePlayer loot infrastructure, not a player-facing item.
- The old throwable-item classes did not implement a right-click throw action.
- The legacy Potentia hook had no effective gameplay implementation.
- The old JEI plugin only registered an advanced-GUI click area; it did not provide
  recipe categories or recipe logic.
- The unregistered legacy `windup` sound is not part of the 18 registered OMT sounds.

## Maintainer decisions resolved for this port

The maintainer accepted the following boundary and compatibility policy. These are
scope decisions, not missing implementation work:

1. **Serial Port:** retain the block, item and vanilla recipe as a reserved inert
   endpoint with its explanatory tooltip; do not add a ComputerCraft/OpenComputers
   integration in the core port.
2. **Per-turret `enabled`:** register all eleven legacy turret variants. The old
   registration-time switch is not reproduced as a runtime registry mutation.
3. **Optional recipe integrations:** keep only the 61 vanilla survival recipes. The
   obsolete Mekanism and EnderIO branches are intentionally outside this release.
4. **Public API:** guarantee gameplay and saved-data semantics, not source/binary
   compatibility with the old Java API.
5. **Modern integrations:** Jade, JEI/EMI and CC:Tweaked support remain optional
   follow-up features and are not required for 1.12.2 gameplay parity.
6. **Locales:** ship `en_us` and `zh_cn`; additional locales are out of scope for
   this release.
7. **World conversion:** no direct 1.12.2 world converter is promised. Legacy data
   meaning is preserved where the modern world loader can read it; a registry/NBT
   converter would be a separate product.
8. **Teleporter failure:** the 1.12.2 code teleported directly above the head and had
   no failure branch. In 1.21.1 the safe-destination search is performed before the
   volley is consumed; if every candidate is unsafe, energy and ammunition are
   untouched, no firing cooldown is started, and no launch sound is emitted.
9. **Special-turret cooldown:** retain the normal configured firing interval rather
   than reproducing the old Teleporter/Relativistic counter-reset defect.
10. **Attachment collision:** keep the visible-model-aligned modern collision shape;
    do not reintroduce the old 0.2-pixel inward offset.

## Remaining release proof

Core server mechanics have automated coverage, but a release claim still requires:

- a controlled client regression matrix covering every placed turret, attachment,
  expander, manual charger and base tier in inventory, world and GUI contexts;
- verification of GUI button labels, state transitions and initial-tab behavior;
- a clean release-profile gate, clean DataGen reconciliation and dedicated-server
  smoke test from the final artifact;
- a manual multiplayer authority/synchronization pass using the final artifact.

## Acceptance-contract status

The 11 feature contracts are valid schema-v1 contracts. The GameTest runner currently
provides exact runtime-symbol evidence for the current 52 GameTests, but the new toolkit's L4
traceability advisory cannot join schema-v1 acceptance text to those symbols. This is
an evidence-format migration, not a gameplay failure.

The v1 contracts must be migrated with
`.agents/contracts/migrate_v1_to_v2.py`, reviewed, linked only to real executable
providers, and then promoted from draft. Required DataGen, dedicated-server and manual
criteria must not be mislabeled as GameTests merely to silence the L4 advisory.

