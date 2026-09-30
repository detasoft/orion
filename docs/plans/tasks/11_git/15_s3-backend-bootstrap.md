# Bootstrap the S3 Git Backend

- Owner: codex, session 01a0f177-8697-7993-a635-8992bcdf75cf, branch `codex/s3-backend-01a0f177`,
  worktree `.worktrees/s3-backend-01a0f177`, resumed 2026-09-30 11:57 Europe/Amsterdam;
  next: implement the approved connections/config foundation after rebasing the
  existing S3 metadata result. UI and SSH creation commands remain a later step.
  Integration remains declined. The metadata result rebased onto `c158cda9`
  is prepared as `5dc7f0f2ae80aa2bb3ff62bd9c5ce4ee0559f5f5`.

## Required result

Deliver the user-approved first S3 backend stage in a new `git/git-s3-storage`
module: real repository metadata create/find/exists/list, S3 configuration,
and two explicit stubs implementing the existing `GitIndexApi` and
`GitStorageApi`. Extend this foundation with named scoped connections in
`config.xml`, existing key-material-backed secrets, shared S3 clients, and
repository storage bindings. Prove the implemented behavior against MinIO.

## Dependencies and scope

Use the existing committed native repository and storage interfaces. This
bounded metadata/bootstrap stage is independent of the pending storage
architecture review: it neither extracts an external storage contract nor
implements Git object/ref publication semantics. The broader work in
`06_externalized-repository-storage.md` and its review dependency remain pending.
Do not implement refs, pack storage, push/fetch, load/save files, migrations,
maintenance, locks, or local fallback. Full stateless server operation remains
outside this stage because bootstrap Git reads require the deferred APIs.
Connection/repository creation through UI and SSH commands is explicitly
deferred. Do not add mutation endpoints or commands in this step.

## Current model and design

`NativeGitRepositoryProvider` owns repository discovery and creation;
`NativeGitRepository` composes `GitIndexApi` and `GitStorageApi`.
`BootstrapContext` currently chooses the file provider. Reuse these contracts
without adding another repository abstraction. Keep file-backed behavior and
the default configuration unchanged.

Keep the S3 implementation in the new module, with only necessary Maven,
configuration, composition, and test-fixture wiring elsewhere. Reuse existing
AWS SDK dependencies and S3 location/credential conventions where suitable.
Support a bucket, prefix, region, endpoint, and test-compatible credentials
without logging secrets. Make S3 explicitly selectable; validate configuration
and provide a clear failure for unsupported Git operations.

Use one `<connections>` collection under `<system>` and each organization,
with typed `<s3>` and `<ssh>` entries and names unique within their owner.
Connections are not restricted to system administrators: organizations may
configure their own connections. Ownership in this foundation is the enclosing
system/organization scope; do not invent per-user ownership or new ACL policy
before the deferred creation flows are designed. References identify the scope explicitly;
never fall back between organization and system names. Reject references to
another organization's connection. System-scoped use must be explicitly bound.

S3 connections own optional endpoint, signing region, addressing settings, and
authentication; repository storage bindings own connection reference and
`s3://bucket/prefix` location. One connection may serve multiple buckets and
prefixes. An omitted endpoint uses AWS regional resolution; an explicit AWS
endpoint may be `https://s3.eu-west-1.amazonaws.com`. MinIO examples use
`http://localhost:9000`. Preserve the documented default signing region when
omitted; endpoint omission does not eliminate request signing requirements.

Store credentials through existing `ConfigurationSecrets` encrypted envelopes
and `ConfigurationCipherCapability` from key-material. Connections contain
secret references, never plaintext secret keys. Reuse same-owner system and
organization secret resolution, including optional session tokens, rather than
introducing a second secret store or plaintext XML path. Keep SDK default
credentials available when explicit credentials are omitted.

`S3Transport` is the single outbound runtime component owning one S3 SDK client
and one HTTP connection pool, shared across all configured S3 connections,
repositories, buckets, and prefixes. Supply the same instance to repository
providers through application dependency injection. Replace the interim client
owner with this component rather than adding a forwarding wrapper or parallel
owner. Register it as a singleton capability, not as an incoming HTTP/SSH/Git
server in `OrionTransportModule`. Bind
credentials and endpoint resolution to each request through SDK request
overrides. Resolve endpoint, signing region, and bucket addressing from the
selected scoped connection via the standard S3 endpoint provider; do not mutate
shared client defaults, construct a custom signer, or use thread-local state.
All metadata requests, including paginator follow-up requests, carry their own
configuration. Capture one immutable configuration snapshot per operation.

Remove per-connection client maps and client replacement/reconciliation state.
Configuration or secret changes affect subsequent operations without replacing
the shared client or altering in-flight requests. Reuse one owned default
credentials provider for the default chain; explicit credentials resolve from
existing encrypted secrets for the selected snapshot. Repository closure does
not close shared resources. Application shutdown waits for operations and
releases the SDK client, HTTP pool, and owned default credentials provider.
Avoid a global static pool, generic transport framework, or SSH connection pool.

Split existing SSH proxy configuration into the canonical connection plus a
proxy binding containing alias, ref, and SSH repository path with explicit
connection reference. The SSH connection owns host/port/user, authentication,
secret reference, and trusted host keys. Preserve direct HTTP(S)/file proxy
behavior and existing operator projections. Remove the old inline SSH
authority/auth/trust path and update bootstrap adoption and host-key decisions
to use the connection. Do not broaden unrelated repository remote semantics.

Runtime repository resolution composes existing bootstrap/file behavior with
configured S3 bindings after XML is loaded. Bootstrap retains the minimum
external configuration needed to obtain XML and key-material itself, without
a circular dependency on the configuration being loaded. The existing optional
bootstrap `storage.endpoint` stays alongside `storage.location`, never inside
`storage.auth`; runtime connection configuration belongs in XML. Repository
read authorization remains separate from connection binding/configuration
ownership: authorized readers need not own a bound connection.
Keep configuration, key-material, and proxy cache repositories file-backed
until the data plane exists. Explicit S3 bindings are authoritative: never fall
back to same-name local data after an S3 failure. Listing includes a bound name
only when its S3 metadata exists, suppresses any same-name local repository,
and otherwise preserves unbound file repository discovery. No data migration
or automatic repository provisioning is part of reading a binding.

Update immutable document rebuilds, XML mappings/schema, validation, examples,
and every real consumer together. Preserve connections and storage bindings
through unrelated document edits. Do not add compatibility constructors or
parallel configuration paths to avoid updating consumers.

Persist minimal repository metadata under safe deterministic repository keys.
Use conditional creation so competing providers cannot overwrite an existing
repository. Discovery and opening must read S3, with no local durable state or
authoritative process cache. Preserve canonical repository-name validation and
the default HEAD target. Do not initialize refs through a fake index.

S3 index and pack-storage implementations remain explicit stubs. Operations
must report unsupported behavior rather than fake success or empty repository
contents. Define resource ownership and close semantics; metadata access must
continue to work across independent provider instances.

## Implementation plan

1. Inspect the existing native provider, configuration binding, S3 resolvers,
   lifecycle, and MinIO fixture; present the reused contracts and new public
   surface before implementing them.
2. Add the new Maven module and the two stubs, using existing API contracts.
3. Add minimal S3 metadata operations with conditional create, validated keys,
   bounded transport behavior, correct missing/error distinction, and explicit
   resource ownership.
4. Present typed connection, reference, and repository binding contracts before
   their implementations. Extend XML/domain validation and update all immutable
   document reconstruction paths.
5. Integrate existing encrypted secrets, shared-client lifecycle, and configured
   repository resolution; convert SSH proxy consumers to connection references.
6. Add AWS and MinIO XML examples and document bootstrap/runtime ownership and
   the still unsupported Git data plane.
7. Add observable-behavior tests and actual MinIO tests using the existing
   `MinioS3TestServer` fixture where practical. Keep tests in the new module
   when possible; avoid production dependencies on test support.

## Acceptance and verification

- Build the new module within the reactor and run `make test`.
- Run focused tests through `make test MODULE=... TEST=...` as appropriate.
- Run actual MinIO-backed tests, with the documented integration goal or a
  focused verify invocation if no Make goal supports the necessary selection.
- Verify metadata create/find/exists/list, missing repositories, duplicate and
  concurrent creation, reopening through an independent provider, repository
  and prefix isolation, invalid names/configuration, and storage failures.
- Verify configuration selects S3 and preserves the existing file default.
- Verify binding and use of `storage.endpoint`, plus omitted endpoint for AWS.
- Verify XML round trips, scoped duplicate names, unknown/wrong-type/cross-org
  references, encrypted secret resolution, and preservation during document edits.
- Verify two repository bindings on one connection, independent connections at
  the same and different endpoints, prefix/bucket isolation, and concurrent
  request credentials/signing-region/session-token isolation on one SDK client.
- Verify configuration/credential changes affect the next operation, paginator
  requests preserve overrides, active operations keep their captured settings,
  and the shared client/pool close safely on application shutdown.
- Verify existing HTTP/file/SSH proxy behavior, bootstrap adoption, and SSH
  trusted-host-key decisions through the canonical connection representation.
- Verify stub operations clearly fail and resource closure is safe.
- Report exact tested boundaries. Do not claim push/fetch or full server
  startup works with the intentionally incomplete APIs.

## Preserved behavior and limitations

Preserve existing file and memory backends, authorization above the repository
provider, canonical names, and existing API semantics. Parallel changes to Git
APIs in the primary worktree are unrelated and must not be copied or altered.
This stage establishes S3 metadata persistence and connections/config wiring;
the Git data plane and UI/SSH repository creation will be implemented later.
