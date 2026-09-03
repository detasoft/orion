# Password-Authenticated SSH Key Enrollment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Authenticate named-user SSH enrollment with the user's Orion password, conditionally select proven keys
in a second keyboard-interactive round, and continue the requested command on the same connection.

**Architecture:** Keep proof-aware candidate collection in the custom public-key user-auth implementation and add
a custom Mina keyboard-interactive state machine that controls password and optional selection rounds. Reuse ACL
password authentication and atomic batch key persistence, while deleting the independent enrollment-token state
and runtime plumbing.

**Tech Stack:** Java 21, Apache Mina SSHD 2.13.2, Orion ACL XML storage, Dagger, JUnit 5, AssertJ, Maven, Make.

---

### Task 1: Specify the conditional Mina keyboard-interactive protocol

**Files:**

- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticatorTest.java`

**Step 1: Replace token-oriented loopback fixtures with password fixtures**

Give the recording ACL fake a configured password and make `authenticateUser` compare UTF-8 bytes, record the
authenticated username, and return the matching identity. Replace the two-value token/selection interaction with a
round-aware `UserInteraction` that records challenge names, instructions, prompts, and echo flags.

**Step 2: Write the failing password-first regression test**

Offer a valid unknown key for `alice`, answer the first callback with an invalid password, and assert that the
callback contains exactly one hidden `Orion password: ` prompt. Assert that the instruction contains no key type,
fingerprint, key list, or selection prompt and that no key was persisted.

**Step 3: Write the failing conditional two-round tests**

Cover these real loopback cases:

- a valid password plus proved candidates causes a second callback listing each deduplicated algorithm and
  fingerprint, selection `all` persists the keys, and the same `ClientSession.auth()` succeeds;
- a valid password with no candidates authenticates in one callback and installs the named identity;
- numeric and pasted-key selection work only after password success;
- malformed selection, unknown user, an unsigned probe, and candidates from another connection fail safely;
- registered named-user and `git` public keys still authenticate without an interactive callback.

**Step 4: Run the focused test to verify RED**

Run:

```sh
make run-test MODULE=net/git-transport TEST='OrionSshAuthenticatorTest'
```

Expected: FAIL because the current single challenge prints candidates before asking for the enrollment token,
requires two responses at once, disconnects after persistence, and cannot continue the same authentication attempt.

**Step 5: Commit the executable specification**

```sh
git add net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticatorTest.java
git commit -m "Specify password-first SSH key enrollment"
```

### Task 2: Implement the password-first two-round user-auth state machine

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/PasswordKeyboardInteractiveAuthFactory.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java`

**Step 1: Add typed authenticator operations**

Keep public-key authentication and connection-local candidate attributes in `OrionSshAuthenticator`. Replace the
stock `KeyboardInteractiveAuthenticator` callbacks with package-private operations that:

- allow interaction only for existing named users other than `git`;
- build a password-only challenge with no candidate-dependent text;
- encode the one password response as UTF-8, call `authenticateUser`, and clear the byte array in `finally`;
- snapshot proven candidates only after password success;
- build the selection challenge from deduplicated candidate algorithms and fingerprints;
- parse `all`, unique numeric selections, or a pasted OpenSSH public key;
- atomically persist the selected batch and attach the authenticated `UserIdentity` to
  `GitSshTransportService.SSH_AUTHENTICATED_USER` only on complete success.

Do not store or log password strings. Clear mutable response lists and byte arrays where their ownership permits.

**Step 2: Add the custom Mina factory and state machine**

Subclass Mina's server keyboard-interactive user auth, parse RFC 4256 response packets with the same response-count
bound used by Mina 2.13.2, and track `PASSWORD` versus `KEY_SELECTION` phase on the user-auth instance. Return
`null` after sending the second `SSH_MSG_USERAUTH_INFO_REQUEST`; return `true` only after the optional enrollment
completes. Invalid password or selection returns `false`, never a successful intermediate state.

**Step 3: Wire only the custom authentication factories**

Configure the SSH server with `EnrollmentAwarePublicKeyAuthFactory` and
`PasswordKeyboardInteractiveAuthFactory`. Retain the proof-aware public-key authenticator and disabled password
authenticator. Remove the stock server `UserAuthKeyboardInteractiveFactory` and the keyboard-interactive
authenticator callback registration.

**Step 4: Run the focused test to verify GREEN**

Run:

```sh
make run-test MODULE=net/git-transport TEST='OrionSshAuthenticatorTest'
```

Expected: PASS, including a successful same-session two-round exchange and password-first failure regression.

**Step 5: Commit the protocol implementation**

```sh
git add net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/PasswordKeyboardInteractiveAuthFactory.java \
  net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java \
  net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java
git commit -m "Authenticate SSH key enrollment with Orion passwords"
```

### Task 3: Remove enrollment-token lifecycle and runtime plumbing

**Files:**

- Delete: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/SshEnrollmentTokenStore.java`
- Delete: `net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/SshEnrollmentTokenStoreTest.java`
- Delete: `core/schema/src/main/java/pro/deta/orion/schema/config/OrionRuntimeOptions.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/AppOptions.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/App.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionComponent.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/AppOptionsTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/AppTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/OrionStartupIT.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpTestSupport.java`

**Step 1: Remove the obsolete mechanism**

Delete token-store construction/startup and injection, the `--regenerate-ssh-enrollment-token` option and forwarding,
the one-field runtime options object and Dagger binding, plus builder arguments that existed only for that binding.
Leave `issue-token`, `authenticateUserAndIssueToken`, and application-token tests unchanged.

**Step 2: Remove legacy token-specific tests in this separate cleanup commit**

Delete token-store tests, option-regeneration tests, startup-state assertions, startup-token parsing, consumed-token
cases, and reconnect-after-enrollment expectations. Do not replace them with negative tests whose only purpose is
asserting that the old mechanism is absent; the password-flow tests are the positive replacement specification.

**Step 3: Run focused compilation and tests**

Run:

```sh
make run-test MODULE=core/bootstrap TEST='AppOptionsTest,AppTest,OrionRuntimeModuleTest,InternalConfigurationRepositoryLifecycleTest'
make run-test MODULE=net/git-transport TEST='GitSshTransportStateMachineTest,OrionSshAuthenticatorTest'
```

Expected: PASS with no remaining production or test dependency on `OrionRuntimeOptions` or
`SshEnrollmentTokenStore`.

**Step 4: Commit the removal**

```sh
git add core/schema core/bootstrap net/git-transport tests/integration-test
git commit -m "Remove one-time SSH enrollment tokens"
```

### Task 4: Prove current-command continuation and real ACL persistence

**Files:**

- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`

**Step 1: Write a failing service continuation test**

Start the real `GitSshTransportService`, offer an unknown owned key, complete password and selection callbacks,
then execute the originally intended `state` command on the same authenticated client session. Assert command
output and exit status without reconnecting.

**Step 2: Write or adapt the end-to-end enrollment test**

Use the real generated root password and ACL service, complete password-authenticated enrollment over Mina, execute
the admin command on that session, restart Orion, and prove the enrolled key authenticates afterward. Preserve the
existing Git upload/receive end-to-end assertions and `issue-token` behavior.

**Step 3: Run RED before adjusting fixtures, then GREEN**

Run after the new assertions and before fixture changes:

```sh
make run-test MODULE=net/git-transport TEST='GitSshTransportStateMachineTest'
make run-test MODULE=tests/integration-test TEST='GitSshTransportEndToEndIT'
```

Expected RED: current fixtures still expect token/disconnect behavior. After updating only the required fixtures,
run both commands again and expect PASS.

**Step 4: Commit integration coverage**

```sh
git add net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java \
  tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java
git commit -m "Verify same-session SSH key enrollment"
```

### Task 5: Update local enrollment support and operator documentation

**Files:**

- Modify: `tests/test-support/src/test/java/pro/deta/orion/makefile/ServerMakeTargetsTest.java`
- Modify: `make/server.mk`
- Delete: `make/ssh-enrollment-askpass.sh`
- Create: `make/ssh-password-askpass.sh`
- Modify: `README.md`
- Modify: `docs/plans/2026-09-02-interactive-ssh-shell.md`
- Modify: `docs/plans/2026-09-02-interactive-ssh-authentication.md`

**Step 1: Write failing Make helper tests**

Require `ORION_ROOT_PASSWORD`, verify it reaches only the hidden password askpass callback, verify the selection
callback returns `all`, and record SSH arguments containing `IdentitiesOnly=yes`, `IdentityFile=none`, and exactly
the explicit admin identity. Verify a missing root password fails before SSH runs.

**Step 2: Run the Make tests to verify RED**

Run:

```sh
make run-test MODULE=tests/test-support TEST='ServerMakeTargetsTest'
```

Expected: FAIL because the helper still requires `ORION_SSH_ENROLLMENT_TOKEN` and lacks `IdentityFile=none`.

**Step 3: Implement the helper update**

Add `IdentityFile=none` to shared SSH options. Require `ORION_ROOT_PASSWORD` for `enroll-admin-key`. Rename the
askpass helper and make it return the password for `Orion password:` and `all` for the key-selection prompt. Keep
the password in the environment; never interpolate it into the command line or Make output.

**Step 4: Update current documentation**

Document the root-password enrollment command, password-first same-connection behavior, and no-token startup.
Mark the 2026-09-02 authentication plan as superseded by the new design for enrollment semantics while retaining it
as historical implementation context. Remove instructions requiring `ORION_SSH_ENROLLMENT_TOKEN`.

**Step 5: Run the Make tests to verify GREEN**

Run:

```sh
make run-test MODULE=tests/test-support TEST='ServerMakeTargetsTest'
```

Expected: PASS.

**Step 6: Commit helper and documentation changes**

```sh
git add make README.md docs/plans/2026-09-02-interactive-ssh-shell.md \
  docs/plans/2026-09-02-interactive-ssh-authentication.md \
  tests/test-support/src/test/java/pro/deta/orion/makefile/ServerMakeTargetsTest.java
git commit -m "Use the Orion root password for SSH key enrollment"
```

### Task 6: Verify the complete change for review

**Files:**

- Review: every changed production, test, Make, and documentation file

**Step 1: Search for stale token mechanisms and inspect sensitive handling**

Run:

```sh
grep -R -n --exclude-dir=target -E 'SshEnrollmentTokenStore|OrionRuntimeOptions|ORION_SSH_ENROLLMENT_TOKEN|regenerate-ssh-enrollment-token' .
git diff --check 03376196ccfea6b4e5d08e115fad0b0fee2a9563..HEAD
```

Expected: no live references outside explicitly superseded historical prose, and no whitespace errors. Review all
password paths for logging, persistence, retained buffers, and generic failure messages. Re-read every changed
class-level `@AiRule` and confirm it still holds.

**Step 2: Run focused behavioral verification outside the sandbox**

```sh
make run-test MODULE=net/git-transport TEST='OrionSshAuthenticatorTest,GitSshTransportStateMachineTest'
make run-test MODULE=tests/test-support TEST='ServerMakeTargetsTest'
make run-test MODULE=tests/integration-test TEST='GitSshTransportEndToEndIT'
```

Expected: PASS.

**Step 3: Run full development verification outside the sandbox**

```sh
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 4: Record review handoff state**

Report the exact base and head SHAs, branch and worktree, changed files, each command result, and residual risks.
Leave the task node, worktree, and branch in place for orchestrated review; do not squash, cherry-pick, or clean up.
