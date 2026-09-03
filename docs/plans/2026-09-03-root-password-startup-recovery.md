# Root Password Startup Recovery Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a one-shot `--reset-root-pass` startup option that rotates an existing root password or recreates a
missing fully privileged root user before Orion exposes any transport.

**Architecture:** Parse the flag into typed runtime options and bind those options into the application component.
The ACL service performs recovery inside its existing startup phase, persists through `AccessControlStorage`, reloads
the committed ACL, and only then prints the generated password; the runtime's existing child order keeps transports
closed throughout. Reuse the default ACL graph when a missing root must be recreated.

**Tech Stack:** Java 21, Dagger, Orion ACL XML/native Git storage, JUnit 5, AssertJ, Maven, Make.

---

### Task 1: Parse and carry the one-shot startup option

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/config/OrionRuntimeOptions.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/AppOptionsTest.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/AppOptions.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/App.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionComponent.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/OrionStartupIT.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpTestSupport.java`

**Step 1: Write failing option tests**

Add tests proving that the default, explicit `run`, `start`, and `restart` forms accept `--reset-root-pass`, expose a
true typed runtime option, and forward the flag in `applicationArguments()` for service launches. Add a duplicate flag
test expecting a clear `IllegalArgumentException`, and assert that `stop`, `status`, and `verify` reject the option.

**Step 2: Run the focused tests and verify RED**

Run:

```sh
mvn test -Pdev -T 4 -q -pl core/bootstrap -am \
  -Dtest=AppOptionsTest,OrionRuntimeModuleTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `--reset-root-pass` is unknown and no runtime-options binding exists.

**Step 3: Add typed runtime options and parsing**

Introduce this non-persistent runtime value:

```java
public record OrionRuntimeOptions(boolean resetRootPassword) {
    public static OrionRuntimeOptions defaults() {
        return new OrionRuntimeOptions(false);
    }
}
```

Add `resetRootPassword` state to `AppOptions`, parse the exact long option once, include it in root usage text, and
include it in `applicationArguments()` only when requested. Bind `OrionRuntimeOptions` on `OrionComponent.Builder`,
provide defaults in `defaultConfigurationProvider()`, and pass `options.runtimeOptions()` from `App`. Supply explicit
defaults in every test fixture that constructs `DaggerOrionComponent.Builder` directly.

**Step 4: Run the focused tests and verify GREEN**

Repeat the command from Step 2. Expected: PASS.

**Step 5: Commit the option plumbing**

```sh
git add core/schema/src/main/java/pro/deta/orion/schema/config/OrionRuntimeOptions.java \
  core/bootstrap/src/main/java/pro/deta/orion/AppOptions.java \
  core/bootstrap/src/main/java/pro/deta/orion/App.java \
  core/bootstrap/src/main/java/pro/deta/orion/component/OrionComponent.java \
  core/bootstrap/src/test/java/pro/deta/orion/AppOptionsTest.java \
  core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java \
  tests/integration-test/src/integration-test/java/pro/deta/orion/test/GitSshTransportEndToEndIT.java \
  tests/integration-test/src/integration-test/java/pro/deta/orion/test/OrionStartupIT.java \
  tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpTestSupport.java
git commit -m "Add root password recovery startup option"
```

### Task 2: Rotate the password of an existing root user

**Files:**

- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`

**Step 1: Write the failing existing-root recovery test**

Bootstrap the default ACL, retain its generated password and committed version, add an SSH public key and an unrelated
user, then restart a component with `new OrionRuntimeOptions(true)`. Assert that:

- startup reaches `RUNNING`;
- the old root password no longer authenticates;
- the newly emitted password authenticates and is stored as one `ARGON2` credential;
- the root SSH key, roles, grants, unrelated user, and repository remain;
- the ACL commit version changes exactly through the configured native storage;
- output contains one new `---ROOT PASSWORD: ` marker for the recovery start.

**Step 2: Run the lifecycle test and verify RED**

Run:

```sh
mvn test -Pdev -T 4 -q -pl core/bootstrap -am \
  -Dtest=InternalConfigurationRepositoryLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because runtime options do not yet affect ACL startup.

**Step 3: Implement password rotation in ACL startup**

Inject `OrionRuntimeOptions` into `OrionAccessControlServiceImpl`. After a valid existing ACL loads, generate the same
length secure password used for initial bootstrap. On a draft of the existing root user, retain every non-password
credential and all metadata/references, remove `ARGON2` and `SHA1`, and append one freshly salted `ARGON2` credential.
Persist with an explicit recovery commit message through `saveAccessControlAndRequestUpdate`, then print through the
existing password-output helper. Clear the generated `char[]` in `finally`.

Normal startup follows the existing load path unchanged. A missing ACL follows the existing default-creation path and
must print only its one generated password.

**Step 4: Run the lifecycle test and verify GREEN**

Repeat the command from Step 2. Expected: PASS.

**Step 5: Commit existing-root recovery**

```sh
git add core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java \
  core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java
git commit -m "Rotate the root password during ACL startup"
```

### Task 3: Recreate a missing root with full privileges

**Files:**

- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`

**Step 1: Write the failing missing-root recovery test**

Persist an ACL containing an unrelated user and intentionally restricted entries using the canonical `ROOT`,
`CONNECT`, `ALL_REPOSITORY`, and `APPLICATION_CONTROL` IDs, but no root user. Start with reset enabled and assert that
exactly one case-insensitive root user is created, the unrelated user is unchanged, and the root user references the
canonical full-privilege graph from `ACLUtil.generateDefaultAccessControl`. Authenticate with the emitted password and
verify the result remains valid after a normal restart.

Add a duplicate-case root test (`root` and `ROOT`) that verifies startup fails without persisting a recovery commit or
printing a new password.

**Step 2: Run the lifecycle test and verify RED**

Run the focused lifecycle command from Task 2. Expected: FAIL because rotation currently requires one existing root.

**Step 3: Merge the canonical root graph when the user is absent**

Build the canonical recovery root, role, and grants from `ACLUtil.generateDefaultAccessControl(newHash, ARGON2)`.
When no root exists, add its root user and replace entries with the canonical system IDs while retaining every other
ACL entry. When exactly one root exists, use Task 2's rotation behavior and do not modify its authorization graph.
Reject multiple case-insensitive root users before saving.

**Step 4: Run the lifecycle test and verify GREEN**

Repeat the focused lifecycle command. Expected: PASS.

**Step 5: Commit root recreation**

```sh
git add core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java \
  core/bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleTest.java
git commit -m "Recreate a missing privileged root user"
```

### Task 4: Expose the recovery option through the development launcher

**Files:**

- Modify: `tests/test-support/src/test/java/pro/deta/orion/makefile/ServerMakeTargetsTest.java`
- Modify: `make/server.mk`
- Modify: `core/bootstrap/pom.xml`

**Step 1: Write the failing Make target test**

Run `make run-server ORION_ARGS=--reset-root-pass` against the fake Maven executable and assert that the fake receives
the exact startup option without exposing the key-material password as a command-line argument.

**Step 2: Run the focused Make test and verify RED**

Run:

```sh
mvn test -Pdev -T 4 -q -pl tests/test-support -am \
  -Dtest=ServerMakeTargetsTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `run-server` ignores `ORION_ARGS`.

**Step 3: Forward the arguments**

Define `ORION_ARGS ?=` in `make/server.mk`, pass it as the run profile's dedicated Maven property, and configure the
exec plugin's `commandlineArgs` from that property. Keep secrets exclusively in the environment. Add a nearby usage
comment showing `make run-server ORION_ARGS=--reset-root-pass`.

**Step 4: Run the focused Make test and verify GREEN**

Repeat the command from Step 2. Expected: PASS.

**Step 5: Commit launcher support**

```sh
git add make/server.mk core/bootstrap/pom.xml \
  tests/test-support/src/test/java/pro/deta/orion/makefile/ServerMakeTargetsTest.java
git commit -m "Forward Orion startup recovery arguments"
```

### Task 5: Verify behavior and consolidate the feature

**Files:**

- Review: all files changed by Tasks 1-4

**Step 1: Run focused verification**

Run:

```sh
mvn verify -Pdev -T 4 -q -pl core/bootstrap,tests/test-support -am
```

Expected: PASS.

**Step 2: Inspect the complete diff**

Run:

```sh
git diff --check HEAD~4..HEAD
git diff --stat HEAD~4..HEAD
git status --short
```

Confirm that no secret values, unrelated files, or lines over the project limit were introduced.

**Step 3: Squash the implementation branch**

Squash the design, plan, and implementation commits into one logical commit before transferring it to `main`:

```sh
git reset --soft <branch-base>
git commit -m "Add root password startup recovery"
```

**Step 4: Cherry-pick and run the required main-branch tests**

Cherry-pick the squashed commit to `main`, run `make test` outside the sandbox, and only then remove the feature
worktree and branch. Preserve any unrelated main-worktree changes unstaged.
