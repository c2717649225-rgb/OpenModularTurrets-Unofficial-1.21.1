# v1.3 reference-host evidence slice

This fixture proves one narrow infrastructure statement on the repository's
starter host:

> DataGen can produce a GameTest structure, the real GameTestServer can load
> the host mod and mutate its world with a vanilla block, and the process-local
> L4 reporter can bind that execution to the exact Java symbol declared by a
> v2 contract.

It is intentionally stored outside `docs/features/`. The fixture is not a
playable feature contract, does not describe the user's mod design, and must
not satisfy the normal requirement for feature-specific contracts and tests.
Its Java package is deliberately outside the starter mod package, and its
DataGen provider does not depend on tutorial registrations, so the
`workspace_setup` minimal profile can retain this tooling self-check.

The JSON report retains a bounded raw and canonical event stream and binds the
reporter Java source, `mods.toml`, and Gradle init script with a stable control
digest. Traceability replays those events and compares the current controls.
This detects later evidence/control drift; it does not resist an actively
malicious mod forging or modifying evidence inside the same JVM.

Run the isolated vertical slice with:

```text
python .agents/run.py .agents/gates/pipeline.py --profile major --strict-traceability --allow-reference-host-only --contract-root .agents/eval/reference_host
```

The evidence mapping is:

```text
toolkit.reference-host / runtime.world-mutation
  -> dev.modstudio.referencehost.ReferenceHostGameTests#vanillaBlockRoundTripsThroughWorld
```
