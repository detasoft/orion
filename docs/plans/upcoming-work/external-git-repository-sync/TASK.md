# Implement External Git Repository Synchronization

Status: todo
Depends on: ../../current-work/unified-key-material-bootstrap/configuration-secret-cryptography/TASK.md,
../../current-work/hierarchical-orion-configuration/repository-and-mirror-configuration/TASK.md

Implement synchronization mode where an Orion repository can proxy an external
Git repository and keep refs and objects synchronized through ordinary Git
transports.

## Scope

- Support SSH remotes with configured private keys, passphrases, and
  known-hosts verification.
- Support HTTPS remotes with token-based credentials.
- Support GitHub App authentication through installation tokens and refresh
  logic.
- Model the feature as provider-neutral external Git synchronization first,
  with GitHub-specific credential and webhook profiles layered on top.
- Define inbound, outbound, and bidirectional modes with explicit refspecs,
  fast-forward policy, conflict reporting, retry behavior, and loop prevention.
- Provide administration state for configured external remotes, last sync run,
  pending work, and typed sync failures.
- Coordinate with `../github-commit-replication/TASK.md` so the older GitHub
  replication plan becomes either a GitHub profile or a narrower follow-up.

## Child Tasks

- [ ] [Add configurable branch filtering](branch-filtering/TASK.md).
- [ ] Define external remote configuration, credential references, and refspec
  validation.
- [ ] Add SSH and HTTPS Git client authentication support for sync workers.
- [ ] Add GitHub App installation-token credential provider.
- [ ] Implement outbound push synchronization from Orion to external remotes.
- [ ] Implement inbound fetch synchronization from external remotes to Orion.
- [ ] Add manual, scheduled, and webhook-triggered sync entry points.
- [ ] Add durable sync queue, run records, conflict handling, and retry policy.
