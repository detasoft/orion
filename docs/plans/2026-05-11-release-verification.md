# Release Verification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a standard GPG-based verification path for Orion self-executable jar releases.

**Architecture:** Keep detached GPG signatures as the independent source of truth. The POSIX launcher remains a thin process manager and dispatches `verify` into Java. Java owns argument parsing, key download, fingerprint checks, temporary GPG home setup, and invocation of external `gpg`. Maven creates a checksum for the executable artifact, while private-key signing remains a release step outside normal local builds.

**Tech Stack:** Java 21, GPG, POSIX shell, Maven Antrun, JUnit 5.

---

### Task 1: Add Java CLI Coverage

**Files:**
- Add: `core/bootstrap/src/test/java/pro/deta/orion/bootstrap/OrionCliTest.java`
- Add: `core/bootstrap/src/test/java/pro/deta/orion/bootstrap/ReleaseVerificationOptionsTest.java`
- Add: `core/bootstrap/src/test/java/pro/deta/orion/bootstrap/ReleaseVerifierTest.java`
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/bootstrap/ExecutableLauncherScriptTest.java`

**Step 1: Write failing tests**

Cover:

- no arguments start the server;
- `verify` delegates to the verifier without starting the server;
- unknown commands return usage error;
- verify options parse from CLI and environment;
- verification fails closed when no fingerprint is supplied;
- verification succeeds when the public key fingerprint matches;
- the launcher dispatches `verify` to Java instead of implementing GPG logic in shell.

**Step 2: Run focused tests**

Run:

```sh
mvn test -Pdev -pl core/bootstrap -am -Dtest=OrionCliTest,ReleaseVerificationOptionsTest,ReleaseVerifierTest,ExecutableLauncherScriptTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: fails before implementation because Java CLI classes are missing.

### Task 2: Implement Java CLI and Verifier

**Files:**
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/App.java`
- Add: `core/bootstrap/src/main/java/pro/deta/orion/bootstrap/OrionCli.java`
- Add: `core/bootstrap/src/main/java/pro/deta/orion/bootstrap/ReleaseVerificationOptions.java`
- Add: `core/bootstrap/src/main/java/pro/deta/orion/bootstrap/ReleaseVerifier.java`

**Step 1: Add command dispatch**

`App.main` should delegate to `OrionCli`. `OrionCli` should support:

- no arguments or `run`: start Orion;
- `verify`: run release verification;
- `help`, `--help`, `-h`: print usage;
- unknown commands: return exit code `2`.

**Step 2: Add verify argument parsing**

Support:

- `--artifact`
- `--key`
- `--key-url`
- `--fingerprint`
- `--signature`
- `--signature-url`
- `--gpg`
- `--help`

Use environment defaults:

- `ORION_RELEASE_PUBLIC_KEY`
- `ORION_RELEASE_PUBLIC_KEY_URL`
- `ORION_RELEASE_KEY_FINGERPRINT`
- `ORION_RELEASE_SIGNATURE`
- `ORION_RELEASE_SIGNATURE_URL`
- `ORION_GPG`

**Step 3: Add GPG verification**

The verifier should:

1. fail closed when the expected fingerprint is missing;
2. resolve the current executable jar as the default artifact;
3. load a local or downloaded release public key;
4. load a local, sibling, or downloaded detached signature;
5. inspect the public key fingerprint with `gpg --with-colons`;
6. compare the normalized fingerprint before importing the key;
7. import the key into a temporary `GNUPGHOME`;
8. run `gpg --verify <signature> <artifact>`;
9. remove temporary key material before exit.

### Task 3: Keep Shell Launcher Thin

**Files:**
- Modify: `core/bootstrap/src/main/launcher/orion-launcher.sh`

Update the `verify)` case to call:

```sh
run_app verify "$@"
```

Do not keep download, fingerprint, or GPG verification logic in shell.

### Task 4: Generate Checksum Artifact

**Files:**
- Modify: `core/bootstrap/pom.xml`

In the `create-executable-jar` antrun target, add an Ant `checksum` task for:

```text
${project.build.directory}/${project.build.finalName}-executable.jar
```

Use SHA-256 and write:

```text
${project.build.directory}/${project.build.finalName}-executable.jar.sha256
```

Attach the checksum as a build artifact.

### Task 5: Document Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/plans/2026-05-11-release-verification-design.md`

Document:

- `gpg --verify <jar>.asc <jar>` as the independent verification path;
- `./<jar> verify --fingerprint <fingerprint>` as a convenience wrapper;
- default public key URL;
- local and network signature overrides;
- environment variable equivalents.

### Task 6: Verify End-To-End

Run:

```sh
mvn test -Pdev -pl core/bootstrap -am -Dtest=OrionCliTest,ReleaseVerificationOptionsTest,ReleaseVerifierTest,ExecutableLauncherScriptTest -Dsurefire.failIfNoSpecifiedTests=false
mvn package -Pdev -pl core/bootstrap -am
mvn test -Pdev
git diff --check
```

Expected:

- focused tests pass;
- package creates the executable jar and `.sha256`;
- routine tests pass;
- diff check has no whitespace errors.
