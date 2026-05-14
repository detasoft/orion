# Git SSH Transport Adapters

## Goal

Add JGit-free SSH transport adapters for Git upload-pack and receive-pack.

This plan covers two directions:

- server-side Orion SSH command handling for native Git repositories;
- client-side outbound SSH transport for native remote fetch, push, mirroring,
  and Git-backed resource reads.

The shared Git wire protocol core should continue to own pkt-line framing,
capability parsing, protocol v2 sections, side-band handling, and report-status
helpers. The SSH adapter owns SSH session setup, host key handling,
authentication, command parsing, environment handling, stream lifecycle, limits,
and mapping SSH failures to typed Git transport failures.

## Current State

Orion already has a server-side SSH transport in `net/git-transport` using
Apache MINA SSHD. `GitSshTransportService` configures the SSH server, host key
provider, password/public-key authenticator, disabled forwarding, command
factory, and shell factory.

The existing SSH route is covered by `GitSshTransportEndToEndIT`. Current tests
exercise the Orion SSH/Git/auth pipeline, but the repository protocol behavior
still depends on the existing Git service and JGit-backed repositories in normal
paths.

`docs/plans/2026-05-14-native-git-wire-protocol-core.md` defines JGit-free
parsing for socket/SSH-style initial commands such as:

```text
git-upload-pack '/repo.git'\0host=example\0\0
git-receive-pack '/repo.git'\0host=example\0\0
```

It explicitly does not implement SSH client or server adapters.

`docs/plans/2026-05-14-native-git-protocol-client-primitives.md` lists outbound
SSH as a later transport for private-key auth and direct `git-upload-pack` /
`git-receive-pack` execution. It requires known-host validation by default.

`docs/plans/2026-05-14-key-material-keystore-and-ca.md` covers server SSH host
key storage and rotation, but it does not define Git SSH session behavior.

`docs/plans/2026-05-15-git-smart-http-transport-adapters.md` covers HTTP(S), not
SSH.

Remote ACL/config/resource access still has JGit SSH usage in some paths. Native
Git needs a dedicated SSH adapter before those paths can stop depending on
JGit's SSH transport.

## Non-Goals

Do not implement Git pkt-line parsing, capability parsing, side-band, or
report-status logic in the SSH adapter. Use the shared wire protocol core and
protocol client primitives.

Do not implement repository object lookup, pack parsing, pack building, ref
storage, upload-pack policy, or receive-pack policy here.

Do not replace the existing server-side SSH transport in one step. Add a native
adapter boundary that can be used behind feature flags and compatibility tests.

Do not implement a general interactive SSH shell in this plan.

Do not enable port forwarding, agent forwarding, X11 forwarding, or arbitrary
remote commands for Git transport.

Do not shell out to `git-upload-pack` or `git-receive-pack` in production code.

Do not depend on JGit in production code. Tests may compare behavior with Git CLI
or existing JGit-backed fixtures.

Do not store user private keys in Orion unless a separate credential-storage
policy explicitly allows it. Prefer secret references and caller-provided
credentials.

## SSH Transport Basics

Git over SSH runs a remote command over an SSH exec channel:

```text
git-upload-pack '<repository>'
git-receive-pack '<repository>'
```

The command's standard input and output become the Git protocol byte stream.
Standard error may carry progress or server diagnostics depending on the client
and implementation.

Protocol v2 is usually requested through the `GIT_PROTOCOL` environment
variable:

```text
GIT_PROTOCOL=version=2
```

The SSH adapter must preserve a binary-safe stream boundary. It should not treat
Git packet payloads as text after command parsing is complete.

## Adapter Boundaries

Server-side adapter responsibilities:

- configure SSHD lifecycle, host keys, algorithms, auth methods, and limits;
- authenticate the SSH session;
- parse allowed Git exec commands;
- reject non-Git commands or interactive commands according to policy;
- capture `GIT_PROTOCOL` environment values where supported;
- build a Git operation subject from the SSH principal;
- resolve repository path through a safe route normalizer;
- pass binary streams to native upload-pack or receive-pack service;
- propagate cancellation and disconnects;
- emit SSH access and Git operation diagnostics.

Client-side adapter responsibilities:

- parse SSH repository locations;
- resolve credentials from secret references;
- validate known hosts by default;
- open an SSH exec channel for upload-pack or receive-pack;
- send protocol environment hints where supported;
- expose stdin/stdout/stderr streams to protocol client primitives;
- map SSH status, auth, host-key, timeout, and stream failures to typed errors;
- sanitize diagnostics.

Shared responsibilities:

- service command model;
- repository path quoting and validation rules;
- SSH transport error model;
- redaction helpers for usernames, hostnames, paths, and secret references;
- test fixtures for scripted SSH channels.

## Server Command Handling

Only these commands should be accepted for Git transport:

- `git-upload-pack <repo>`;
- `git-upload-pack '<repo>'`;
- `git-receive-pack <repo>`;
- `git-receive-pack '<repo>'`.

Parsing rules:

- reject empty commands;
- reject commands with shell operators;
- reject multiple commands;
- reject unknown executables;
- reject absolute local filesystem paths unless repository routing explicitly
  supports them;
- decode simple single-quoted repository paths safely;
- reject malformed quoting;
- preserve the sanitized raw command for diagnostics.

The parser should not invoke a shell. It should parse the SSH exec command as a
restricted command language with exactly one Git service and one repository
argument.

## Repository Path Normalization

The repository argument must be converted to an Orion repository id.

Rules:

- strip one optional leading slash only if current route compatibility requires
  it;
- strip one optional `.git` suffix only if repository naming policy supports
  that alias;
- reject `..`, empty path segments, NUL bytes, and control characters;
- reject paths that look like local filesystem escape attempts;
- preserve case according to repository metadata policy;
- normalize nested repository names consistently with HTTP smart routes.

Server-side SSH and smart HTTP should share repository normalization rules where
practical so `/r/name.git` and `git@host:name.git` resolve consistently.

## Protocol Version And Environment

Git clients may request protocol v2 over SSH by sending an environment request
for `GIT_PROTOCOL=version=2` before exec.

Server-side behavior:

- allow only recognized Git protocol environment keys;
- reject or ignore unrelated environment variables according to policy;
- parse `version=2`;
- pass requested protocol version to native upload-pack service;
- record selected version in diagnostics;
- fall back to v0/v1 only when service policy allows it.

Client-side behavior:

- send `GIT_PROTOCOL=version=2` when protocol v2 is requested;
- handle servers that reject environment requests;
- fall back only when caller policy allows it;
- record whether the environment request was accepted.

Receive-pack usually uses classic push protocol. The adapter should still carry
transport hints without assuming protocol v2 push is available.

## Server Authentication

Server-side SSH authentication should support:

- password auth where existing Orion policy allows it;
- public-key auth for users with registered OpenSSH public keys;
- future OpenSSH user certificates if a separate identity plan adds them;
- future application-token issuance through restricted SSH commands.

Authentication output:

- Orion user identity;
- authenticated principal id;
- auth method;
- public key fingerprint when applicable;
- session id;
- remote address.

The SSH adapter should not decide Git authorization. It passes the subject to the
Git access policy and protected ref policy.

## Client Authentication

Client-side SSH authentication should support:

- private key from a secret reference;
- private key from a configured file path where allowed;
- passphrase from a secret reference;
- username from the URI or explicit config;
- optional SSH agent only after policy and dependency behavior are clear.

Default behavior:

- require known-host validation;
- require explicit private key or allowed agent config for non-public remotes;
- reject password prompts in non-interactive services unless explicitly
  configured;
- never log private key paths if they are secret references;
- never log passphrases or private key contents.

JGit SSH session factories should not be used in production native paths.

## Known Hosts

Known-host validation is mandatory by default for outbound SSH.

Supported sources:

- configured known-hosts file;
- secret reference containing known-host entries;
- pinned host key fingerprint;
- test-only permissive verifier.

Rules:

- reject unknown host by default;
- reject changed host key by default;
- support hashed known-host entries if the chosen SSH library supports them;
- support multiple algorithms per host;
- include hostname, port, and algorithm in sanitized diagnostics;
- do not accept all host keys in production defaults.

The first implementation can require explicit known-host entries and defer
advanced OpenSSH matching behavior if the limitation is documented.

## Host Keys

Server-side host keys should use the key material service once available.

Rules:

- load all configured SSH host key aliases;
- serve multiple host key algorithms during rotation;
- do not regenerate host keys unexpectedly after first startup;
- record host key fingerprints at startup;
- avoid logging private key material;
- keep compatibility with existing `SshHostKeyService` until migration.

Rotation:

- add new host key alias;
- serve old and new keys for a configured overlap period;
- remove old key only after clients have had time to learn the new key;
- verify tests can connect during overlap.

## Server Authorization

After SSH authentication, Git authorization follows the same policy as other Git
transports.

Upload-pack:

- require repository read or fetch permission;
- filter ref advertisement by subject visibility;
- reject unauthorized wants without leaking hidden refs;
- pass transport id and subject into audit.

Receive-pack:

- require repository write/create permission;
- enforce ref create/update/delete/force/protected-ref policy;
- reject disallowed commands with sanitized `ng` reasons;
- preserve atomic push semantics when negotiated.

Shell or helper commands:

- keep `issue-token` and similar helpers outside Git command handling;
- require explicit allowlist and audit for every non-Git SSH command;
- never let helper commands bypass transport lifecycle or ACL startup order.

## Server Upload-Pack Integration

SSH upload-pack command flow:

1. Accept authenticated SSH exec command.
2. Parse `git-upload-pack` and repository argument.
3. Resolve repository and subject.
4. Read protocol version hints from SSH environment.
5. Call native upload-pack service with command input/output streams.
6. Stream response bytes to SSH stdout.
7. Stream progress/errors according to upload-pack side-band behavior.
8. Close channel with a meaningful exit status.
9. Emit access and upload events.

The SSH adapter should not parse wants/haves itself. Upload-pack service owns
Git request semantics.

## Server Receive-Pack Integration

SSH receive-pack command flow:

1. Accept authenticated SSH exec command.
2. Parse `git-receive-pack` and repository argument.
3. Resolve or create repository according to receive policy.
4. Call native receive-pack service with command input/output streams.
5. Stage and validate incoming pack through object publication service.
6. Stream report-status to SSH stdout.
7. Close channel with exit status that reflects protocol completion.
8. Emit receive and audit events.

The SSH adapter should not update refs directly. Receive-pack service owns ref
validation and updates.

## Client Upload-Pack Integration

Outbound upload-pack over SSH:

1. Parse remote URI.
2. Resolve auth and known-host config.
3. Open SSH session.
4. Send protocol v2 environment hint when requested.
5. Execute `git-upload-pack '<repo>'`.
6. Expose stdout/stderr/stdin streams to upload-pack client primitives.
7. Parse advertisement and fetch response through protocol client primitives.
8. Close channel and map exit status.

Use cases:

- remote single-file fetch;
- mirror fetch;
- Git-backed resource reads;
- validation against private provider remotes.

The transport must not decide which refs or objects to fetch.

## Client Receive-Pack Integration

Outbound receive-pack over SSH:

1. Parse remote URI.
2. Resolve auth and known-host config.
3. Open SSH session.
4. Execute `git-receive-pack '<repo>'`.
5. Parse advertisement through receive-pack client primitives.
6. Stream ref commands and pack bytes.
7. Parse report-status.
8. Map remote reject and exit status to typed result.

Use cases:

- remote single-file push;
- mirroring push;
- future replication.

The transport streams pack bytes and must not buffer large packs fully in memory.

## URI And Resource Handling

Supported URI forms should be explicit.

Recommended first forms:

```text
ssh://git@example.com/repo.git
git+ssh://git@example.com/repo.git
```

Later compatibility form:

```text
git@example.com:repo.git
```

Rules:

- reject unsupported schemes;
- require username when the remote requires it;
- normalize port with default 22;
- preserve repository path exactly until server path normalization;
- do not embed private key or passphrase in URI;
- resolve `git+ssh:` through resource-addressing and location-content layers
  without JGit.

## Stream And Channel Lifecycle

Server-side:

- close channels on command completion;
- propagate client disconnect as cancellation;
- enforce idle read and whole-command timeouts;
- release repository locks on disconnect;
- disable PTY for Git commands unless a client sends it and policy safely
  ignores it;
- bound stderr/progress buffering.

Client-side:

- close stdin after request body completes;
- keep reading stdout/stderr until protocol completion or failure;
- enforce channel open, auth, read, write, and operation timeouts;
- cancel channel on pack size limit failure;
- map non-zero exit status after a complete Git protocol response carefully.

Binary streams must not be decoded as text except for command and diagnostic
channels.

## SSH Server Hardening

Server defaults:

- disable port forwarding;
- disable agent forwarding;
- disable X11 forwarding;
- reject arbitrary shell commands for Git-only users;
- allow shell only for explicitly supported Orion helper shell if still needed;
- bound authentication attempts;
- bound concurrent sessions per user and globally;
- set idle timeout;
- set max packet and window settings according to library defaults and tests;
- log authentication and command outcomes without secrets.

Algorithms:

- use modern host key algorithms where available;
- disable obsolete algorithms if Apache MINA SSHD defaults permit them;
- document compatibility exceptions;
- make algorithm policy configurable only where needed.

## Error Model

Use typed errors:

- SSH transport disabled;
- bind failed;
- host key unavailable;
- unsupported SSH command;
- malformed Git command;
- invalid repository path;
- authentication failed;
- authorization denied;
- unknown remote host;
- changed remote host key;
- private key unavailable;
- private key passphrase required;
- private key rejected;
- SSH agent unavailable;
- environment request rejected;
- channel open failed;
- command execution failed;
- channel timeout;
- unexpected EOF;
- remote non-zero exit status;
- stream size limit exceeded;
- protocol error from Git service.

Diagnostics should include:

- direction: inbound or outbound;
- service: upload-pack or receive-pack;
- host and port where safe;
- repository id or sanitized path;
- authenticated subject id where safe;
- command phase;
- retryability.

Diagnostics must not include:

- private key contents;
- passphrases;
- secret-reference resolved values;
- hidden ref names;
- object contents;
- raw pack data.

## Observability

Server metrics:

- SSH sessions accepted;
- auth success/failure by method;
- Git commands by service;
- rejected commands by reason;
- upload-pack bytes in/out;
- receive-pack bytes in/out;
- command duration;
- disconnect count;
- timeout count;
- concurrent session gauge.

Client metrics:

- outbound SSH connect duration;
- auth duration;
- known-host failure count;
- command duration;
- bytes sent;
- bytes received;
- stderr bytes retained;
- remote reject count;
- timeout count.

Logs should link:

- SSH session id;
- Git operation id;
- repository id;
- authenticated subject;
- remote address;
- command outcome.

## Test Fixtures

Build test fixtures that avoid external network access by default:

- in-memory SSH server using Apache MINA SSHD;
- fake SSH client channel for server command parser tests;
- scripted upload-pack over SSH fixture;
- scripted receive-pack over SSH fixture;
- known-host success and failure fixtures;
- private-key auth success and rejection fixtures;
- malformed command fixtures;
- command disconnect fixture;
- large stream fixture with byte limits.

Git CLI compatibility tests can run against a local Orion SSH server when the
test environment allows local ports.

External provider tests should be integration-only and gated behind explicit
configuration.

## Phased Plan

Phase 1: SSH Git command parser.

- Parse upload-pack and receive-pack command forms.
- Reject shell operators, malformed quoting, empty repo paths, and unknown
  commands.
- Add repository path normalization tests.

Phase 2: Server adapter boundary.

- Introduce a native SSH Git command adapter between `SshCommandFactory` and
  Git service calls.
- Keep existing behavior behind compatibility configuration.
- Add no-JGit dependency checks for the adapter package.

Phase 3: Protocol environment handling.

- Capture `GIT_PROTOCOL=version=2` where Apache MINA exposes environment
  requests.
- Pass protocol hints to upload-pack service.
- Add tests for accepted, rejected, and malformed environment values.

Phase 4: Server upload-pack integration.

- Route SSH upload-pack streams to native upload-pack service.
- Preserve binary stdout.
- Map service errors to SSH channel status and Git protocol errors.

Phase 5: Server receive-pack integration.

- Route SSH receive-pack streams to native receive-pack service.
- Preserve report-status behavior.
- Verify repository creation policy and ref rejection behavior.

Phase 6: Server hardening and limits.

- Add command timeouts, idle timeouts, auth attempt limits, and session limits.
- Verify forwarding is disabled.
- Add disconnect cleanup tests.

Phase 7: Client-side SSH transport model.

- Define URI, credentials, known-hosts, and error value objects.
- Add redaction tests.
- Add scripted transport boundary tests without real network.

Phase 8: Client known-host validation.

- Implement strict known-host verification by default.
- Add pinned fingerprint support.
- Add test-only permissive verifier with explicit unsafe naming.

Phase 9: Client private-key auth.

- Load private keys from secret references or allowed files.
- Support passphrase secret references.
- Reject missing or interactive prompts in non-interactive mode.

Phase 10: Client upload-pack over SSH.

- Execute `git-upload-pack`.
- Send protocol v2 environment hints.
- Feed streams to upload-pack client primitives.
- Verify single-file fetch over local SSH fixture.

Phase 11: Client receive-pack over SSH.

- Execute `git-receive-pack`.
- Stream commands and pack bytes.
- Parse report-status through client primitives.
- Verify single-file push over local SSH fixture.

Phase 12: Resource-addressing integration.

- Replace JGit SSH use for `git+ssh:` reads where native fetch is sufficient.
- Keep compatibility fallback configurable.
- Add ACL/config/resource read tests.

Phase 13: Git CLI compatibility.

- Run local `git clone`, `git fetch`, and `git push` against native SSH routes
  where test environment allows.
- Keep these tests gated if ports or external processes are required.

Phase 14: Provider compatibility.

- Add optional tests for private SSH remotes on GitHub/GitLab or other providers
  behind explicit credentials and repository configuration.

## Verification

Command parser:

- accepts `git-upload-pack repo.git`;
- accepts `git-upload-pack 'repo.git'`;
- accepts `git-receive-pack repo.git`;
- rejects unknown command;
- rejects shell operator injection;
- rejects malformed quotes;
- rejects empty repository;
- rejects path traversal.

Server auth and command routing:

- password auth maps to Orion subject;
- public-key auth maps to Orion subject;
- unauthenticated session cannot run Git command;
- upload-pack command calls native upload-pack service;
- receive-pack command calls native receive-pack service;
- non-Git commands are rejected unless explicitly allowlisted.

Protocol hints:

- `GIT_PROTOCOL=version=2` reaches upload-pack service;
- malformed protocol environment is rejected or ignored according to policy;
- v0/v1 fallback behavior is explicit.

Server hardening:

- port forwarding is disabled;
- agent forwarding is disabled;
- command timeout closes channel;
- client disconnect releases resources;
- logs redact secrets and hidden refs.

Client known hosts:

- known host key succeeds;
- unknown host fails by default;
- changed host key fails;
- pinned fingerprint succeeds only for matching key;
- permissive verifier is test-only.

Client auth:

- private key auth succeeds;
- wrong key fails with typed error;
- missing passphrase secret fails with typed error;
- credentials are redacted in diagnostics.

Client Git operations:

- upload-pack over SSH can fetch refs and pack stream from scripted server;
- receive-pack over SSH can push commands and pack stream to scripted server;
- report-status remote rejection is mapped correctly;
- side-band progress does not corrupt pack data.

Resource integration:

- `git+ssh:` resource reads can use native transport without JGit where enabled;
- compatibility fallback remains available during rollout;
- production native SSH code has no JGit dependency.

## Rollout

Start by isolating SSH command parsing and server adapter boundaries without
changing runtime behavior.

Enable native server-side upload-pack over SSH behind a feature flag after native
upload-pack serving passes socket and HTTP fixtures.

Enable native server-side receive-pack over SSH only after object publication,
ref policy, and report-status behavior are stable.

Implement outbound SSH after smart HTTP(S) outbound transport is stable unless a
production private-remote use case needs SSH earlier.

Replace JGit-backed `git+ssh:` resource reads only after native outbound
upload-pack can fetch a single file with strict known-host validation.

Keep external provider compatibility tests optional and credential-gated.

## Open Questions

Should outbound SSH use Apache MINA SSHD client to match the server dependency,
or a smaller dedicated SSH client library?

Should Orion support scp-like URI syntax (`git@example.com:repo.git`) in the
first native implementation, or only `ssh://` and `git+ssh://`?

Should the SSH server keep an interactive Orion shell, or should production
Git-only deployments disable shell entirely?

How should OpenSSH user certificates map into Orion identities if that feature
is added later?

Should `issue-token` remain an SSH helper command after application tokens are
available over HTTP admin APIs?

How strict should host key algorithm policy be for compatibility with older Git
hosting providers?

Should missing repositories over SSH return a Git-style not-found error or a
sanitized authorization failure when repository existence must be hidden?

## Acceptance Criteria

Orion can route server-side SSH `git-upload-pack` and `git-receive-pack` exec
commands to native Git services without JGit.

Orion can use outbound SSH to run upload-pack and receive-pack against remote
repositories with strict known-host validation and private-key authentication.

SSH command parsing, repository path normalization, auth mapping, limits, and
disconnect behavior are explicit and tested.

Secrets, private key references, hidden refs, and object contents are sanitized
from errors and logs.

The adapter uses the shared Git wire protocol core and protocol client
primitives instead of duplicating pkt-line, side-band, or report-status parsing.

Existing SSH behavior can remain behind compatibility flags during rollout.
