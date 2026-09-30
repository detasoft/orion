# Create S3 repositories through UI and SSH

- Owner: codex, session 01a0f177-8697-7993-a635-8992bcdf75cf,
  branch `codex/s3-management-01a0f177`,
  worktree `.worktrees/s3-management-01a0f177`, resumed 2026-09-30 14:50 Europe/Amsterdam;
  next: restore identified accidental cross-worktree edits as explicitly authorized
  by the user, preserve the task correction, verify and return an updated reviewed commit.
  Rebased onto `fa760bd0fe9652e082283a0be3017353a9ec6d57`; correction remains unstaged.
  Preserve a fresh backup before restoring external edits inside this task worktree;
  do not modify main or other worktrees as part of that restoration.
  Own seven-file correction is preserved at `/tmp/s3-management-connection-use-own.patch`.
  No UI/SSH implementation changes have been transferred to main yet.

## Required result

Users can create repositories backed by a configured S3 connection through the
existing web UI and SSH command interface. Users can add and update their own
organization's S3 connections through UI, with credentials encrypted by existing
key-material services. System administrators can manage system connections.
Use separate ACL permissions for connection creation, modification, and use.
Creating a repository requires both repository CREATE and selected connection
CONNECTION_USE; reading an already bound repository needs only existing repository rights.
The permission name is CONNECTION_USE in Java, XML, tests, and examples; do not
retain USE as an alias or a second accepted spelling.

## Dependencies and scope

The integrated S3 connections/config foundation supplies scoped connections,
encrypted secrets, repository storage bindings, metadata operations, and one
shared S3Transport. Preserve that ownership and request-scoped authentication.
GitIndexApi and GitStorageApi remain explicit stubs: no push/fetch/refs/packs or
claim of a fully stateless operational Git server. Preserve local repository
creation and existing SSH proxy connection behavior. Do not add a generic
transport registry, separate credential store, or new secret-input SSH protocol.
SSH repository creation references an existing accessible connection; UI accepts
new connection credentials as write-only input. Connection deletion and broader
SSH connection management are outside this bounded result.

## Current model and design

The HTTP repository route currently invokes NativeGitRepositoryProvider directly;
SSH has a command catalog and read-only repository listing. Configuration already
has immutable scoped Connection values and S3StorageBinding references. Reuse
OrionAccessControlServiceImpl.updatePrimaryConfiguration and ConfigurationSecrets
for revision-checked durable changes. Keep one operation shared by HTTP and SSH
for repository creation, including authorization, persistence, and error mapping;
choose the narrowest existing owner/module boundary that can serve both callers.
Present new contracts before implementing them.

Extend the existing grant vocabulary and scoped deny-wins evaluation. Connection
grants must have an explicit connection resource selector; they must not satisfy
repository, administration, branch, or network permissions accidentally. Reuse
existing action grants where their semantics fit and add only missing actions.
List only connections the caller may inspect or use, without plaintext secrets.
Organization principals act in their own organization; another organization's
connections are never accessible. Organization-local grants must not grant use
of system connections or the server's default AWS credentials. System connection
selection and default-chain configuration require system administrator authority.
An administrator may bind a system connection to an organization repository;
repository readers continue to use that binding under normal repository ACLs.

Connection creation accepts explicit credentials for nonadministrators. Use
generated same-owner encrypted secret references; do not expose a facility for
borrowing arbitrary existing secret IDs. Updating credentials must not mutate a
secret shared by another consumer. Preserve unrelated connections, secrets,
repositories, grants, and configuration fields. Responses, errors, logs, and
command output must not expose secret values. Omitted secret update fields keep
existing values; do not return stored secrets to populate forms.

S3 repository creation uses canonical organization/team/repository names under
an existing configured organization and team. Persist the requested binding in
config.xml before conditional S3 metadata creation. There is no atomic transaction
across Git configuration and S3: if S3 fails, retain the binding and return a clear
retryable error. Repeating the identical request can complete metadata creation;
a conflicting existing binding is rejected. Never replace an existing local
repository implicitly, overwrite metadata, or fall back to local storage. Use
existing revision checks, not a durable pending-operation registry. Recheck
authorization against the authoritative configuration used for mutation.

Extend the existing create-repository UI with local/S3 selection, authorized
connection selection, connection creation/editing, and s3://bucket/prefix input.
Keep server-connection settings distinct from storage connections. Provide
bounded loading/error/retry states, discard stale responses after a server
switch, and clear secret input after successful submission or dialog closure.
Explain in the S3 creation flow that Git data operations are not implemented yet.
SSH uses the existing dispatcher, authorization, output and help mechanisms;
avoid duplicate creation policy or command aliases. Document exact commands and
ACL/configuration examples in existing relevant documentation.

## Implementation plan

1. Trace HTTP creation, SSH dispatch, authorization, configuration persistence,
   encrypted-secret ownership, and frontend state. Define the shared operation
   contract and connection permission vocabulary before implementation.
2. Implement scoped connection authorization and revision-checked connection
   mutation with secret encryption and safe projections.
3. Implement shared repository creation with durable binding and retry behavior;
   update the HTTP route and add SSH command integration.
4. Extend UI connection management and repository creation using those operations.
5. Update examples and tests through public behavior and real production wiring.

## Acceptance and verification

- Full make test passes; run focused tests using documented Make goals.
- Test allowed and denied connection creation/change/use, deny precedence,
  cross-organization isolation, system/default-credential restrictions, and
  absence of privilege leakage into repository or administrative actions.
- Test encrypted persistence, secret replacement isolation, safe output, stale
  revisions, and preservation of unrelated document state.
- Test local creation unchanged, S3 binding and metadata success, duplicate and
  conflicting requests, storage failure followed by identical-request recovery,
  and unauthorized requests causing no config or S3 changes.
- Exercise HTTP and SSH callers through the common production operation.
- Verify S3 creation/reopening against actual MinIO using existing fixture and
  suitable focused verify goal when Make does not expose integration selection.
- Run frontend tests/build and relevant browser behavior checks, covering failure,
  retry, loading, credential clearing, and server-switch races.
- Report actual verification boundaries and the intentionally unsupported data
  plane. Do not repair unrelated concurrent working-tree changes.
