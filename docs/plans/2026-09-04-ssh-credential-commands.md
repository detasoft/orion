# SSH Credential Commands Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `/auth/key ls`, `add`, and `rm` commands that atomically manage only the authenticated user's SSH
credentials, including explicit forced lockout and safe root-generation handling.

**Architecture:** Carry immutable SSH authentication facts from Mina into `SecurityContext`, while keeping command
handlers independent of Mina sessions and channels. Add narrow typed ACL query/mutation results, update the exact ACL
file under the existing reload lock, and use the loaded Git version as a compare-and-set precondition for native ACL
saves. Compose a dedicated credential catalog into the SSH command tree. A forced last-key removal places root in a
durable locked state that only `--reset-root-pass` can repair.

**Tech Stack:** Java 21, Apache Mina SSHD 2.13.2, Orion command core, Orion ACL snapshots/XML v2, Dagger, JUnit 5,
AssertJ, Maven.

---

### Task 1: Parse standalone boolean command flags

**Files:**

- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandLineParser.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/CommandLineParserTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/DefaultCommandDispatcherTest.java`
- Modify: `core/command/src/test/java/pro/deta/orion/command/audit/AuditingCommandDispatcherTest.java`

**Step 1: Add failing parser tests**

Specify that `/auth/key rm SHA256:abc --force` produces one positional argument and the named value
`force=true`. Reject `--`, `--=value`, repeated `--force`, and a flag after `where`. Keep quoted values and existing
`name=value` parsing unchanged.

**Step 2: Add failing validation and audit tests**

Define a test command that allows `force`. Assert that `--force` passes definition validation, an undeclared flag
returns `INVALID_ARGUMENTS`, and the audit description contains `force=true`. The flag must never be interpreted as
a positional argument.

**Step 3: Verify RED**

Run outside the sandbox:

```sh
make run-test MODULE=core/command \
  TEST='CommandLineParserTest,DefaultCommandDispatcherTest,AuditingCommandDispatcherTest'
```

Expected: FAIL because standalone long flags are currently positional tokens.

**Step 4: Implement minimal generic flag parsing**

In the pre-`where` argument loop, recognize only a token with the shape `--<nonblank-name>` and add that name to
the existing named-parameter map with value `true`. Use the same duplicate-name failure as `name=value`. Do not add
short flags, negated flags, bundled flags, or a second boolean type to the command model.

**Step 5: Verify GREEN and commit**

Repeat the focused command, then commit the parser and its tests:

```sh
git add core/command
git commit -m "Parse boolean command flags"
```

### Task 2: Define typed SSH credential contracts

**Files:**

- Create: `core/authorization/src/main/java/pro/deta/orion/auth/SshConnectionCredentials.java`
- Create: `core/authorization/src/main/java/pro/deta/orion/auth/SshCredential.java`
- Create: `core/authorization/src/main/java/pro/deta/orion/auth/SshCredentialFailureCode.java`
- Create: `core/authorization/src/main/java/pro/deta/orion/auth/SshCredentialListResult.java`
- Create: `core/authorization/src/main/java/pro/deta/orion/auth/SshCredentialUpdateResult.java`
- Modify: `core/authorization/src/main/java/pro/deta/orion/auth/SecurityContext.java`
- Modify: `core/authorization/src/main/java/pro/deta/orion/OrionAccessControlService.java`
- Modify: `core/authorization/src/test/java/pro/deta/orion/auth/SecurityContextTest.java` if present; otherwise add
  focused coverage in the nearest existing authorization test.

**Step 1: Add the immutable connection credential context**

Define `SshConnectionCredentials` with an optional authenticated-key fingerprint and a copied list of canonical
OpenSSH candidate strings. Its `toString()` must show counts/fingerprint only and must not expose candidate key
material. Provide an `empty()` factory.

Extend `SecurityContext` with a default-empty SSH connection credential value, a fluent setter, and a getter. Mark
the field excluded from Lombok's generated `toString`; test that full candidate material never appears in either
object's string form.

**Step 2: Add credential descriptors and result types**

`SshCredential` contains only algorithm and fingerprint. Define stable failure codes sufficient for handlers to
distinguish:

```text
USER_NOT_FOUND
INVALID_KEY
INVALID_STORED_KEY
MISSING_MATCH
AMBIGUOUS_MATCH
LAST_KEY_REQUIRES_FORCE
ROOT_LOCKED
CONCURRENT_UPDATE
PERSISTENCE_FAILED
```

`SshCredentialListResult` is a sealed `Success(List<SshCredential>)` / `Failure(code, reason, throwable)` result.
`SshCredentialUpdateResult` is a sealed `Success(List<SshCredential> credentials, boolean changed)` /
`Failure(code, reason, candidates, throwable)` result. Defensively copy every collection and do not accept null
success values.

**Step 3: Extend the narrow service interface**

Add methods with default typed failures so existing test doubles remain source-compatible:

```java
SshCredentialListResult listSshCredentials(String userId);
SshCredentialUpdateResult addSshCredentials(String userId, List<String> publicKeys);
SshCredentialUpdateResult removeSshCredential(String userId, String fingerprintPrefix, boolean force);
```

Keep the existing enrollment methods. Later implementation makes the legacy `addSshKeysToUser` delegate to the
same mutation path rather than duplicating writes.

**Step 4: Run authorization tests and commit**

Run outside the sandbox:

```sh
mvn test -Pdev -T 4 -q -pl core/authorization -am \
  -Dtest='*SecurityContextTest,*SshCredential*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Commit only these contracts and tests:

```sh
git add core/authorization
git commit -m "Define SSH credential management contracts"
```

### Task 3: Enforce native ACL snapshot versions

**Files:**

- Create: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/GitRepositoryConcurrentUpdateException.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java`
- Modify: `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java`
- Create: `core/acl/src/main/java/pro/deta/orion/acl/storage/AccessControlConcurrentUpdateException.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/storage/AccessControlStorage.java`
- Modify: `connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java`
- Modify: `connectors/acl-storage/src/test/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorageTest.java`

**Step 1: Write failing conditional native-save tests**

Save and load version one, write version two through the existing unconditional API, then attempt to save files with
version one's commit ID as the expected version. Assert a dedicated `GitRepositoryConcurrentUpdateException`, an
unchanged branch ref at version two, and unchanged version-two contents. Also prove a successful conditional save
uses the expected commit as its parent and preserves files not included in the update.

**Step 2: Write failing ACL-storage version tests**

Load an `AccessControlSnapshot`, advance the configured ref with different ACL and unrelated-file contents, and save
the stale snapshot. Require `AccessControlConcurrentUpdateException`; reload and verify neither file from the winning
commit was overwritten. Assert that a snapshot with an empty version still uses the existing unconditional path so
initial native ACL creation remains supported.

**Step 3: Verify RED**

Run outside the sandbox:

```sh
mvn test -Pdev -T 4 -q -pl git/git-native-storage -am \
  -Dtest=NativeGitRepositoryTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn test -Pdev -T 4 -q -pl connectors/acl-storage -am \
  -Dtest=NativeGitAccessControlStorageTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because native saves currently resolve the branch at save time and ignore
`AccessControlSnapshot.version()`.

**Step 4: Implement exact-version compare-and-set**

Add a conditional `NativeGitRepository.saveFilesIfVersion(...)` path. It must parse the expected commit ID, read and
overlay that exact commit's tree, create a child commit, and update the configured ref with the expected commit ID as
the compare-and-set old value. Map `RefUpdateResult.STALE` to the dedicated checked concurrent-update exception.
Keep the existing unconditional `saveFiles(...)` behavior and callers unchanged.

When `AccessControlSnapshot.version()` is present, `NativeGitAccessControlStorage.save(...)` must use the conditional
path and translate the native stale exception to `AccessControlConcurrentUpdateException`. When the version is empty,
retain the existing unconditional path for initial creation and versionless storage behavior. Other Git/storage
failures retain their current failure boundary.

**Step 5: Verify GREEN and commit**

Repeat the Task 3 focused tests, then:

```sh
git add git/git-native-storage core/acl connectors/acl-storage
git commit -m "Enforce native ACL snapshot versions"
```

### Task 4: Implement atomic credential listing and addition

**Files:**

- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`

**Step 1: Write failing listing tests**

Seed one user with RSA and Ed25519 credentials, duplicate encoded keys, non-SSH credentials, and a second user's
keys. Assert that listing returns one logical descriptor per encoded SSH key, preserves deterministic fingerprint
ordering, exposes neither values nor the second user's entries, and reports malformed stored SSH keys explicitly.

**Step 2: Write failing addition tests**

Assert successful multi-key addition, byte-level deduplication, canonical serialization, idempotent `changed=false`,
all-or-nothing invalid input, missing user failure, and isolation of other credentials/users. Include a split ACL
snapshot and verify that only the file containing the user changes.

For generation-aware root, assert every added key retains the existing root generation. Assert that a locked root
returns `ROOT_LOCKED` without mutation.

**Step 3: Verify RED**

Run outside the sandbox:

```sh
make run-test MODULE=core/acl TEST='OrionAccessControlServiceImplTest'
make run-test MODULE=core/bootstrap TEST='InternalConfigurationRepositoryLifecycleTest'
```

Expected: FAIL because the typed operations do not yet exist.

**Step 4: Implement snapshot-aware query and mutation helpers**

Parse stored and requested keys with `KeyUtils`, derive algorithm/fingerprint with Mina key utilities, and
deduplicate by encoded bytes. Under `reloadLock`, load the current `AccessControlSnapshot`, locate exactly one
case-insensitive user across drafts, mutate only its draft, preserve snapshot version and every other file, then
use strict save-and-reload activation.

Map expected parse, validation, lookup, save, and reload failures into the typed results. In particular, translate
`AccessControlConcurrentUpdateException` to `CONCURRENT_UPDATE`; do not use an expected storage race as command
control-flow exception beyond that storage boundary. Log no full pasted or candidate key.

Refactor `addSshKeysToUser` to delegate to `addSshCredentials`; retain its existing public behavior for successful
password-authenticated enrollment and convert a typed failure to the existing exceptional boundary only where a
legacy caller cannot consume a typed result.

**Step 5: Verify GREEN and commit**

Repeat the Task 4 focused tests, then:

```sh
git add core/acl core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java
git commit -m "Add atomic SSH credential queries and additions"
```

### Task 5: Implement removal and durable root lock

**Files:**

- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`

**Step 1: Write failing removal tests**

Cover exact and unique-prefix removal, missing and ambiguous prefixes, duplicate stored records for one logical key,
invalid stored values, preservation of non-SSH credentials, another user's identical key, and multi-file snapshot
isolation. Assert that removing a non-last key succeeds without `force`; removing the last key returns
`LAST_KEY_REQUIRES_FORCE`; repeating a completed removal returns `MISSING_MATCH`.

**Step 2: Specify current-session-independent behavior**

Service removal must not receive or act on the current-session fingerprint. It changes persisted credentials only;
the transport continues using its already built `SecurityContext`. Unit tests should make clear that `force` is
only a last-key acknowledgement, not a session-termination instruction.

**Step 3: Specify root locking**

For case-insensitive root, forced last-SSH-key removal adds one internal locked marker with a new random generation
and a never-exposed Argon2 preimage. Existing non-SSH credentials may remain serialized, but the marker makes all
root authentication fail closed.

Extend root-state helpers so a locked root:

- fails password, public-key, and bearer-token authentication;
- refuses token issue and SSH credential addition;
- is skipped by internal server-key synchronization;
- remains locked after normal restart;
- is replaced by the existing canonical recovery flow on `--reset-root-pass`.

Assert that all root JWTs issued before locking fail, including generation-bound tokens. When at least one SSH key
remains, remove only the selected key and preserve the current generation on the survivors.

**Step 4: Verify RED, implement, and verify GREEN**

Use the same Task 4 commands. Generate and clear the random marker preimage exactly like other generated secrets;
use a distinct reserved locked-generation key-ID prefix so the marker is never mistaken for the printable recovery
password shape.

**Step 5: Commit**

```sh
git add core/acl core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java
git commit -m "Add safe SSH credential removal"
```

### Task 6: Carry current-key and proved-candidate facts into commands

**Files:**

- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/OrionShell.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticatorTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/OrionShellTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java`

**Step 1: Write failing Mina/session-context tests**

For direct registered-key authentication, require the connection context to contain that key's fingerprint and all
ownership-proven candidates accumulated before success. For password authentication, the current fingerprint is
absent. Selected and unselected candidates remain immutable connection facts after successful ordinary-user
enrollment; probes without a verified signature never appear.

Assert that candidate material is isolated between connections and that root recovery's restricted session still
cannot reach ordinary commands.

**Step 2: Write failing frontend propagation tests**

Assert that both `OrionShell` and `SshCommandFactory` attach the same safe `SshConnectionCredentials` snapshot to
the `SecurityContext` used for dispatch. Audit metadata contains at most method/fingerprint labels and never full
candidate keys.

**Step 3: Verify RED**

Run outside the sandbox:

```sh
make run-test MODULE=net/git-transport \
  TEST='OrionSshAuthenticatorTest,OrionShellTest,SshCommandFactoryTest'
```

**Step 4: Implement transport snapshot propagation**

Track the authenticated key fingerprint in a private Mina session attribute. Keep proved keys in the existing
connection-local map after successful authentication and expose a defensive immutable snapshot method. Build the
typed connection context when constructing exec or interactive `SecurityContext`; never put canonical public-key
strings in `auditMetadata`.

Read and preserve the class-level `@AiRule` in `OrionShell`: add no blocking platform thread, Mina future wait, or
intrinsic terminal lock.

**Step 5: Verify GREEN and commit**

Repeat the focused command, then:

```sh
git add net/git-transport core/authorization
git commit -m "Expose SSH authentication facts to commands"
```

### Task 7: Add the `/auth/key` command catalog

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/SshCredentialCommandCatalog.java`
- Create: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/SshCredentialCommandCatalogTest.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalog.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/LegacySshCommandCatalogTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/command/Slf4jCommandAuditSinkTest.java`
- Modify: `core/command/src/main/java/pro/deta/orion/command/CommandDefinition.java` only if parameter completion
  metadata cannot be expressed through the existing contract.

**Step 1: Write failing routing and authorization tests**

Compose `/auth/key` under the existing root tree. All three actions require an authenticated named user and always
derive `userId` from `SecurityContext`; no command accepts a target-user parameter. Assert anonymous denial and
that another user's credentials never reach results or mutation calls.

**Step 2: Write failing `ls` tests**

Map typed list success to `Rows` with exactly `algorithm`, `fingerprint`, and `current`. Compare each fingerprint to
the connection context to calculate `current`; return a stable typed command failure for ACL query failure.

**Step 3: Write failing `add` tests**

Declare named parameters `candidates` and sensitive `key`. Require exactly one. Resolve `all` or comma-separated
unambiguous candidate fingerprint prefixes against the connection context before calling the ACL service. Reject
empty, duplicate, missing, or ambiguous selectors without persistence. Pass `key` as one opaque canonical input to
the ACL service and ensure audit descriptions render it as `<redacted>`.

Map idempotent add to a success message rather than a failure. Never render candidate key material in errors or
completion.

**Step 4: Write failing `rm` tests**

Accept exactly one positional fingerprint prefix and optional standalone `--force`. Map missing, ambiguous,
last-key, locked-root, and persistence failure codes deterministically. A successful removal of the current key
returns a message that it remains valid for this connection; it does not close or mutate the channel.

**Step 5: Implement and verify**

Run outside the sandbox:

```sh
make run-test MODULE=net/git-transport \
  TEST='SshCredentialCommandCatalogTest,LegacySshCommandCatalogTest,Slf4jCommandAuditSinkTest'
```

Then commit:

```sh
git add net/git-transport core/command
git commit -m "Add SSH credential commands"
```

### Task 8: Prove exec, terminal, persistence, and isolation behavior

**Files:**

- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`
- Modify: `README.md`

**Step 1: Add transport and end-to-end scenarios**

Through real Mina SSH connections, prove:

- key-authenticated `/auth/key ls` marks the authenticating key current;
- a manually pasted key and a selected proved candidate become immediately usable for new connections;
- duplicate addition is idempotent and another user's list is isolated;
- removing the authenticating key leaves the current session usable but rejects that key on reconnect;
- last-key removal without `--force` fails;
- forced last-key removal for a normal user follows its remaining password/admin recovery path;
- forced last-key removal for root locks SSH and root JWT while preserving other users and their JWTs;
- restart preserves root lock and `--reset-root-pass` restores only the canonical one-time root recovery path;
- interactive and exec dispatch use the same handlers and rendering contract.

Avoid logging or asserting full pasted key material in diagnostic output.

**Step 2: Update operator documentation**

Document command syntax, fingerprints, candidate selection, `--force`, current-session retention, ordinary-user
lockout, and reset-only recovery for a locked root.

**Step 3: Run focused acceptance verification**

Run outside the sandbox:

```sh
make run-test MODULE=net/git-transport \
  TEST='GitSshTransportStateMachineTest,SshCredentialCommandCatalogTest'
make run-test MODULE=tests/integration-test \
  TEST='GitSshTransportEndToEndIT#authenticatedUserCanManageOwnSshCredentials,'\
'GitSshTransportEndToEndIT#forcedLastRootKeyRemovalRequiresResetRecovery'
```

Expected: PASS. If exact method names differ, keep two focused scenarios and report their final names verbatim.

**Step 4: Commit acceptance coverage and docs**

```sh
git add README.md net/git-transport/src/test tests/integration-test/src/integration-test
git commit -m "Verify SSH credential management"
```

### Task 9: Verify and return for orchestrated review

**Files:**

- Review: every changed production, test, documentation, and task file

**Step 1: Inspect rules and diff hygiene**

Read `docs/reviews/RULES.md` and every changed class-level `@AiRule`. Run:

```sh
git diff --check <worker-base>..HEAD
git status --short
```

Expected: no whitespace errors and only task-owned files.

**Step 2: Repeat all focused verification**

Run every focused command from Tasks 1 through 8 outside the sandbox. Expected: PASS.

**Step 3: Run development and commit verification**

Run outside the sandbox:

```sh
mvn verify -Pdev -T 4
make test
```

Report any known environment-only integration failure separately, but do not classify a failure as unrelated
without evidence. `make test` must pass after the final implementation commit under `AGENTS.md`.

**Step 4: Return the branch for review**

Report task path, worktree, branch, exact base/head SHAs, changed-file summary, all commands/results, and residual
risks. Do not edit ordinary plan documents, squash, cherry-pick to `main`, delete the completed task node, or remove
the worktree until the review orchestrator requests final preparation.
