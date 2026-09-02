# Interactive SSH Authentication and Enrollment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add named-user SSH public-key authentication and one-time keyboard-interactive key enrollment while
preserving key-derived Git-over-SSH identity.

**Architecture:** Keep authentication state in the ACL service and SSH protocol state in `git-transport`. Persist
only a salted hash and lifecycle status for the enrollment token under Orion's base directory. Use a custom Mina
public-key user-auth factory so an unregistered key becomes an enrollment candidate only after its signature has
been verified, without authenticating the SSH session.

**Tech Stack:** Java 21, Apache Mina SSHD 2.13.2, Orion ACL storage, Dagger, JUnit 5, AssertJ, Maven.

---

## Design choices

Apache Mina SSHD 2.13.2 calls `PublickeyAuthenticator.authenticate(...)` before it verifies the client signature.
Returning `true` for an unknown key is necessary to request the signature, but the normal `UserAuthPublicKey`
then authenticates the session after a valid signature. Three approaches were considered:

1. Use a custom `UserAuthPublicKey` and factory. It delegates Mina's parsing and signature verification, records a
   candidate only after Mina returns a verified result, and converts that result back to an authentication failure
   for unregistered keys. This is the selected approach because it supports ownership-proven offered keys without
   granting access.
2. Allow only manually pasted public keys. This is smaller and safe but does not meet the candidate-selection
   requirement.
3. Authenticate candidate keys and restrict their channels until enrollment. This creates an unnecessary
   partially-authenticated state and is rejected because a missed channel gate would become an authorization flaw.

The keyboard-interactive exchange uses two prompts in one RFC 4256 round: the one-time token and either `all`, a
comma-separated list of displayed candidate numbers, or one pasted OpenSSH public key. Successful enrollment
persists all selected keys atomically, consumes the token, and disconnects the client so the next connection must
prove one of the stored keys.

### Task 1: Add an explicit regeneration launch option

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/config/OrionRuntimeOptions.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/AppOptions.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/App.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionComponent.java`
- Modify: every test component builder under `core/bootstrap/src/test` and `tests/integration-test`
- Test: `core/bootstrap/src/test/java/pro/deta/orion/AppOptionsTest.java`
- Test: `core/bootstrap/src/test/java/pro/deta/orion/AppTest.java`

1. Add failing parser tests for `restart --regenerate-ssh-enrollment-token`, propagation to the child `run`
   command, direct `run`, rejection on `start`, and the usage text.
2. Run `mvn test -Pdev -T 4 -q -pl core/bootstrap -am -Dtest=AppOptionsTest,AppTest,OrionServiceManagerTest`
   with `-Dsurefire.failIfNoSpecifiedTests=false`; expect the new assertions to fail.
3. Add immutable runtime options as a Dagger bound instance and propagate the one-shot flag without storing it in
   YAML/TOML configuration.
4. Run the focused tests again; expect them to pass.
5. Commit the launch-option slice.

### Task 2: Persist and consume a one-time enrollment token

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/SshEnrollmentTokenStore.java`
- Test: `net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/SshEnrollmentTokenStoreTest.java`

1. Add failing tests for first-start generation/output, normal restart reuse without output, successful one-time
   consumption, explicit regeneration invalidating the old token, corrupt state, and owner-only POSIX permissions.
2. Run the focused `git-transport` test and confirm the class is missing.
3. Implement a synchronized store at `<baseDir>/ssh-enrollment-token.properties`. Generate 32 random bytes, expose
   the URL-safe token once through the supplied startup `PrintStream`, and persist version, status, random salt, and
   SHA-256 hash through an owner-only temporary file plus atomic replacement.
4. Compare token hashes in constant time. Keep an active token across ordinary restarts, persist `consumed` after
   successful enrollment, and create a fresh active token only when the state is absent or runtime regeneration is
   explicitly requested.
5. Run the focused test and commit the token-store slice.

### Task 3: Add SSH-specific ACL operations

**Files:**

- Modify: `core/authorization/src/main/java/pro/deta/orion/OrionAccessControlService.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`

1. Add failing lifecycle tests that atomically add multiple OpenSSH public keys, ignore duplicate key material,
   authenticate each key, resolve `git` identity by key rather than SSH username, reject ambiguous key ownership,
   reject unknown users, and retain enrolled keys after restart.
2. Run the focused bootstrap lifecycle test and confirm the missing API/behavior.
3. Add `userExists`, named-user SSH-key authentication, key-derived Git SSH authentication, and batch key addition
   to the authorization contract. Keep matching based on decoded public-key bytes so PEM and OpenSSH storage forms
   remain compatible and key algorithms are not represented by a closed enum.
4. Serialize key mutations under the existing ACL reload lock, canonicalize valid OpenSSH keys, deduplicate by key
   bytes, and save/reload once per batch.
5. Run the focused tests and commit the ACL slice.

### Task 4: Implement proof-aware SSH authentication and enrollment

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/EnrollmentAwarePublicKeyAuthFactory.java`
- Delete: `net/git-transport/src/main/java/pro/deta/orion/transport/git/OrionSSHPasswordAuthenticator.java`
- Test: `net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticatorTest.java`

1. Add failing tests around a real loopback Mina client/server for named-user key login, `git` key-derived login,
   unproved probes, one and multiple proved candidates, pasted OpenSSH keys, invalid/reused tokens, unknown users,
   enrollment disconnect, and successful key-authenticated reconnect.
2. Run the focused test and confirm the new classes are missing.
3. Implement the combined public-key and keyboard-interactive authenticator. Store only per-session pending attempts
   and proven candidates, keyed by fingerprint with insertion order and no private material.
4. Implement the custom public-key auth class: registered keys produce an authenticated `UserIdentity`; unknown
   keys for an existing named user may request signature verification, but a verified candidate is recorded and
   returned as failed authentication.
5. Render numbered algorithm/fingerprint candidates in the keyboard-interactive instruction, parse `all`, unique
   numeric selections, or one manually pasted key, then persist, consume, and disconnect on success. Never log or
   persist the plaintext token.
6. Run the focused tests and commit the authenticator slice.

### Task 5: Wire the shared endpoint and preserve Git behavior

**Files:**

- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`

1. Add failing service/E2E tests proving password authentication is not exposed, named users authenticate by key,
   `git@orion` maps a stored key to its ACL user, existing upload/receive operations remain authorized, enrollment
   survives an Orion restart, and multiple offered keys remain isolated to one connection.
2. Configure exactly the custom public-key and standard keyboard-interactive factories, remove the unsafe public-key
   result cache for candidate keys, disable SSH password authentication, and start the token store before binding.
3. Keep `SSH_AUTHENTICATED_USER` as the identity consumed by command authorization and preserve the existing Git
   wire command implementation.
4. Run focused module tests:

   `mvn test -Pdev -T 4 -q -pl net/git-transport,core/bootstrap -am \
   -Dsurefire.failIfNoSpecifiedTests=false`

5. Run `mvn verify -Pdev -T 4`, inspect `git diff --check`, and review all changed `@AiRule` classes against their
   class-level rules.
6. Complete the task-tree/worktree workflow: squash task commits, delete the completed leaf and its parent link,
   cherry-pick the single task commit to `main`, run `make test` on `main`, and remove the worktree and task branch.
