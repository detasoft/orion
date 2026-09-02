# Run the Orion-Facing Git Matrix in Maven and CI

Status: todo

Assemble the harness, scenarios, and engine adapters into a dedicated Maven
test module and make the Orion interoperability contract visible in CI.

## Scope

- Add the five required pairs: Orion/Orion, Orion/JGit, Orion/Git,
  JGit/Orion, and Git/Orion.
- Execute the ten shared scenarios for 50 named required invocations, subject
  only to explicit scenario capability declarations.
- Keep the four non-Orion control pairs available for harness verification or
  a slower CI profile.
- Migrate the active `git-projection-parity-native` workflows into the shared
  catalog and remove disabled placeholders once their scenarios are covered.
- Retain focused wire, transport, authentication, and runtime tests; the matrix
  complements rather than replaces those suites.
- Bound every process and network operation, close servers and clients after
  each isolated repository, and include commands, versions, and normalized
  snapshots in failure output.
- Report missing required matrix coverage as a failure rather than a JUnit
  assumption skip.

## Completion Criteria

- Routine development verification can run the dedicated module locally.
- CI reports each scenario/client/server case separately and leaves no daemon
  process, listener, or temporary repository behind.
- Every required scenario has at least one Orion side and every required
  Orion-facing pair is represented.
