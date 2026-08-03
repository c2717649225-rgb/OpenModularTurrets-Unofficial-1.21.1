# Phase 23 - Client matrix audit and atomic GUI input

## Static visual matrix

A second source-by-source audit found no unresolved model or texture route in the
reported problem families:

- All eleven turret definitions use the same model, texture and cutout render type in
  their item and placed renderers. Ten distinct turret textures are byte-identical to
  1.12.2; Plasma deliberately reuses the legacy Grenade Launcher model and texture.
- All five bases, ten expanders, Loot Deleter and Manual Charger have complete
  blockstate/block-model/item-model or custom-renderer chains.
- The twenty-nine relevant base/attachment/charger PNG files are 32 by 32 and
  byte-identical to their legacy source files.
- Expander tier five has no base-tier restriction, matching 1.12.2. Existing
  placement GameTests cover all ten expanders and the tier-five inventory expander on
  a tier-one base.
- Manual Charger remains restricted to the four horizontal faces of a tier-one base,
  matching the old rule. Its item renderer bakes lazily and resets on resource reload.

The only measured attachment-shape difference is a legacy 0.2-pixel inward offset of
the selection/collision plate. The current shape exactly follows the visible model;
this does not explain a visible model offset and is left as a low-impact fidelity
decision.

## Restored rapid-click semantics

The old GUI sent relative actions and the server applied each click. The port had sent
absolute values computed from the most recent `ContainerData` snapshot. Multiple
clicks before the next sync could therefore collapse into one mode step, one range
step or an overwritten target bitmask.

New append-only `BaseCommand` ids provide server-atomic operations for:

- range adjustment;
- mode cycling;
- multi-target toggling;
- one target-category bit toggle;
- camouflage light and opacity adjustment.

The old absolute setters remain validated for compatibility and tests. The screen now
uses the atomic commands, while `BaseCommandService` validates the operand, current
menu session, access level, distance and resulting bounds before mutation.
`baseCommandAuthorityAndBounds` sends consecutive relative packets without an
intermediate client sync and verifies that every operation is retained.

## Narrow-screen adaptation

The original combined screen was 344 logical pixels wide and clipped at Minecraft's
minimum 320 by 240 GUI canvas. At widths below 344, the screen now uses a 176-pixel
machine panel plus a 144-pixel responsive side panel. Tabs, trust list, trust actions,
camouflage controls and labels derive their widths from that panel; wide screens keep
the existing 180+164 layout. This is presentation-only and does not move machine
slots or change protocol state.

The Security tab's enabled state is now refreshed with synchronized permissions rather
than only when the screen is constructed.

## Verification

Completed on 2026-08-01:

```text
compile_and_repair.py --with-static: PASS (85 Java files, 0 errors, 0 warnings)
pipeline.py --profile major: PASS
GameTest: PASS (48/48; sequential atomic command assertions included)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
clean development-client startup before the patch: PASS, no OMT missing model/texture/error
```

The final in-world six-face and GUI-scale capture matrix remains a manual release
check; a static resource audit cannot honestly replace it.
