# Phase 22 - Dedicated-server and artifact smoke

## Scope

This phase validates the reviewed schema-v2 contracts, common/client class boundary
and packaged mod artifact. It does not replace the final client or multiplayer manual
matrix.

## Dedicated server

`compile_and_repair.py --with-static --with-contracts --with-server` compiled the
current source, validated all eleven contracts and started the NeoForge dedicated
server until the authoritative `Done` marker. The gate then issued a graceful stop.
No ERROR log line was observed.

## Packaged artifact

`gradlew build --no-daemon --console=plain` completed successfully. Inspection of
`build/libs/openmodularturrets-1.0.0.jar` recorded:

```text
size: 1,099,690 bytes
sha256: deae946ec8ab8ff8f368b9051f8a354ae790e14535d7e6d1da91423e4cbcad39
zip entries: 626
assets/openmodularturrets entries: 264
data/openmodularturrets entries: 184
tutorialmod entries: 0
mod id: openmodularturrets
display name: OpenModularTurrets-Unofficial
```

This digest includes the phase-23 atomic GUI input and narrow-layout fixes. It remains
a test-artifact identity until the client and multiplayer manual matrices pass; any
later fix or metadata change must rebuild the jar and record a new digest.

## Verification

Completed on 2026-08-01:

```text
pipeline.py --profile major: PASS
GameTest: PASS (48/48)
compile/static: PASS (85 Java files, 0 errors, 0 warnings)
DataGen/assets: PASS (291 JSON files, 0 errors, 0 warnings)
dedicated-server smoke: PASS (Done, 0 ERROR lines)
gradlew build: BUILD SUCCESSFUL
```

The toolkit's full release profile additionally requires a clean Git worktree for its
DataGen reproducibility assertion. This shared worktree intentionally contains the
uncommitted port and toolkit update, so that single clean-tree assertion cannot be
claimed until the maintainer chooses a commit/staging boundary. All non-clean-tree
release checks above were executed directly.
