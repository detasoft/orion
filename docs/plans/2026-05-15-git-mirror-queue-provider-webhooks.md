# Git Mirror Queue and Provider Webhooks

## Goal

Add a durable mirror queue and provider webhook intake layer for GitHub/GitLab
repository mirroring.

This plan expands the existing mirroring roadmap item with the operational parts
that must be correct before mirroring can run unattended:

- durable mirror work records;
- outbound and inbound mirror job scheduling;
- idempotent retries and coalescing;
- provider webhook signature verification;
- mapping provider events to mirror configs;
- safe worker locking;
- sync result storage;
- admin-triggered dry-run and retry operations;
- observability and redaction.

The first implementation should support manual and event-driven queueing for
configured mirrors. Provider webhooks should enqueue work only; they should not
run Git fetch/push inline inside the HTTP request.

## Current State

`docs/plans/2026-05-14-github-gitlab-repository-mirroring.md` defines the
high-level mirroring feature: mirror config, refspecs, directions, credential
references, outbound local worker, provider support, inbound fetch, webhooks, and
bidirectional policy. It identifies missing durable queue, webhook endpoint, and
conflict policy, but it does not define the queue record model or worker
semantics.

`docs/plans/2026-05-15-git-repository-events-reflog-audit.md` defines Git
operation/ref update events that mirror synchronization can consume. This plan
uses those events as enqueue inputs; it does not redefine canonical Git event
schemas.

`docs/plans/2026-05-15-secret-reference-credential-management.md` defines secret
references and credential resolution. Mirror config should store credential
references and webhook secret references, never raw provider tokens.

`docs/plans/2026-05-15-git-smart-http-transport-adapters.md` and
`docs/plans/2026-05-15-git-ssh-transport-adapters.md` define outbound Git
transport adapters. Mirror workers should call those transport clients rather
than implementing Git protocol directly.

`docs/plans/2026-05-15-git-access-policy-protected-refs.md` defines protected
ref and hidden ref policy. Mirroring must use explicit mirror actor policy for
force pushes, deletes, tags, protected refs, and hidden refs.

There is currently no detailed mirror queue model, webhook request model,
deduplication key, lease protocol, retry policy, or durable run report shape.

## Non-Goals

Do not implement Git fetch, Git push, pack parsing, or protocol transport in this
plan. Mirror workers call native remote Git clients.

Do not implement full bidirectional conflict resolution first. Bidirectional
mirrors should stay disabled until explicit conflict policy is implemented.

Do not create remote repositories through provider REST APIs in the first queue
milestone.

Do not trigger builds directly from provider webhooks. Webhooks enqueue mirror
sync work only.

Do not store raw provider credentials, webhook secrets, auth headers, or remote
URLs with embedded credentials in queue records or logs.

Do not block local Git pushes on mirror completion by default. Local receive
events enqueue async work unless a later policy explicitly requests synchronous
mirroring.

Do not trust provider webhook payloads without signature verification and mirror
config matching.

Do not depend on JGit in production code.

## Mirror Directions

Support directions deliberately.

Outbound mirror:

- Orion is source of truth for selected refs;
- local Git ref update events enqueue push jobs;
- worker pushes selected refs to remote.

Inbound mirror:

- remote provider is source for selected refs;
- scheduled jobs, manual jobs, or verified webhooks enqueue fetch jobs;
- worker fetches selected refs and updates Orion according to policy.

Bidirectional mirror:

- later only;
- requires explicit conflict rules;
- requires loop prevention and source attribution;
- should not be enabled by generic queue behavior.

The queue model should support all directions, but initial worker behavior should
enable outbound and inbound separately.

## Mirror Config Additions

Extend mirror config with queue-facing fields.

Candidate fields:

- mirror id;
- Orion repository id;
- direction;
- enabled flag;
- remote provider;
- remote URL safe display;
- credential reference;
- webhook secret reference;
- inbound refspecs;
- outbound refspecs;
- force/delete/tag policy;
- protected ref policy mode;
- sync schedule;
- retry policy id;
- max concurrent runs;
- last enqueued event id;
- last successful run id;
- last successful local ref snapshot;
- last successful remote observation;
- disabled reason.

Mirror config must not contain raw credentials.

Changes to mirror config should invalidate or re-evaluate pending work when the
change affects direction, refspecs, remote identity, credentials, or policy.

## Queue Record Model

Introduce durable work records.

Candidate value objects:

- `GitMirrorWorkItem`;
- `GitMirrorRun`;
- `GitMirrorQueueStore`;
- `GitMirrorWorkerLease`;
- `GitMirrorTrigger`;
- `GitMirrorSyncPlan`;
- `GitMirrorSyncResult`;
- `GitMirrorRetryPolicy`;
- `GitMirrorProviderEvent`;
- `GitMirrorWebhookDelivery`;
- `GitMirrorRefMapping`;
- `GitMirrorConflictRecord`.

Work item fields:

- work id;
- mirror id;
- repository id;
- direction;
- trigger kind;
- trigger id;
- source event id when applicable;
- provider delivery id when applicable;
- ref names or refspec subset;
- requested old/new ids when known;
- priority;
- dedupe key;
- state;
- attempt count;
- next attempt time;
- lease owner;
- lease expiry;
- created time;
- updated time;
- safe diagnostics.

States:

- `PENDING`;
- `LEASED`;
- `RUNNING`;
- `SUCCEEDED`;
- `FAILED_RETRYABLE`;
- `FAILED_PERMANENT`;
- `CANCELLED`;
- `SUPERSEDED`;
- `PAUSED`;
- `DEAD_LETTER`.

Run records should be append-only summaries for each attempt.

## Trigger Kinds

Supported trigger kinds:

- local Git ref update event;
- manual admin request;
- scheduled sync;
- provider webhook;
- retry;
- startup recovery;
- config changed;
- maintenance repair.

The trigger kind affects priority, dedupe, and diagnostics, but not Git protocol
correctness.

Manual requests should be able to force a new work item even when a similar item
already exists, but they should still not violate mirror policy.

## Dedupe And Coalescing

Repeated events should not create unbounded duplicate work.

Dedupe key inputs:

- mirror id;
- direction;
- refspec group or ref name;
- trigger class;
- local source event id range where applicable;
- provider delivery id where applicable.

Coalescing rules:

- multiple local ref updates on the same branch can coalesce into one outbound
  push of the latest allowed state;
- multiple provider webhooks for the same remote ref can coalesce into one
  inbound fetch;
- manual forced jobs should not be silently coalesced unless caller asks for it;
- failed retryable jobs can be superseded by newer work that covers the same
  refspec;
- delete events must not be dropped unless a newer event makes the final desired
  state clear.

Coalescing must preserve enough source event ids for audit.

## Lease And Worker Model

Workers claim work through leases.

Lease requirements:

- conditional claim from `PENDING` or retryable state;
- lease owner id;
- lease expiry;
- heartbeat or extension for long jobs;
- stale lease recovery;
- maximum attempt count;
- worker shutdown releases or lets lease expire;
- no two workers run the same work item concurrently.

Worker flow:

1. Load enabled mirror config.
2. Claim due work item.
3. Resolve credential references.
4. Build sync plan from current repository and remote observations.
5. Execute Git operation through native transport.
6. Validate result.
7. Store run record.
8. Mark work item succeeded, retryable, permanent failure, or superseded.
9. Emit mirror event/audit record.

Worker leases should be backend-neutral: local file store first, S3-compatible or
database-backed later if needed.

## Retry Policy

Retry must distinguish transient and permanent failures.

Retryable examples:

- remote transport timeout;
- remote temporary unavailable;
- rate limit with retry-after;
- credential provider temporarily unavailable;
- stale local snapshot before operation starts;
- queue lease conflict;
- S3/local storage transient failure.

Permanent examples:

- invalid mirror config;
- credential denied;
- remote auth rejected;
- unsafe refspec;
- protected ref policy denied;
- missing repository where creation is disabled;
- webhook signature invalid;
- unsupported remote capability required by policy.

Policy fields:

- max attempts;
- initial delay;
- max delay;
- exponential backoff factor;
- jitter;
- dead-letter threshold;
- retry-after support;
- manual retry allowed flag.

Retries should preserve run history and safe failure diagnostics.

## Outbound Mirror Worker

Outbound jobs push selected local refs to remote.

Inputs:

- local repository id;
- source ref update events;
- mirror outbound refspecs;
- remote URL and credential reference;
- force/delete/tag policy;
- current local ref snapshot;
- remote advertised refs.

Flow:

1. Resolve mirror config and credentials.
2. Load local ref snapshot.
3. Map local refs to remote refs using outbound refspecs.
4. Filter by policy and source events.
5. Query remote refs through upload-pack/receive-pack advertisement where needed.
6. Build push commands with expected old ids.
7. Build or select pack objects for missing remote objects.
8. Execute receive-pack push through smart HTTP or SSH transport.
9. Parse report-status.
10. Record per-ref result.
11. Mark work item outcome.

Outbound mirror should not push every ref by default. Only configured refspecs
are eligible.

## Inbound Mirror Worker

Inbound jobs fetch remote refs and update Orion refs.

Inputs:

- mirror inbound refspecs;
- provider webhook hints or scheduled trigger;
- remote advertised refs;
- local destination refs;
- conflict policy;
- protected ref policy;
- credentials.

Flow:

1. Resolve mirror config and credentials.
2. Discover remote refs.
3. Map remote refs to local refs.
4. Compare with local destination refs.
5. Reject or plan non-fast-forward updates according to policy.
6. Fetch required objects through native remote fetch.
7. Validate object graph.
8. Publish objects to local repository storage.
9. Update refs through native ref store policy.
10. Emit ref update events with mirror actor.
11. Record per-ref result.

Inbound mirror must not overwrite local work unless policy permits it.

## Webhook Intake

Provider webhooks should be thin HTTP handlers.

Responsibilities:

- validate method and content type;
- read bounded body;
- identify provider from route or headers;
- verify signature before parsing trusted fields;
- parse delivery id;
- parse repository identity;
- parse event type;
- map repository identity to mirror config;
- create or coalesce queue work;
- return provider-appropriate status quickly.

Do not run Git fetch or push inline.

Webhook handler should store a bounded sanitized delivery record for diagnostics,
not raw secrets. Raw payload storage should be disabled by default or redacted.

## GitHub Webhooks

Initial GitHub support:

- `X-GitHub-Event`;
- `X-GitHub-Delivery`;
- `X-Hub-Signature-256`;
- JSON body;
- push event first.

Signature:

- HMAC-SHA256 over raw request body;
- secret from mirror webhook secret reference;
- constant-time comparison;
- reject missing signature when secret is configured;
- reject invalid signature before enqueue.

Push event mapping:

- provider repository id or full name;
- ref;
- before id;
- after id;
- created/deleted/forced flags;
- sender safe identity;
- delivery id.

Unsupported events can return success with ignored status or explicit unsupported
depending on provider expectations and operator preference.

## GitLab Webhooks

Initial GitLab support:

- `X-Gitlab-Event`;
- `X-Gitlab-Token` or supported signature header depending on provider config;
- JSON body;
- push hook first.

Token/signature:

- prefer signed/HMAC verification if configured and supported;
- support shared token verification when that is the provider mechanism;
- compare secrets safely;
- reject missing/invalid token before enqueue.

Push hook mapping:

- project id or path;
- ref;
- before id;
- after id;
- checkout sha when present;
- user safe identity;
- delivery id if available or generated idempotency key.

## Provider Identity Mapping

Webhook repository identity must match mirror config.

Allowed match keys:

- provider type;
- provider repository id;
- owner/name or namespace/path;
- configured remote URL canonical form;
- optional webhook endpoint id.

Rules:

- do not trust only display names;
- handle repository renames only after explicit config update or provider id
  match;
- reject events that map to multiple mirrors unless all matching mirrors are
  intended and enabled;
- do not reveal configured mirror names in unauthorized webhook responses.

The first implementation can require exact configured provider id or exact remote
URL canonical match.

## Refspec And Policy

Refspec handling should be shared by inbound and outbound mirror workers.

Rules:

- reject unsafe refspecs at config time;
- support explicit branch mapping first;
- support wildcard mappings only after tests cover ambiguity;
- reject mapping to hidden/internal refs unless mirror actor policy allows it;
- reject protected ref updates unless mirror policy allows them;
- handle deletes explicitly;
- handle tags explicitly;
- handle force updates explicitly.

Mirror policy should decide desired state before Git transport runs.

## Conflict Handling

Start with simple conflict behavior.

Outbound:

- if remote ref changed unexpectedly, fail retryable or conflict depending on
  policy;
- do not force unless configured;
- do not delete unless configured.

Inbound:

- if local ref changed unexpectedly, fail conflict;
- do not force unless configured;
- do not overwrite local-only commits without explicit policy.

Bidirectional:

- disabled first;
- later requires source attribution, last synced pair, and conflict records.

Conflict records should be visible to admin diagnostics and safe logs.

## Loop Prevention

Mirroring can create loops when Orion and remote both emit events.

Initial loop prevention:

- record mirror actor on ref update events;
- do not enqueue outbound work for inbound mirror updates unless policy says to
  propagate;
- record last successful local and remote ids per ref mapping;
- coalesce provider webhook events that match a just-completed outbound push;
- preserve source operation id in mirror run record.

Provider-specific loop prevention can be added later if APIs expose delivery or
actor metadata reliably.

## Queue Storage

Start with local durable storage.

Local layout example:

```text
<baseDir>/mirrors/
  configs/<mirror-id>.json
  queue/<work-id>.json
  runs/<mirror-id>/<run-id>.json
  deliveries/<provider>/<delivery-id>.json
```

Requirements:

- explicit schema version;
- atomic writes;
- startup reload;
- stale lease recovery;
- bounded completed work retention;
- dead-letter retention;
- corruption diagnostics;
- no raw credentials.

S3 or database-backed queue storage can be added later if multi-node workers are
needed.

## Admin Operations

Add admin operations after queue model is stable.

Useful operations:

- list mirrors;
- list pending work;
- list recent runs;
- enqueue manual sync;
- dry-run sync plan;
- retry failed work;
- cancel pending work;
- pause/resume mirror;
- disable mirror;
- inspect dead-letter item;
- mark superseded after operator confirmation.

Admin responses must include safe remote URLs and credential references only.

Dry-run should compute mapping and planned Git operations without pushing,
fetching objects, or updating refs.

## Security

Webhook and queue security rules:

- verify signatures before trusting payload fields;
- rate-limit webhook endpoints;
- bound request body size;
- use secret references for webhook secrets;
- do not log raw webhook secrets or auth headers;
- do not expose hidden refs in webhook responses;
- require admin permission for manual enqueue/retry/cancel;
- use mirror actor identity for Git policy decisions;
- keep provider payload storage redacted by default.

Provider webhooks should return generic responses for invalid signatures. Detailed
failure reasons go to internal diagnostics only.

## Observability

Metrics:

- work items created by trigger kind;
- work items coalesced;
- work item state counts;
- worker lease conflicts;
- worker run duration;
- retry count by reason;
- dead-letter count;
- webhook deliveries accepted;
- webhook signature failures;
- webhook mapping failures;
- outbound refs pushed;
- inbound refs fetched;
- conflicts detected;
- bytes fetched/pushed when available.

Logs:

- enqueue decisions;
- coalescing decisions;
- worker start/finish;
- permanent failures;
- webhook verification failures;
- provider mapping failures;
- admin manual actions.

Do not log credentials, raw provider tokens, webhook secrets, or object contents.

## Error Model

Typed failures:

- mirror disabled;
- mirror config missing;
- invalid refspec;
- credential resolution failed;
- remote auth failed;
- remote authorization denied;
- remote unavailable;
- transport unsupported;
- protected ref denied;
- hidden ref denied;
- non-fast-forward conflict;
- delete denied;
- force denied;
- provider webhook signature invalid;
- provider webhook unsupported event;
- provider repository unknown;
- webhook body too large;
- queue store unavailable;
- lease conflict;
- stale lease;
- retry exhausted;
- run superseded.

Every failure should indicate retryability.

## Phased Plan

Phase 1: Mirror queue model.

- Define work item, run record, trigger, state, retry policy, and safe
  diagnostics.
- Add serialization with schema version.
- Add redaction tests.

Phase 2: Local queue store.

- Implement atomic local persistence, listing, state transitions, and startup
  reload.
- Add stale lease recovery.
- Add corruption diagnostics.

Phase 3: Enqueue from Git ref events.

- Consume native Git ref update events.
- Match outbound mirror configs and refspecs.
- Create or coalesce outbound work items.

Phase 4: Worker lease and retry loop.

- Claim due work.
- Extend lease for long runs.
- Mark success, retryable failure, permanent failure, and dead-letter.
- Add backoff and jitter.

Phase 5: Outbound local mirror worker.

- Push configured refs to local/file remotes through native remote push
  primitives or scripted fixtures.
- Record per-ref results.
- Handle remote rejects and conflicts.

Phase 6: Manual admin operations.

- Add enqueue, dry-run, list, cancel, and retry operations.
- Keep HTTP/CLI surface minimal and safe.

Phase 7: Inbound scheduled fetch.

- Fetch configured remote refs manually or on schedule.
- Update local refs through mirror actor policy.
- Prevent overwriting local work by default.

Phase 8: Webhook intake model.

- Add provider delivery record, signature verification interface, event parser
  interface, and mapping diagnostics.

Phase 9: GitHub push webhook.

- Verify `X-Hub-Signature-256`.
- Parse push event.
- Map repository and ref.
- Enqueue inbound work.

Phase 10: GitLab push webhook.

- Verify configured token/signature mechanism.
- Parse push hook.
- Map project and ref.
- Enqueue inbound work.

Phase 11: Provider transport integration.

- Use smart HTTP/SSH transport credentials for GitHub/GitLab remotes.
- Keep provider REST API usage out unless needed for later setup validation.

Phase 12: Loop prevention.

- Record mirror actor and last successful mapping.
- Suppress outbound enqueue for inbound mirror updates by default.
- Coalesce webhooks matching just-completed outbound runs.

Phase 13: Bidirectional readiness.

- Add conflict record model and disabled-by-default bidirectional dry-run.
- Do not enable automatic bidirectional sync until policy is explicit.

## Verification

Queue:

- work item persists across restart;
- due work can be leased by one worker only;
- stale lease is recoverable;
- retryable failure schedules next attempt;
- permanent failure does not retry;
- max attempts moves item to dead letter;
- completed work is retained according to policy.

Enqueue:

- disabled mirror never enqueues work;
- ref update outside refspec does not enqueue;
- repeated branch updates coalesce;
- delete update is preserved when policy allows deletes;
- manual enqueue can force a new work item.

Outbound worker:

- pushes configured branch to local test remote;
- does not push unconfigured refs;
- remote non-fast-forward reject records conflict;
- force push happens only when configured;
- credentials are resolved through secret references and redacted.

Inbound worker:

- fetches configured remote branch;
- updates mapped local ref through mirror actor;
- refuses overwrite when local ref diverged and force is disabled;
- delete handling follows policy.

Webhooks:

- GitHub valid signature enqueues work;
- GitHub invalid signature rejects before parsing trusted fields;
- GitLab valid token/signature enqueues work;
- unsupported event is ignored or rejected according to policy;
- unknown repository does not expose mirror config details;
- duplicate delivery id is idempotent.

Loop prevention:

- inbound mirror update does not enqueue outbound work by default;
- outbound push followed by provider webhook coalesces or no-ops when refs match;
- bidirectional mirror remains disabled without explicit policy.

Admin:

- dry-run returns planned ref mappings without Git mutation;
- retry failed work creates or reactivates a work item;
- cancel pending work prevents worker execution;
- list APIs redact credentials and remote URL userinfo.

Production boundary:

- production code has no JGit dependency;
- webhook handlers do not run Git sync inline;
- logs do not include secrets or object contents.

## Rollout

Start with local queue storage and manual enqueue/dry-run so mirror behavior can
be inspected before automation.

Enable outbound mirroring from Git ref events before inbound mirroring.

Enable inbound scheduled/manual fetch before provider webhooks.

Enable provider webhooks only after signature verification, mapping, and queue
idempotency tests are complete.

Keep bidirectional mirroring disabled until explicit conflict and loop-prevention
policy passes dry-run tests.

Keep provider REST API automation out of the first rollout; document manual
webhook setup first.

## Open Questions

Should mirror queue storage live beside repository metadata, in a global mirror
store, or in the same operational store as application tokens/secrets?

Should completed work records be retained by count, age, or both?

Should mirror failures ever block local pushes for selected critical mirrors, or
should all mirror sync remain asynchronous?

Should webhook delivery payloads be stored redacted for debugging, or should only
parsed safe metadata be kept?

Should GitHub/GitLab webhook setup be automated through provider REST APIs later,
or remain an operator task?

Should bidirectional mirrors use one shared ref namespace for remote tracking
refs, or store last-seen remote ids only in mirror state?

## Acceptance Criteria

Mirror work is durable, idempotent, retryable, and recoverable after restart.

Outbound mirror jobs can be enqueued from local Git ref update events and run
asynchronously without blocking the original Git operation.

Inbound mirror jobs can be enqueued manually, on schedule, or by verified
provider webhooks.

GitHub and GitLab webhook handlers verify signatures/tokens, map deliveries to
mirror config, and enqueue work without running Git sync inline.

Workers enforce refspec, protected ref, force/delete/tag, credential, and access
policy before mutating remote or local refs.

Queue records, run records, admin responses, logs, and metrics contain safe
diagnostics only, with no raw credentials or provider secrets.
