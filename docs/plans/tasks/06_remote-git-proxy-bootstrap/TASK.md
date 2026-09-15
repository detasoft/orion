# Remote Git Proxy Bootstrap and GitHub Mirror Setup

Status: active

## Required result

An operator can configure an existing GitHub repository as the upstream of an
ordinary Orion repository, provide a credential safely, observe synchronization,
and accept branch changes on both sides with automatic conflict-free reconciliation
and manual resolution only when needed. Synchronization recovers after an outage
or restart. Remote bootstrap remains supported when
`orion.xml` and encrypted material themselves live in a Git repository.

## Current model and evidence

Bootstrap proxy runtime entered the repository in `e7eac547`. `BootstrapContext`
resolves configuration and material before Dagger construction and passes the
same provider and typed material capabilities to the runtime. The provider
refreshes logical handles and publishes upstream with expected object IDs.
Smart HTTP gzip decoding already exists in `GitHttpRequestBody`.

Bootstrap binding rollback and native HTTP/SSH boundary acceptance are verified
by `ac2f610f` and `79dd66b4`. The native acceptance covers shared inputs, refreshed
reads, upstream publication and stale-write rejection, cache-name isolation,
identity preservation on reopening, and unavailable upstreams.

`ConfigurationSecrets` owns stored credential resolution for both the GitHub
profile and persistent proxy connections. The proxy provider activates validated
configuration bindings through the existing transport path while retaining private
source handles. Public Git transports reject internal bootstrap cache names
independently of binding state. Automatic adoption and activation during application
startup remain pending.
`BootstrapContext` already preserves failure causes (`9b0bb402`). These are
implementation observations, not evidence that all acceptance checks passed.

The GitHub mirror foundation is separate: `RepositoryRemote`, scoped
`ConfigurationSecretReference`, `GitHubRemoteProfile`, `GitAttachment`, and
`FileGitSyncStateStore` already exist (`146b9e76`, `949644e6`). Extend them;
do not create another mirror engine inside the proxy provider.

## Behavior and ownership

A transparent proxy is an upstream-backed access path: refresh before a logical
read and report write success only after upstream publication succeeds. Bootstrap
must fail before public transports start when required upstream inputs fail.

An ordinary mirrored Orion repository owns its local refs and remains usable
while GitHub is offline. The required delivery reconciles compatible branches on
attachment and continuously synchronizes compatible changes in both directions.
Orion updates trigger outbound work; scheduled upstream observation imports
compatible GitHub changes. Divergent histories are merged automatically when a
three-way merge is conflict-free, preserving both histories. Actual merge
conflicts require manual resolution; no automatic rebase, force push, or choice
of one side discards the other. Runtime behavior is owned by
[stream 07](../07_external-git-repository-sync/TASK.md).

A stable scoped proxy alias may be exposed through authorized Git HTTP/SSH routes.
Its private cache name must never be accepted as a public repository identifier,
including after restart or failed adoption. An internal proxy reference is not
an endpoint for ordinary Git clients. Bootstrap hashes are not user-facing ids.

## Configuration and credential boundary

Process TOML/YAML retains the supported `bootstrap.accessControl` locator and
independent `bootstrap.keyMaterial` locator. The former selects `orion.xml`;
it does not make ACL responsible for upstream transport. Do not rename these
persisted fields or add an alias solely to match obsolete plan text.

Every launch resolves external `env:`/`file:` bootstrap credentials and material
password. Runtime configuration stores desired repository/proxy metadata and
credential references; encrypted values use the existing configuration cipher.
One credential owner serves proxy and mirror consumers. Coordinate its narrow
Git delivery with [02/05](../02_hierarchical-orion-configuration/05_secret-reference-credential-management.md);
do not require unrelated provider integrations before a GitHub token works.

Use actual system/organization/team/repository identities from `OrionDocument`.
Do not introduce a parallel project scope or a second repository remote model.
Operational queues, observations, conflicts, and retries remain outside XML.

## Dependencies and acceptance boundary

Mirror orchestration is owned by the claimed
[07/01](../07_external-git-repository-sync/01_primary-upstream.md). Its claim is
unchanged. That outbound foundation is an intermediate result. GitHub setup
also requires [07/03 bidirectional runtime](../07_external-git-repository-sync/03_github-commit-replication.md)
and runtime credential resolution, not completion of proxy UI, branch filtering,
SSH mirror support, GitHub Apps, webhooks, or a general-purpose secret-provider
framework.

The aggregate result requires both transparent remote-bootstrap acceptance and
the documented GitHub setup journey: attach an existing repository, clone from
Orion, push to either side and observe the same commit on the other, restart
without duplicate configuration or lost work, rotate the credential, and recover
from concurrent edits through automatic clean merges or manual conflict
resolution when necessary. Outbound-only synchronization does not satisfy this
aggregate result.

Use deterministic native upstreams in automated tests. A real GitHub smoke test
uses only an operator-designated test repository and credential; its absence
must be reported separately from local contract verification. No repository
creation, force push, deletion, or tag rewrite is implicit in setup.
