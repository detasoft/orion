# Define the Git Interoperability Harness and Repository State Model

Status: todo

Turn the existing Git workflow test support into an engine-neutral Java API
that can execute one scenario against different client and server adapters.

## Scope

- Define typed contracts for scenario, client, worktree, server, remote
  repository, capability, and normalized operation result.
- Add a JUnit 5 parameterized runner whose test name identifies the scenario,
  client engine, and server engine.
- Replace the current `HEAD` plus worktree-file snapshot with a canonical state
  containing relevant refs, `HEAD` symref, reachable commits and parents, tree
  entries, file modes, blob identities, and content hashes.
- Support before/after snapshots for rejected operations so tests can prove
  that remote state did not change.
- Use deterministic branch names, identities, timestamps, repository names,
  and per-invocation temporary directories.
- Normalize semantic results such as accepted, rejected, and non-fast-forward;
  do not require identical engine-specific exception types or error text.

## Completion Criteria

- A minimal scenario can run unchanged with two test-double engine pairs.
- Snapshot differences report the first meaningful ref, history, tree, or file
  mismatch with both normalized states.
- The harness has no knowledge of SSH, HTTP authentication, or engine-specific
  repository filesystem layout.
