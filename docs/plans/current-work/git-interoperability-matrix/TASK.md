# Add the Orion Git Interoperability Matrix

Status: todo

Define Git workflow scenarios once and run them against combinations of the
Orion, JGit, and canonical Git clients and servers whenever at least one side
of the connection is Orion.

## Scope

- Cover repository creation fixtures, clone, commit, push, fetch, and
  fast-forward pull workflows through reusable typed Java scenarios.
- Require the five Orion-facing client/server combinations: Orion/Orion,
  Orion/JGit, Orion/Git, JGit/Orion, and Git/Orion.
- Use one unauthenticated loopback `git://` compatibility profile with a fixed
  protocol baseline; transport variants and authentication are not matrix
  dimensions in this task.
- Compare observable repository state, including refs, commit ancestry, trees,
  file modes, and file contents, rather than pack bytes or repository layout.
- Compose the existing Orion upload-pack and receive-pack primitives without
  claiming that they provide a production porcelain `clone` or `pull` API.
- Keep automatic creation of a missing remote repository by first push as an
  Orion-server extension because JGit and canonical Git servers require the
  repository to be provisioned first.

## Child Tasks

- [ ] [Add the shared Git workflow scenario catalog](workflow-scenarios/TASK.md)
- [ ] [Run the Orion-facing matrix in Maven and CI](matrix-ci-integration/TASK.md)
