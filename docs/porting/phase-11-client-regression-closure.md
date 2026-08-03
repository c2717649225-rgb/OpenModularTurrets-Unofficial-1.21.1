# Phase 11 — Client regression closure

## Scope and authoritative baseline

- Baseline: `reference-sources/OpenModularTurrets-1.12` and `reference-sources/OMLib-1.12`.
- Preserve 1.12.2 gameplay.  Redstone mode changes may change the derived active state;
  attaching a turret with a larger native/upgraded range may raise the base range ceiling.
- Do not reinterpret either behavior as a new gameplay bug.

## Batch A — attachment transforms and overview clarity (verified)

- Fixed the generated attachment transform matrix to the exact legacy blockstate matrix:
  north `0`, south `Y=180`, west `Y=90`, east `Y=270`, down `X=270`, up `X=90`.
- The shared provider covers all five inventory expanders, all five power expanders and
  the loot deleter. Their 12x12x6 geometry, collision shapes and texture bytes already
  matched 1.12.2 and were intentionally left unchanged.
- Kept the manual charger renderer/model unchanged after source-by-source comparison:
  its two cubes, pivots, 32x32 texture, invisible host block and horizontal transforms
  match the legacy lever TESR. The item renderer did contain a lifecycle defect: it
  baked `manual_charger#main` during `RegisterClientExtensionsEvent`, before layer
  definitions are guaranteed to exist. It now stores `EntityModelSet`, bakes lazily on
  first render and clears the cached model on resource reload.
- The base overview now distinguishes redstone mode, actual signal and derived active
  state. The signal translation now consumes its state argument. Kills are compacted
  into one line and all overview rows use 11-pixel spacing, eliminating overlap with
  the controls at `y=108`.

Evidence on 2026-08-01:

```text
python .agents/run.py .agents/gates/compile_and_repair.py --with-data --with-static --with-assets
L1 compile: PASS
L2 static: PASS (0 errors, 0 warnings)
DataGen: PASS (291 JSON files)
L2.5 assets: PASS (0 errors, 0 warnings)
Clean runClient startup after lazy charger bake: PASS on NeoForge 21.1.234
```

## Batch B — placed turret lighting and compatibility isolation (verified)

Known facts:

- Held and placed heads use the same `TurretHeadModel`, texture path and
  `entityCutoutNoCull` render type.
- The installed test jar already contains neighbor-aware packed-light sampling.
- Textures are present and runtime logs contain no OMT missing-model or missing-texture
  warning.
- A clean development client rendered the placed machine-gun head with its intended gray
  texture in daylight, with no missing terrain faces around the invisible host block.
- The same copied world was then launched with the exact Sodium 0.8.13 beta and Iris
  1.8.14 beta jars from the user's instance. A controlled same-position capture showed
  the held and placed machine-gun heads with matching texture/brightness behavior.
- A damage-amplifier stack was injected into the base's real serialized inventory and the
  chunk was reloaded. Its red overlay rendered correctly without darkening or replacing
  the turret body. This rules out the basic addon-mask path and Sodium/Iris alone as the
  source of the earlier all-black screenshot.
- `turretProperties().noOcclusion()` is present for every turret head. The controlled
  scene showed no sky/terrain holes through the custom-rendered host block.
- Do not use full-bright as an unverified workaround: the 1.12.2 TESR respected world
  lighting.

Runtime evidence on 2026-08-01:

```text
Clean NeoForge 21.1.234, daylight, placed machine-gun turret: PASS
Sodium 0.8.13 beta + Iris 1.8.14 beta, held versus placed: PASS
Damage-amplifier overlay after inventory/chunk reload: PASS
Invisible host-block terrain-face integrity: PASS
```

The remaining manual matrix is broader than this isolated regression: verify every
turret on all six base faces, every allowed addon combination and continuous idle aim.
If the user still sees an all-black head after installing the next jar and fully
restarting, capture the exact mod/resource-pack/shader set and compare that instance;
the controlled reproduction no longer supports changing the core renderer blindly.

## Batch C — manual visual matrix

- All eleven plate attachments on all six faces; rendered bounds must match outline.
- Manual charger world/item forms on every horizontal base face.
- Base overview at GUI scales 1–3; no text overlap, and mode/signal/active are distinct.
- Range decrement/increment with no head, one head and mixed-range heads.
- Every turret idle, aimed and firing, without addons and with each allowed addon.
