# Executable Jar Packaging Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Maven packaging that produces Orion runnable jars for `java -jar` and Unix init.d-style service use.

**Architecture:** Configure packaging in `core/bootstrap`, the module that owns `pro.deta.orion.App` and has the runtime dependency graph. Use Spring Boot's Maven plugin as a repackager only, attaching classified artifacts without adding Spring runtime dependencies. Document the produced artifacts and their intended deployment modes.

**Tech Stack:** Maven, `spring-boot-maven-plugin`, Java 21, existing `core/bootstrap` application entry point.

---

### Task 1: Configure Executable Jar Packaging

**Files:**
- Modify: `pom.xml`
- Modify: `core/bootstrap/pom.xml`

**Step 1: Add a Spring Boot Maven plugin version property**

Add a root Maven property:

```xml
<spring-boot-maven-plugin.version>3.5.0</spring-boot-maven-plugin.version>
```

**Step 2: Add plugin management**

In root `pom.xml` plugin management, add:

```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <version>${spring-boot-maven-plugin.version}</version>
</plugin>
```

**Step 3: Configure `core/bootstrap` packaging executions**

In `core/bootstrap/pom.xml`, add `spring-boot-maven-plugin` with two `repackage`
executions bound to `package`:

- `repackage-app`, classifier `app`, executable `false`
- `repackage-initd`, classifier `initd`, executable `true`

Both executions use:

```xml
<mainClass>pro.deta.orion.App</mainClass>
```

**Step 4: Run focused package build**

Run:

```sh
mvn package -Pdev -pl core/bootstrap -am
```

Expected: build succeeds and creates:

- `core/bootstrap/target/bootstrap-1.0-SNAPSHOT-app.jar`
- `core/bootstrap/target/bootstrap-1.0-SNAPSHOT-initd.jar`

### Task 2: Add Packaging Documentation

**Files:**
- Modify: `README.md`

**Step 1: Add a distribution section**

Document the package command, `java -jar` usage, direct executable usage, and an
init.d symlink example.

**Step 2: Verify documentation formatting**

Run:

```sh
git diff --check
```

Expected: no output and exit code 0.

### Task 3: Verify End-To-End

**Files:**
- No source edits.

**Step 1: Check produced artifacts**

Run:

```sh
ls -l core/bootstrap/target/*-app.jar core/bootstrap/target/*-initd.jar
```

Expected: both files exist.

**Step 2: Check init.d jar shell preamble**

Run:

```sh
head -1 core/bootstrap/target/bootstrap-1.0-SNAPSHOT-initd.jar
```

Expected: the first line starts with `#!/`.

**Step 3: Run routine tests**

Run:

```sh
mvn test -Pdev
```

Expected: reactor build succeeds.
