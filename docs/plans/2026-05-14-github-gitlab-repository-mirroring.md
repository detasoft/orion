# GitHub and GitLab Repository Mirroring

## Goal

Mirror repositories between Orion and GitHub or GitLab while preserving clear
ownership, credentials, ref update rules, and failure recovery.

This should remain one of the later roadmap items because it depends on stronger
token management, durable event handling, repository update events, and clear
conflict rules.

## Current State

Orion can serve Git repositories through native Git, SSH, and HTTP routes.

`GitInternalService` publishes `GitReceiveOrionEvent` after receive-pack
operations, including repository name, user name, ref updates, update type, and
result.

Repository access control already distinguishes read, write, and create grants.
The repository provider can open and create local Git repositories.

There is no mirror configuration model, no remote credential model, no outbound
Git worker, no webhook endpoint, no durable retry queue, and no conflict policy.

## Non-Goals

Do not start with full bidirectional mirroring.

Do not couple this feature to one provider's REST API when Git transport can
solve the first push and fetch flows.

Do not store GitHub or GitLab tokens in plaintext configuration.

Do not mirror all refs by default without explicit refspec rules.

Do not trigger builds from external webhooks until mirroring and build triggers
have separate durable event paths.

## Scope

Add a mirror configuration model:

- Orion repository name;
- remote provider;
- remote URL;
- direction;
- refspecs;
- credential reference;
- enabled flag;
- last sync state;
- last successful revision;
- failure count and next retry time.

Support directions deliberately:

- outbound only: Orion pushes selected refs to remote;
- inbound only: Orion fetches selected refs from remote;
- bidirectional: later, only after conflict rules are explicit.

Use a durable mirror queue. Git receive events can enqueue outbound sync work,
but the sync operation itself must survive process restart and retry provider
failures.

Use a credential reference that can later be backed by the application-token or
secret-management plan.

## Phased Plan

Phase 1: Mirror model and validation.

Define provider type, remote URL validation, direction, refspecs, and conflict
policy. Add tests for invalid URLs, unsafe refspecs, duplicate mirrors, and
disabled mirrors.

Phase 2: Credential references.

Add mirror credential records or references without storing raw provider tokens
inside mirror config. Start with test credentials and local Git remotes before
integrating hosted providers.

Phase 3: Outbound local mirror worker.

Implement outbound push to a local or file URL remote from an Orion repository.
This proves refspec handling, retries, state updates, and error recording
without provider API complexity.

Phase 4: Outbound provider support.

Support HTTPS or SSH remotes for GitHub and GitLab using configured
credentials. Keep provider REST API usage minimal unless repository creation,
webhook management, or token validation requires it.

Phase 5: Inbound fetch.

Add scheduled or manually triggered inbound fetch. Decide how fetched remote
refs map into Orion refs. Avoid overwriting local work without an explicit
policy.

Phase 6: Webhooks.

Add provider webhook endpoints only after inbound fetch works manually. Verify
webhook signatures, map provider repository identity to mirror config, and
enqueue fetch work rather than performing sync inline.

Phase 7: Bidirectional policy.

Design bidirectional mirroring only after outbound and inbound paths are stable.
The policy must define non-fast-forward handling, force pushes, deleted refs,
tag updates, and simultaneous changes.

## Open Questions

Should Orion create remote repositories, or only mirror to repositories that
already exist?

Should each mirror sync all branches and tags, or require explicit refspecs?

How should force pushes and deleted refs be mirrored?

Should mirror failures block Git pushes, or only record asynchronous sync
failures?

Where should provider credentials live before secret management exists?

Should provider webhooks be configured by Orion automatically or documented as
manual setup?

## Verification

Cover at least these cases:

- mirror config rejects invalid remote URLs and unsafe refspecs;
- disabled mirror config never enqueues sync work;
- Git receive event enqueues outbound sync only for matching refs;
- outbound mirror pushes expected refs to a local test remote;
- failed push records failure reason and schedules retry;
- repeated events coalesce or run idempotently without duplicate state;
- deleted refs and tag updates follow explicit policy;
- inbound fetch imports only configured refs;
- webhook endpoint rejects missing or invalid signatures;
- provider credentials are never logged or returned from admin APIs.
