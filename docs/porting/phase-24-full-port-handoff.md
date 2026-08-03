# Phase 24 - Full-port handoff

Date: 2026-08-01

## Honest completion estimate

- Core code and resource implementation: **100% of the agreed port scope**.
- Release-ready proof, including manual client and multiplayer acceptance: about **95%**.
- This is not yet a justified 100% release claim. The remaining core uncertainty is
  runtime presentation and multiplayer acceptance, not a known missing registry family.

## Implemented inventory

- 28 block variants and 66 item variants corresponding to all player-facing OMT
  1.12.2 metadata variants.
- Five base tiers, eleven turrets, ten expanders, Loot Deleter and Manual Charger.
- Seven projectile kinds, two menu types, eighteen registered sounds and sixty-one
  vanilla survival recipes.
- Base energy, inventories/capabilities, attachments, addons, upgrades, targeting,
  security/trust, redstone modes, camouflage, persistence, networking and client
  renderers.
- Item data uses Data Components. OMLib capabilities required by OMT are merged into
  this mod; OMLib is not a separate dependency.
- `en_us` and `zh_cn` each contain 267 matching translation keys. Other locales are
  intentionally outside the current scope.

The eleven schema-v2 feature contracts under `docs/features/` and earlier phase
reports contain the detailed mechanism-to-test mapping.

## Changes closed in this phase

1. Restored the exact legacy base-spacing rule: another base is rejected at
   Manhattan distance one or two, including planar diagonals.
2. Restored default tool-unbreakability for inventory expanders and owner-only,
   sneak-empty-hand removal for inventory and power expanders.
3. Restored the legacy creative-tab rocket-ammunition icon.
4. Restored Shift-expanded item information for bases, turrets, expanders, Manual
   Charger, Loot Deleter, components, addons, upgrades and ammunition. Numeric values
   are taken from the current definitions/configuration. Inventory-expander tooltips
   distinguish the fixed nine added slots from the tier-dependent per-slot stack limit,
   matching the original wording and handler behavior.
5. Connected GUI hover help for range, redstone mode, targeting, multi-target,
   inventory areas, removal controls and trust management.
6. Restored the two required OMLib compatibility switches, both defaulting to false:
   `can_op_access_owned_blocks` and `offline_mode_support`. Operators receive GUI-view
   access rather than settings/admin access, and are protected from targeting when
   the switch is enabled. Offline mode adds a case-insensitive name fallback after
   UUID matching for owner/local/global trust.
7. Restored legacy orphan cleanup drops: removing a base now drops adjacent turret
   heads and base attachments as item entities before they are removed, matching the
   1.12.2 `destroyBlock(..., true)` path.
8. Applied the same orphan-drop rule to the tier-one hand crank (`lever_block`),
   which also used to disappear silently when its base was removed. The regression
   test now covers turret head, power expander and hand-crank drops together.
9. Matched the agreed 1.21.1 Teleporter safety policy: a failed safe-destination
   search is resolved before resource consumption, so the attempt leaves energy,
   ammunition, shot statistics and firing cooldown unchanged and emits no launch
   sound. The 1.12.2 source has no failure branch and otherwise teleports directly
   above the head.
10. Tightened the Overview panel's fixed text stride by one pixel so the last
    statistic remains clear of the first control row with the shipped font. This
    is a presentation-only 1.21.1 layout adaptation; it does not change commands
    or server state.

## Latest automated evidence

- `python .agents/gates/pipeline.py --profile fast`: PASS; 90 Java files, zero
  compile errors, zero static errors and zero warnings.
- `python .agents/gates/compile_and_repair.py --with-data --with-assets`: PASS;
  291 generated JSON files, zero asset errors and zero asset warnings.
- Latest GameTest run: **52/52 PASS**, including the Teleporter no-safe-destination
  resource-free regression, base-spacing, expander-removal, orphan-drop (turret,
  power expander and hand crank) and OMLib ownership compatibility tests. The physical
  GameTest server shut down
  normally with no residual Java process.
- `gradlew build --no-daemon --console=plain`: BUILD SUCCESSFUL.
- Final post-audit `pipeline.py --profile fast`: PASS after the Overview layout
  adjustment; compile/static remains at 90 Java files with zero errors/warnings.
- `gradlew runClient --no-daemon --console=plain`: startup smoke PASS at 20:00:37;
  the latest development classes reached the Minecraft title screen, loaded the
  `openmodularturrets` mod, and completed a resource-manager reload including
  `mod/openmodularturrets`. No OMT-specific missing model, missing texture, sound
  load failure, exception or fatal error was emitted. This is a classpath smoke
  only; the artifact hash below remains the release artifact to test manually.
  The smoke client was closed cleanly after reaching the title screen.
- Final artifact:
  `build/libs/openmodularturrets-1.0.0.jar`
  - size: 1,119,723 bytes
  - SHA-256: `4B884AAA40567A60DE644CAEDE9D5A2DEA43794E4486276990FFA667D6096FFC`
  - 631 JAR entries; 264 OMT asset entries; 184 OMT data entries; zero
    `tutorialmod` entries.

## Work still required before claiming 100%

### Manual release blockers

Use the final JAR above, not the development classpath.

1. Client visual matrix:
   - all eleven turrets on all six legal faces;
   - item/hand/world forms, idle/aim/fire animation, concealment and addon overlays;
   - all five bases, ten expanders, Loot Deleter and Manual Charger;
   - daylight/night, transparency and optional shader/resource-pack combinations.
2. GUI matrix:
   - GUI scales 1 through 4, including a 320 by 240 logical screen;
   - all five base tiers and Overview/Target/Security/Camouflage pages;
   - empty/partial/full energy, initial page, hover text and rapid repeated clicks;
   - verify the redstone-mode label changes independently from the active switch.
3. Multiplayer authority/synchronization:
   - owner, VIEW, USE and ADMIN roles; local/global trust;
   - two clients changing settings concurrently, permission changes while open,
     disconnect/reconnect and chunk unload/reload;
   - particle/sound single-dispatch and consistent turret aim/state on both clients.
4. Survival smoke:
   - Recipe Book display/unlock for the 61 recipes and representative crafting
     remainder behavior;
   - audible sound attenuation and particle density/position for every turret family.

Any failure from this matrix should be treated as a concrete bug and accompanied by
the screenshot, `logs/latest.log`, exact block face/base tier and reproduction steps.

### Resolved scope decisions

The maintainer accepted the boundary in `phase-16-release-boundary-and-decisions.md`:

1. Serial Port stays a reserved inert block/item/vanilla recipe; no CC/OpenComputers
   integration is part of the core port.
2. All eleven legacy turret variants remain registered; no runtime `enabled` registry
   mutation is promised.
3. Only the 61 vanilla recipes ship; Mekanism/EnderIO branches are optional follow-up
   work, as are Jade/JEI/EMI/CC:Tweaked integrations.
4. Gameplay and saved-data semantics are guaranteed, not the old public Java API,
   additional locales, or a direct 1.12.2-world converter.
5. Teleporter failures are resource-free and do not start cooldown or play launch
   sound; the normal configured special-turret interval is retained, and the old
   cooldown defect plus 0.2-pixel collision offset are not reproduced.

The independent OMLib content not required by OMT (`network_cable`, debug/fake/multi
tools and cable block entity) remains outside the agreed merged-OMLib boundary.

## Repository and next-agent cautions

- The port and the upgraded `.agents` toolkit are currently a large dirty/untracked
  worktree. Do not use reset/checkout/clean commands. Preserve everything and create a
  normal reviewable commit or backup before attempting a clean release profile.
- The automated client smoke uses the latest development classpath and is not a
  substitute for final-JAR acceptance. Restart Minecraft with the SHA-256 artifact
  above for the manual matrix.
- Do not rerun every gate after each small observation. Reproduce first, change the
  smallest relevant code, run the targeted test, then run one final release profile
  after the worktree has been safely committed or otherwise made clean.

