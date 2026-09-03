# One-Time Root Recovery Enrollment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Recreate only root as a canonical recovery identity whose generated password can enroll one SSH key,
then require that key for JWT issue while immediately revoking prior root credentials and tokens.

**Architecture:** Represent a recovered root with one keyed Argon2 credential carrying a random root authentication
generation. Defer its password-to-key transition to a dedicated recovery-only SSH command, preserve the generation
on the enrolled key credentials, and bind root JWTs to it. Keep all non-root authentication and token paths
unchanged.

**Tech Stack:** Java 21, Apache Mina SSHD 2.13.2, Orion ACL XML storage, JWT RS256, Dagger, JUnit 5, AssertJ,
Maven, Make.

---

### Task 1: Specify canonical root recreation and targeted JWT revocation

**Files:**

- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/JwtAccessTokenServiceTest.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java`

**Step 1: Replace the password-preservation reset expectation**

Rewrite the existing reset lifecycle case so the source ACL contains a damaged root with old Argon2, SHA1,
OpenSSH, and JWT-signing credentials, noncanonical roles/direct grants, plus an independent user with working SSH,
password, roles, and a previously issued JWT. Include an active server identity so the test detects accidental
internal-key injection.

After `--reset-root-pass`, assert that:

- exactly one case-insensitive root exists in the primary ACL file;
- root has the canonical `ROOT` role and no direct grants;
- the canonical role and built-in grants match `ACLUtil.generateDefaultAccessControl`;
- root has one `ARGON2` credential with a nonblank recovery-generation key ID;
- old root passwords and SSH keys fail, and no internal server credential was added;
- the unrelated user, its credentials, authorization, and pre-reset JWT still work;
- a pre-reset root JWT fails;
- the same state remains after a normal restart.

Keep a second case for a missing root in a multi-file ACL and prove the same canonical recreation without moving or
rewriting unrelated users.

**Step 2: Specify the JWT generation claim**

Extend `JwtAccessTokenServiceTest` with issue/verify cases for an optional authentication-generation claim. Require
round-trip preservation, reject malformed claim encodings, and keep claim-free non-root token behavior unchanged.

Extend `OrionAccessControlServiceImplTest` to assert that root token authentication compares the verified claim to
the current root generation, while a non-root subject does not use that comparison.

**Step 3: Run focused tests to verify RED**

Run outside the sandbox:

```sh
mvn test -Pdev -T 4 -q -pl core/bootstrap -am \
  -Dtest=InternalConfigurationRepositoryLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn test -Pdev -T 4 -q -pl core/acl -am \
  -Dtest=JwtAccessTokenServiceTest,OrionAccessControlServiceImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because reset preserves the old root object and credentials, synchronization restores internal
keys, and JWTs carry only their subject and expiration.

**Step 4: Commit the failing specification**

```sh
git add core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java \
  core/acl/src/test/java/pro/deta/orion/acl/JwtAccessTokenServiceTest.java \
  core/acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java
git commit -m "Specify canonical root recovery state"
```

### Task 2: Recreate root and bind its JWTs to an authentication generation

**Files:**

- Modify: `core/acl/src/main/java/pro/deta/orion/acl/JwtAccessTokenService.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/acl/ACLUtil.java` only if a small keyed-credential factory
  overload removes duplication

**Step 1: Add optional generation to JWT issue and verification**

Accept an optional string generation when issuing. Encode it as a JSON string payload claim and return it in the
typed successful verification result. Keep the existing claim parser bounds and signature verification order.

**Step 2: Give recovered root a durable generation**

Generate a random UUID-sized value on every reset. Store it with a reserved prefix in the sole Argon2 credential's
existing `keyId`. Add small helpers that distinguish only this root recovery shape and extract the generation from
recovery or later enrolled root credentials. Do not add a schema field or a second credential.

**Step 3: Replace root rather than editing it**

For every reset, remove all case-insensitive root user entries from every ACL draft. Remove the canonical `ROOT`
role and built-in grant IDs from their current files, add fresh canonical definitions and the new recovery root to
the primary draft, serialize only changed files, save the exact snapshot, and strictly reload it before printing
the password.

Make internal server-key synchronization skip a generation-aware recovered root so it cannot reintroduce an SSH
or JWT-signing credential during reset, enrollment reload, or restart. Continue synchronization for legacy roots
and leave every non-root user untouched.

**Step 4: Enforce targeted root JWT behavior**

When issuing for a generation-aware root, refuse issue while it has the recovery credential and otherwise include
the generation. When authenticating a token for such a root, require an exact generation match. Keep legacy root
behavior until an explicit reset and keep all non-root token behavior unchanged.

**Step 5: Run focused tests to verify GREEN**

Repeat the Task 1 commands. Expected: PASS.

**Step 6: Commit the implementation**

```sh
git add core/acl core/schema/src/main/java/pro/deta/orion/schema/acl/ACLUtil.java
git commit -m "Recreate canonical root recovery state"
```

### Task 3: Add the atomic root password-to-key transition

**Files:**

- Create: `core/authorization/src/main/java/pro/deta/orion/auth/SshKeyEnrollmentAuthentication.java`
- Create: `core/authorization/src/main/java/pro/deta/orion/auth/SshKeyEnrollmentResult.java`
- Modify: `core/authorization/src/main/java/pro/deta/orion/OrionAccessControlService.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`

**Step 1: Write failing service tests**

Specify a dedicated key-enrollment authentication result that returns the authenticated identity and an optional
root recovery generation. For a recovered root, password verification must expose that generation without
granting token issue. For ordinary users, it must preserve existing password authentication with no recovery
generation.

Specify `completeRootSshKeyEnrollment(expectedGeneration, publicKeys)` as a typed result. Its success case replaces
the sole recovery credential with deduplicated canonical OpenSSH credentials carrying the same root generation.
Its nontrivial cases reject an invalid key, a stale generation after another reset, a second enrollment after
success, and any non-recovery root without changing the ACL.

**Step 2: Run the ACL test to verify RED**

Run outside the sandbox:

```sh
mvn test -Pdev -T 4 -q -pl core/acl -am \
  -Dtest=OrionAccessControlServiceImplTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the API and atomic recovery transition do not exist.

**Step 3: Implement the authorization contract and ACL transaction**

Add sealed success/failure result types following `AuthenticationResult` and `TokenIssueResult`. Authenticate the
password without returning plaintext or a hash. Under `reloadLock`, re-read the current root state, compare the
expected generation, parse/deduplicate all keys before mutation, replace credentials in one draft, save once, and
activate the updated ACL once. Preserve the generation in each enrolled credential key ID.

**Step 4: Run the focused test to verify GREEN**

Repeat the Task 3 command. Expected: PASS.

**Step 5: Commit the service transition**

```sh
git add core/authorization core/acl
git commit -m "Add atomic root key enrollment"
```

### Task 4: Restrict recovery SSH sessions to a dedicated command

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/RootSshKeyEnrollmentSession.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/PasswordKeyboardInteractiveAuthFactory.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/OrionShell.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticatorTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/OrionShellTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitSshTransportStateMachineTest.java`

**Step 1: Write failing Mina and command-gate tests**

In the real Mina loopback fixture, model a recovered root separately from ordinary password users. Assert that a
valid recovery password plus selected proved key authenticates the SSH transport but does not yet mutate ACL. The
session must hold only a pending key list and expected generation.

Assert that exact command `enroll-key` completes the ACL transition and exits successfully. `issue-token`, Git
exec, an interactive shell, or disconnect must not complete enrollment; ordinary users retain the current
password-only and same-session key-enrollment behavior. After success, the password cannot create another
recovery session and a new connection authenticates with the enrolled key.

**Step 2: Run transport tests to verify RED**

Run outside the sandbox:

```sh
mvn test -Pdev -T 4 -q -pl net/git-transport -am \
  -Dtest=OrionSshAuthenticatorTest,SshCommandFactoryTest,OrionShellTest,GitSshTransportStateMachineTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because key selection mutates ACL during authentication and installs an unrestricted root identity.

**Step 3: Track a pending recovery session**

Use the dedicated enrollment authentication API. For a root recovery result, require key selection even if the
operator must paste the key, retain the selected canonical keys and generation in a Mina session attribute, and
install an identity marked by that pending state. Do not call the ACL writer from the authenticator. Leave the
existing non-root path unchanged.

**Step 4: Gate channels and implement `enroll-key`**

Before normal command dispatch, Git setup, or interactive terminal construction, check the recovery-session
attribute. Reject every shell and exec request except exact `enroll-key`. Its command consumes neither state nor
password until the ACL service reports successful atomic enrollment; on success clear the pending session value,
write a short confirmation, and close normally. On failure return a generic command failure and leave the
recovery password usable.

Respect the `@AiRule` in `OrionShell`: do not add blocking platform-thread reads, Mina future waits, or intrinsic
locks to the terminal path.

**Step 5: Run transport tests to verify GREEN**

Repeat the Task 4 command. Expected: PASS.

**Step 6: Commit the protocol boundary**

```sh
git add net/git-transport
git commit -m "Restrict root recovery to key enrollment"
```

### Task 5: Separate interactive enrollment from public-key token issue

**Files:**

- Modify: `tests/test-support/src/test/java/pro/deta/orion/makefile/ServerMakeTargetsTest.java`
- Modify: `make/server.mk`
- Delete: `make/ssh-enrollment-askpass.sh`
- Modify: `README.md`
- Modify: `docs/plans/2026-09-03-password-authenticated-key-enrollment-design.md`
- Modify: `docs/plans/2026-09-03-password-authenticated-key-enrollment.md`

**Step 1: Write failing Make tests**

Assert that `enroll-admin-key` neither requires nor forwards `ORION_ROOT_PASSWORD`, `DISPLAY`, `SSH_ASKPASS`, or
`SSH_ASKPASS_REQUIRE`; invokes SSH with the explicit admin identity, public-key plus keyboard-interactive methods,
and exact command `enroll-key`; and leaves stdin/stdout/stderr attached to the terminal.

Assert that `issue-token` and `issue-token-raw` use `BatchMode=yes` and public-key-only authentication, so a missing
or unregistered admin key fails without prompting.

**Step 2: Run Make tests to verify RED**

Run outside the sandbox:

```sh
mvn test -Pdev -T 4 -q -pl tests/test-support -am \
  -Dtest=ServerMakeTargetsTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because enrollment currently requires the password environment variable and uses forced askpass,
while token issue permits keyboard-interactive fallback.

**Step 3: Implement the separated Make flows**

Remove the askpass script and password-variable guard. Run the dedicated enrollment command on the controlling
terminal. Keep `IdentitiesOnly=yes` and `IdentityFile=none` so only the explicit admin identity becomes a candidate.
Make both token targets public-key-only and noninteractive.

**Step 4: Update operator documentation**

Document `reset -> interactive enroll-admin-key -> issue-token`, one-time password consumption, immediate root-only
JWT revocation, and unchanged non-root users. Mark the previous same-connection arbitrary-command behavior as
superseded for recovered root only; retain it for ordinary users.

**Step 5: Run Make tests to verify GREEN**

Repeat the Task 5 command. Expected: PASS.

**Step 6: Commit commands and documentation**

```sh
git add make tests/test-support/src/test/java/pro/deta/orion/makefile/ServerMakeTargetsTest.java README.md \
  docs/plans/2026-09-03-password-authenticated-key-enrollment-design.md \
  docs/plans/2026-09-03-password-authenticated-key-enrollment.md
git commit -m "Separate root enrollment from token issue"
```

### Task 6: Prove the complete root-only recovery sequence

**Files:**

- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`

**Step 1: Write the end-to-end scenario**

Start with working root and non-root SSH/JWT credentials. Capture both JWTs, reset root, and assert old root key and
JWT failure while the non-root key and JWT still work. Assert `issue-token` cannot fall back to recovery password.
Enroll one root key using the interactive protocol and exact `enroll-key`, reconnect with that key, issue a new JWT,
and prove it authorizes. Assert a second recovery enrollment fails and repeat the key/JWT checks after restart.

**Step 2: Run the focused integration test to verify RED, then GREEN**

Run outside the sandbox after adding the scenario and before fixture changes, then after implementation adjustments:

```sh
mvn verify -Pdev -T 4 -q -pl tests/integration-test -am \
  -Dit.test=GitSshTransportEndToEndIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Expected RED: the reset retains root credentials and the enrollment session can run `issue-token`. Expected GREEN:
the full sequence passes without changing non-root behavior.

**Step 3: Commit acceptance coverage**

```sh
git add tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java
git commit -m "Verify one-time root recovery enrollment"
```

### Task 7: Verify and prepare orchestrated review

**Files:**

- Review: every changed production, test, Make, task, and documentation file

**Step 1: Inspect rules and diff hygiene**

Read `docs/reviews/RULES.md` and every changed class-level `@AiRule`. Run:

```sh
git diff --check <worker-base>..HEAD
git status --short
```

Expected: no whitespace errors and only task-owned changes.

**Step 2: Run focused verification outside the sandbox**

Repeat the focused unit, Make, and integration commands from Tasks 1, 3, 4, 5, and 6. Expected: PASS.

**Step 3: Run full development verification outside the sandbox**

```sh
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 4: Return the branch for orchestrated review**

Report task path, worktree, branch, base and head SHAs, changed files, exact verification results, and residual risks.
Do not edit the orchestrator-owned plans, squash, cherry-pick, or remove the worktree until the review loop and user
gate request those actions.
