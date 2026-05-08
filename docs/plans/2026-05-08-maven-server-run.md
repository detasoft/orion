# Maven Server Run Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a Maven-native command to start the Orion server and expose a short root-level `make` command for the same workflow.

**Architecture:** Configure `exec-maven-plugin` in the `core/bootstrap` module because that module owns the `pro.deta.orion.App` entry point. Add a root `Makefile` that delegates to the Maven command, then document both entry points in the README.

**Tech Stack:** Maven, `org.codehaus.mojo:exec-maven-plugin`, GNU Make

---

### Task 1: Document the approved design

**Files:**
- Create: `docs/plans/2026-05-08-maven-server-run-design.md`

**Step 1: Save the approved design**

Write the design decisions, alternatives, and verification approach.

**Step 2: Verify the file exists**

Run: `ls docs/plans`
Expected: the design document is listed

### Task 2: Add Maven server execution support

**Files:**
- Modify: `core/bootstrap/pom.xml`

**Step 1: Write the failing verification**

Run:

```bash
mvn -pl core/bootstrap exec:java -Dexec.mainClass=pro.deta.orion.App -q
```

Expected: the command fails before startup because the project does not yet expose a working Maven-native server command.

**Step 2: Add minimal plugin configuration**

Configure a `run-server` profile in `core/bootstrap/pom.xml` that binds `exec-maven-plugin` with `mainClass` set to `pro.deta.orion.App` to the `process-classes` phase.

**Step 3: Verify the configured command**

Run:

```bash
mvn -pl core/bootstrap -am -Prun-server process-classes
```

Expected: Maven builds required reactor modules, reaches `process-classes` for `bootstrap`, and starts Orion until runtime configuration or environment stops it.

### Task 3: Add a root Makefile shortcut

**Files:**
- Create: `Makefile`

**Step 1: Write the failing verification**

Run: `make run-server`
Expected: target does not exist yet.

**Step 2: Add minimal implementation**

Create a `run-server` target that delegates to `mvn -pl core/bootstrap -am -Prun-server process-classes`.

**Step 3: Verify the target**

Run: `make run-server`
Expected: `make` delegates to the Maven command and reaches the same startup path.

### Task 4: Document the final workflow

**Files:**
- Modify: `README.md`

**Step 1: Update startup instructions**

Replace the manual `java pro.deta.orion.App` instruction with the Maven command and add the `make` shortcut.

**Step 2: Verify docs**

Run: `sed -n '1,80p' README.md`
Expected: startup section shows both commands.
