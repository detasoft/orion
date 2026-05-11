# Executable Jar Packaging Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build Orion as a single self-executable jar without Spring Boot.

**Architecture:** Package `core/bootstrap` as a shaded runnable jar, then prepend a small POSIX launcher script to create an executable jar that can run directly or act as an init.d service target. Keep the normal shaded jar available for `java -jar` use.

**Tech Stack:** Maven, `maven-shade-plugin`, `maven-antrun-plugin`, Java 21, POSIX shell, existing `pro.deta.orion.App` entry point.

---

### Task 1: Add Launcher Script Coverage

**Files:**
- Create: `core/bootstrap/src/test/java/pro/deta/orion/bootstrap/ExecutableLauncherScriptTest.java`

**Step 1: Write the failing test**

Add a JUnit test that reads `src/main/launcher/orion-launcher.sh` and checks:

- it starts with `#!/bin/sh`;
- it declares LSB init metadata;
- it supports `run`, `start`, `stop`, `status`, and `restart`;
- it delegates to `java -jar "$SELF"`.

**Step 2: Run the focused test**

Run:

```sh
mvn test -Pdev -pl core/bootstrap -am -Dtest=ExecutableLauncherScriptTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: fails because the launcher script does not exist yet.

### Task 2: Add Custom Executable Packaging

**Files:**
- Modify: `pom.xml`
- Modify: `core/bootstrap/pom.xml`
- Create: `core/bootstrap/src/main/launcher/orion-launcher.sh`

**Step 1: Replace Spring Boot plugin management**

Remove `spring-boot-maven-plugin` and add managed versions for:

- `maven-shade-plugin`;
- `maven-antrun-plugin`;
- `build-helper-maven-plugin`.

**Step 2: Configure shaded jar**

In `core/bootstrap/pom.xml`, add `maven-shade-plugin` bound to `package` with:

- `shadedArtifactAttached=true`;
- `shadedClassifierName=all`;
- `createDependencyReducedPom=false`;
- `ManifestResourceTransformer` with `mainClass=pro.deta.orion.App`;
- `ServicesResourceTransformer`;
- artifact exclusions for inherited compile-only or test APIs such as Lombok
  and JUnit;
- signature-file exclusions under `META-INF`.

**Step 3: Create executable jar**

Add an `maven-antrun-plugin` `package` execution that concatenates:

1. `src/main/launcher/orion-launcher.sh`
2. `target/bootstrap-${project.version}-all.jar`

into:

```text
target/bootstrap-${project.version}-executable.jar
```

Then chmod that file to `755`.

**Step 4: Attach executable jar**

Use `build-helper-maven-plugin` to attach the executable jar with classifier
`executable` and type `jar`.

**Step 5: Run focused test**

Run the same focused test again.

Expected: passes.

### Task 3: Update Documentation

**Files:**
- Modify: `README.md`

**Step 1: Update distribution section**

Document:

- `mvn package -Pdev -pl core/bootstrap -am`;
- `bootstrap-1.0-SNAPSHOT-all.jar` for `java -jar`;
- `bootstrap-1.0-SNAPSHOT-executable.jar` for direct execution;
- direct commands: `run`, `start`, `status`, `stop`, `restart`;
- init.d symlink installation.

**Step 2: Check formatting**

Run:

```sh
git diff --check
```

Expected: no output and exit code 0.

### Task 4: Verify Packaging End-To-End

**Files:**
- No source edits.

**Step 1: Build bootstrap package**

Run:

```sh
mvn package -Pdev -pl core/bootstrap -am
```

Expected: build succeeds.

**Step 2: Check artifacts**

Run:

```sh
ls -l core/bootstrap/target/bootstrap-1.0-SNAPSHOT-all.jar \
  core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar
```

Expected: both files exist and executable jar has executable bit.

**Step 3: Check launcher preamble**

Run:

```sh
head -1 core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar
```

Expected: `#!/bin/sh`.

**Step 4: Check manifests**

Run:

```sh
unzip -p core/bootstrap/target/bootstrap-1.0-SNAPSHOT-all.jar META-INF/MANIFEST.MF
```

Expected: `Main-Class: pro.deta.orion.App`.

**Step 5: Run routine tests**

Run:

```sh
mvn test -Pdev
```

Expected: reactor build succeeds.
