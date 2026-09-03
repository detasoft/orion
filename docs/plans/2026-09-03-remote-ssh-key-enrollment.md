# Remote SSH Key Enrollment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enroll a purpose-selected Orion SSH client key idempotently with one wipeable bootstrap-password attempt and verify it in a fresh public-key session.

**Architecture:** Extend the existing isolated Apache MINA operation with explicit public-key-only and password-only authentication modes. Add an `SSH_CLIENT` key-material capability, direct-buffer password ownership, and a typed `SshKeyEnroller` that sends a canonical public key to one fixed POSIX enrollment command before fresh key verification.

**Tech Stack:** Java 21, Apache MINA sshd 2.13.2, PKCS12-backed Orion key material, JUnit 5, AssertJ, Maven/Make.

---

### Task 1: Add purpose-specific SSH client key material

**Files:**
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialPurpose.java`
- Create: `core/key-material/src/main/java/pro/deta/orion/keymaterial/SshClientKeyCapability.java`
- Modify: `core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialCapabilities.java`
- Modify: `core/key-material/src/test/java/pro/deta/orion/keymaterial/KeyMaterialCapabilitiesTest.java`

**Step 1: Write the failing reload and capability tests**

Add tests that generate and save an `SSH_CLIENT` descriptor, reload the store,
obtain `sshClientKey(descriptor)`, and compare the returned public/private key
encodings. Add focused assertions for an exact descriptor with changed purpose,
scope, or algorithm and for passing a registered non-client descriptor.

The intended API is:

```java
public interface SshClientKeyCapability {
    KeyMaterialDescriptor descriptor();
    KeyPair keyPair() throws GeneralSecurityException;
}
```

**Step 2: Run the test and record RED**

Run:

```bash
make run-test MODULE=core/key-material \
  TEST='pro.deta.orion.keymaterial.KeyMaterialCapabilitiesTest#reloadsSshClientKeyMaterialAndRejectsWrongDescriptorMetadata'
```

Expected: FAIL to compile because `SSH_CLIENT`, `SshClientKeyCapability`, and
`sshClientKey` do not exist.

**Step 3: Add the minimal capability implementation**

Add `SSH_CLIENT("ssh-client")`, create the interface, and implement:

```java
public SshClientKeyCapability sshClientKey(KeyMaterialDescriptor descriptor) {
    KeyMaterialDescriptor registered = requireRegistered(
            descriptor, KeyMaterialPurpose.SSH_CLIENT);
    return new SshClientKeyCapability() {
        public KeyMaterialDescriptor descriptor() {
            return registered;
        }

        public KeyPair keyPair() throws GeneralSecurityException {
            KeyPair selected = owner.getKeyPair(registered.alias().value());
            return new KeyPair(selected.getPublic(), selected.getPrivate());
        }
    };
}
```

Rely on the existing descriptor metadata validation for scope and algorithm.
Do not add schema or runtime wiring.

**Step 4: Run GREEN**

Run:

```bash
make run-test MODULE=core/key-material TEST='pro.deta.orion.keymaterial.KeyMaterialCapabilitiesTest'
```

Expected: PASS.

**Step 5: Commit and run the required post-commit suite**

```bash
git add core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialPurpose.java \
  core/key-material/src/main/java/pro/deta/orion/keymaterial/SshClientKeyCapability.java \
  core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialCapabilities.java \
  core/key-material/src/test/java/pro/deta/orion/keymaterial/KeyMaterialCapabilitiesTest.java
git commit -m "Add SSH client key material capability"
make test
```

### Task 2: Own and clear the bootstrap password

**Files:**
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/BootstrapPassword.java`
- Create: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/BootstrapPasswordTest.java`

**Step 1: Write failing ownership and clearing tests**

Test `copyAndClear(char[])` and `copyAndClear(byte[])`. Assert caller arrays are
zero immediately, retained storage is direct, the password can be consumed once,
mutable decode arrays are cleared, and storage reads as all-zero after success,
consumer failure, or explicit/idempotent close. Assert `toString()` contains no
secret.

The intended package API is:

```java
public final class BootstrapPassword implements AutoCloseable {
    public static BootstrapPassword copyAndClear(char[] password);
    public static BootstrapPassword copyAndClear(byte[] utf8Password);
    <T> T useOnce(PasswordConsumer<T> consumer) throws Exception;
    @TestOnly boolean isDirect();
    @TestOnly boolean isCleared();
    public void close();
}
```

**Step 2: Run the test and record RED**

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.BootstrapPasswordTest'
```

Expected: FAIL to compile because `BootstrapPassword` does not exist.

**Step 3: Implement direct-buffer ownership**

Encode characters with a `CharsetEncoder` without a long-lived `String`, copy
into `ByteBuffer.allocateDirect`, and clear every caller and temporary mutable
array in `finally`. `useOnce` creates the unavoidable transient MINA-compatible
`String` only around the consumer call and wipes direct storage in `finally`.
Reject empty, malformed UTF-8, closed, and repeated consumption without
including data in errors. Mark test-only inspection methods with `@TestOnly`.

**Step 4: Run GREEN**

Run the Task 2 command again. Expected: PASS.

**Step 5: Commit and verify**

```bash
git add agent-provisioning/src/main/java/pro/deta/orion/provisioning/BootstrapPassword.java \
  agent-provisioning/src/test/java/pro/deta/orion/provisioning/BootstrapPasswordTest.java
git commit -m "Add wipeable bootstrap password ownership"
make test
```

### Task 3: Add one isolated password-only SSH authentication mode

**Files:**
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/MinaSshOperation.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/MinaSshOperationTest.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/TestSshServer.java`

**Step 1: Write failing live authentication-isolation tests**

Extend `TestSshServer` with a password authenticator and authentication/session
recording. Test a password-only open that succeeds only with the supplied password,
uses only `UserAuthPasswordFactory`, retains the empty host resolver and key
provider, and records exactly one password attempt. Test wrong password and exact
host-key mismatch classifications.

**Step 2: Run and record RED**

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.MinaSshOperationTest#authenticatesWithOnlyTheSuppliedBootstrapPassword'
```

Expected: FAIL to compile because the password-only open boundary is absent.

**Step 3: Generalize the existing connection setup minimally**

Keep public-key `open` behavior unchanged. Add a package-private password-only
factory used through `BootstrapPassword.useOnce`; configure only
`UserAuthPasswordFactory.INSTANCE`, `PasswordIdentityProvider.wrapPasswords`,
`HostConfigEntryResolver.EMPTY`, and `KeyIdentityProvider.EMPTY_KEYS_PROVIDER`.
Share exact host verification, native deadlines, and the single existing shared
watchdog. Ensure failed and successful sessions drop the provider on close.

**Step 4: Run GREEN and regression tests**

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.MinaSshOperationTest'
```

Expected: PASS, including existing timeout and identity-isolation tests.

**Step 5: Commit and verify**

```bash
git add agent-provisioning/src/main/java/pro/deta/orion/provisioning/MinaSshOperation.java \
  agent-provisioning/src/test/java/pro/deta/orion/provisioning/MinaSshOperationTest.java \
  agent-provisioning/src/test/java/pro/deta/orion/provisioning/TestSshServer.java
git commit -m "Add isolated SSH password authentication"
make test
```

### Task 4: Enroll and verify the selected public key

**Files:**
- Modify: `agent-provisioning/pom.xml`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/EnrollmentFailure.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshKeyEnrollmentException.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshKeyEnroller.java`
- Create: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/SshKeyEnrollerTest.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/TestSshServer.java`

**Step 1: Write the failing password-enrollment live test**

Add the `key-material` module dependency. In the in-process MINA server, make
public-key authentication consult its temporary `authorized_keys` state. Test an
initial rejected public-key session, one distinct password session executing the
enrollment command, and a third distinct public-key verification session. Assert
canonical Apache MINA OpenSSH formatting, secure modes for newly created paths,
and cleared password storage.

**Step 2: Run and record RED**

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#enrollsWithPasswordThenVerifiesInAFreshKeySession'
```

Expected: FAIL to compile because the enroller and typed result do not exist.

**Step 3: Implement the minimal success flow**

Implement `SshKeyEnroller.enroll(endpoint, capability, Optional<BootstrapPassword>,
options)`. Format with `PublicKeyEntry.toString(keyPair.getPublic())`. First try a
fresh public-key open. Fall back only from `ProvisioningFailure.AUTHENTICATION`.
Execute one constant POSIX command with the key line on stdin. Close the password
session before fresh public-key verification. Map verification authentication to
`EnrollmentFailure.VERIFICATION` and close the optional password in an outer
`finally`.

The POSIX command must:

```sh
set -eu
umask 077
IFS= read -r key
test -n "$key"
test ! -L "$HOME/.ssh"
test ! -e "$HOME/.ssh" || test -d "$HOME/.ssh"
test -d "$HOME/.ssh" || mkdir -m 700 "$HOME/.ssh"
test ! -L "$HOME/.ssh/authorized_keys"
test ! -e "$HOME/.ssh/authorized_keys" || test -f "$HOME/.ssh/authorized_keys"
```

Then validate the canonical two-field key, scan records for the exact key blob,
and append without replacing existing bytes or chmodding existing objects.
Assign explicit exit codes for input, directory state, file state, and write
failure; map them without exposing raw stderr.

**Step 4: Run the success test GREEN**

Run the Task 4 command again. Expected: PASS.

**Step 5: Add RED/GREEN tests for idempotency and existing-key path**

Add tests that preserve comments, unrelated keys, order, and existing permissions;
run enrollment twice without duplicates; and authenticate an existing key without
any password attempt. Before implementing each missing behavior, run its focused
method and record the expected assertion failure, then implement the smallest
change and rerun to PASS.

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#repeatEnrollmentPreservesContentPermissionsAndDoesNotDuplicate'
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#existingKeyNeedsNoPasswordAuthentication'
```

**Step 6: Commit and verify**

```bash
git add agent-provisioning/pom.xml \
  agent-provisioning/src/main/java/pro/deta/orion/provisioning/EnrollmentFailure.java \
  agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshKeyEnrollmentException.java \
  agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshKeyEnroller.java \
  agent-provisioning/src/test/java/pro/deta/orion/provisioning/SshKeyEnrollerTest.java \
  agent-provisioning/src/test/java/pro/deta/orion/provisioning/TestSshServer.java
git commit -m "Enroll and verify remote SSH client keys"
make test
```

### Task 5: Complete typed failure and secret non-disclosure coverage

**Files:**
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshKeyEnroller.java`
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/EnrollmentFailure.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/SshKeyEnrollerTest.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/TestSshServer.java`

**Step 1: Add focused failing edge-case tests**

Add one test at a time for: wrong password gives one attempt/no command/no mutation;
host mismatch causes no password fallback; absent password is typed; unsafe `.ssh`
or `authorized_keys` symbolic-link/type state is typed and unchanged; write failure
is typed; post-append verification failure is typed and leaves the key; and the
password is absent from commands, recorded stdin/files, results, exception/cause
text, captured logs, and all `toString` values.

**Step 2: Record each meaningful RED**

Run individual methods with:

```bash
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#wrongPasswordAttemptsOnceWithoutMutationAndClearsSecret'
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#hostKeyMismatchNeverFallsBackToPassword'
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#rejectsUnsafeRemoteStateWithoutReplacingIt'
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#reportsWriteFailureWithoutDisclosingPassword'
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest#failedFreshKeyVerificationLeavesAppendedKeyForRetry'
```

Expected for each first run: FAIL on the newly asserted missing classification or
security behavior, never from a test typo or fixture error.

**Step 3: Implement each smallest mapping or guard and rerun GREEN**

Preserve `ProvisioningFailure.CONNECTION`, `HOST_IDENTITY`, and `TIMEOUT` without
fallback. Convert only expected enrollment outcomes to the narrow public typed
failure enum. Do not include raw remote output or password-bearing MINA messages.
Keep the shared watchdog; add no per-I/O threads.

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.SshKeyEnrollerTest'
```

Expected: PASS.

**Step 4: Run focused module regressions and formatting checks**

```bash
make run-test MODULE=core/key-material TEST='pro.deta.orion.keymaterial.KeyMaterialCapabilitiesTest'
make run-test MODULE=agent-provisioning \
  TEST='pro.deta.orion.provisioning.BootstrapPasswordTest,pro.deta.orion.provisioning.MinaSshOperationTest,pro.deta.orion.provisioning.SshKeyEnrollerTest'
git diff --check
```

Expected: PASS and no whitespace errors.

**Step 5: Commit and run required verification**

```bash
git add agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshKeyEnroller.java \
  agent-provisioning/src/main/java/pro/deta/orion/provisioning/EnrollmentFailure.java \
  agent-provisioning/src/test/java/pro/deta/orion/provisioning/SshKeyEnrollerTest.java \
  agent-provisioning/src/test/java/pro/deta/orion/provisioning/TestSshServer.java
git commit -m "Harden SSH key enrollment failure handling"
make test
mvn verify -Pdev -T 4
```

Expected: both full-project commands finish with `BUILD SUCCESS`.

### Task 6: Review the unsquashed implementation and prepare the gate handoff

**Files:**
- Review only: all changes from the real base SHA through branch `HEAD`

**Step 1: Inspect requirements and repository rules**

Re-read the design, this plan, `AGENTS.md`, `docs/reviews/RULES.md`, and every
`@AiRule` on touched classes. Verify each security and test requirement against the
actual diff and recorded RED/GREEN evidence.

**Step 2: Inspect branch state**

```bash
git diff --check
git status --short
git log --oneline 44b31cd4d16bfcc32c88ce9aded1f531634ae350..HEAD
git diff --stat 44b31cd4d16bfcc32c88ce9aded1f531634ae350..HEAD
```

Expected: clean worktree, no whitespace errors, and only task-owned commits/files.

**Step 3: Request primary-agent review**

Report the real base/head SHAs, commit list, change summary, exact RED/GREEN and
verification results, risks, and clean status. Do not squash, delete the leaf,
cherry-pick to `main`, remove the branch/worktree, or begin another task until the
explicit integration gate is received.
