# Add Canonical Git and JGit Engine Adapters

Status: todo

Provide reusable client and server implementations for the two independent Git
engines used as interoperability references.

## Scope

- Generalize the existing JGit workflow client and daemon test server for the
  shared harness, with receive-pack enabled and loopback dynamic ports.
- Rename the current `NativeGitWorkflowClient` concept to `GitCliWorkflowClient`
  so it cannot be confused with the Orion native Git client.
- Add a canonical Git server fixture backed by `git daemon`, with receive-pack
  enabled, isolated repository roots, bounded process waits, and captured
  diagnostics.
- Implement init, commit, clone, fetch, fast-forward pull, branch/ref update,
  and push operations required by the shared scenario capabilities.
- Standardize `main`, author and committer identity, commit time, line endings,
  and command configuration across both clients.
- Record the JGit and `git --version` values in failure diagnostics.

## Completion Criteria

- The reference adapters pass the shared smoke scenario in JGit/JGit,
  JGit/Git, Git/JGit, and Git/Git control combinations.
- No test uses a fixed listener port or a user-level global Git configuration.
- Missing canonical Git is a clear prerequisite failure in required CI runs,
  not a silently skipped interoperability case.
