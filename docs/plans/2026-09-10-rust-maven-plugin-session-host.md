# Rust Maven Plugin and Session Host Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build and test `session-host` inside the Maven lifecycle through an independently releasable Rust
Maven plugin while using Maven Build Cache for content-based up-to-date checks.

**Architecture:** `build-tools/rust-maven-plugin` is a standalone Maven project with its own version and
release configuration. Orion consumes the plugin from `session-host`, where Maven lifecycle bindings own
build, test, and packaging while Apache Maven Build Cache owns source fingerprints and cached outputs; Cargo
continues to own compiler-level incrementality.

**Tech Stack:** Java 17, Maven Plugin API, Maven Plugin Tools, Maven Release Plugin, Maven 3.9, Maven 4,
Apache Maven Build Cache Extension, Cargo, Rust 1.97

---

## Required delta

- `mvn compile`, `mvn test`, and `mvn package` run the corresponding Cargo work for the `session-host` reactor
  module.
- `mvn package -Pdist` builds a release-profile native executable and places it in the carrier JAR consumed by
  the Orion distribution.
- Unchanged `session-host` inputs use Maven Build Cache; any content change causes a cache miss and reruns the
  applicable goals.
- An invocation containing the Maven `clean` lifecycle reruns Cargo tests even if Maven restores the module from
  a matching cache entry.
- Cargo incrementality is controlled independently by the plugin's `incremental` parameter.
- The plugin is released only from its standalone project with `release:prepare` and `release:perform`; no Orion
  release configuration is added.
- The same plugin artifact runs under Maven 3.9.x and Maven 4.

## Preserved behavior and boundaries

- `session-host` remains the native process and protocol owner; no Rust runtime behavior or protocol changes.
- The existing `pro.deta.orion:session-host` carrier artifact remains the dependency used by AgentD and bootstrap.
- Rustup continues to select the exact toolchain from `session-host/rust-toolchain.toml`.
- Native support remains limited to the host platforms already supported by the repository; this work does not
  add Windows execution or cross-toolchain installation.
- The standalone plugin is not added to Orion's `<modules>` and does not inherit Orion's `${revision}`.

### Task 1: Create the independently releasable plugin project

**Files:**

- Create: `build-tools/rust-maven-plugin/pom.xml`
- Create: `build-tools/rust-maven-plugin/README.md`
- Create: `build-tools/rust-maven-plugin/src/test/java/pro/deta/maven/rust/CargoCommandTest.java`
- Create: `build-tools/rust-maven-plugin/src/test/java/pro/deta/maven/rust/CargoTestOnCleanMojoTest.java`
- Create: `build-tools/rust-maven-plugin/src/main/java/pro/deta/maven/rust/CargoCommand.java`
- Create: `build-tools/rust-maven-plugin/src/main/java/pro/deta/maven/rust/AbstractCargoMojo.java`
- Create: `build-tools/rust-maven-plugin/src/main/java/pro/deta/maven/rust/CargoBuildMojo.java`
- Create: `build-tools/rust-maven-plugin/src/main/java/pro/deta/maven/rust/CargoTestMojo.java`
- Create: `build-tools/rust-maven-plugin/src/main/java/pro/deta/maven/rust/CargoTestOnCleanMojo.java`

**Step 1: Write failing command-construction tests**

Specify that `build` and `test` use the configured manifest, optional target, Cargo profile, features, `--locked`,
`--offline`, and `CARGO_INCREMENTAL`. Cover the normal command and a disabled-incrementality/optional-argument
case.

**Step 2: Verify RED**

Run outside the sandbox:

```bash
mvn test -f build-tools/rust-maven-plugin/pom.xml
```

Expected: compilation failure because the plugin classes do not exist.

**Step 3: Implement the minimal Cargo goals**

Use one package-private command value and one abstract Mojo for shared typed parameters and `ProcessBuilder`
execution. Add only the public `build`, `test`, and cache-supporting `test-on-clean` goals, all marked
`threadSafe = true`. Copy the selected binary to the configured Maven output directory only in `build`.

`test` records successful execution in the Maven plugin context. `test-on-clean` runs only when the requested
goals contain the clean lifecycle and the normal test goal was skipped by a cache hit; it otherwise performs no
work. Expected process failures become `MojoExecutionException` with the Cargo exit code.

**Step 4: Verify GREEN under Maven 3.9**

Run outside the sandbox:

```bash
mvn test -f build-tools/rust-maven-plugin/pom.xml
```

Expected: PASS.

**Step 5: Configure independent release and Maven compatibility**

Keep a standalone literal plugin version and POM model 4.0.0. Compile against the stable Maven 3 plugin API,
generate the current plugin descriptor, declare Maven 3.9 as the minimum, avoid Maven internals and legacy
Plexus injection, and configure release tags as `rust-maven-plugin-@{project.version}`. Configure only this POM
for `release:prepare` and `release:perform`, with deployment to the plugin's GitHub Packages repository.

**Step 6: Verify packaging and Maven 4 execution**

Run outside the sandbox with the installed Maven 3.9 and a downloaded Maven 4 distribution:

```bash
mvn verify -f build-tools/rust-maven-plugin/pom.xml
<maven-4-home>/bin/mvn verify -f build-tools/rust-maven-plugin/pom.xml
```

Expected: both builds pass and the generated JAR contains `META-INF/maven/plugin.xml`.

**Step 7: Stage and review the plugin checkpoint**

Stage only `build-tools/rust-maven-plugin`. Inspect the staged diff and required checks, then request the Simple
workflow commit with subject:

```text
Integrate session-host into Maven lifecycle: Add independent Rust Maven plugin
```

### Task 2: Integrate session-host with Maven lifecycle and cache

**Files:**

- Create: `.mvn/extensions.xml`
- Create: `.mvn/maven-build-cache-config.xml`
- Modify: `session-host/pom.xml`
- Modify: `session-host/README.md`
- Modify: `core/bootstrap/pom.xml`
- Modify: `Makefile`
- Create or modify: Maven model/cache tests in the smallest existing test-support module that can exercise the
  effective lifecycle and packaged resources

**Step 1: Write failing integration tests**

Specify the `session-host` lifecycle bindings, debug/default and release/`dist` profiles, carrier-JAR resource
path, cache input coverage, and forced `test-on-clean` reconciliation. Cover a regular package and the meaningful
edge case where `clean test` must execute Cargo tests despite a cache hit.

**Step 2: Verify RED**

Run the new focused Maven test with:

```bash
make run-test MODULE=<test-module> TEST='<test-class>'
```

Expected: FAIL because the lifecycle and cache configuration are absent.

**Step 3: Bind Cargo work to session-host phases**

Configure the published plugin version in `session-host/pom.xml`: `build` at `compile`, `test` at `test`, and
`test-on-clean` after the normal test execution. Use Cargo's development profile by default and override it with
`release` in Maven's existing `dist` profile. Package the copied executable under
`META-INF/orion/native/session-host/<target>/session-host` in the carrier JAR.

**Step 4: Configure Maven Build Cache**

Declare Apache Maven Build Cache Extension 1.3.0 in `.mvn/extensions.xml`. Explicitly include `Cargo.toml`,
`Cargo.lock`, `rust-toolchain.toml`, Rust sources, protocol fixtures, and build-script inputs for `session-host`;
exclude Cargo and Maven output directories. Register `test-on-clean` as an always-run goal so the Mojo can
enforce the approved clean behavior. Do not implement a second fingerprint or timestamp database in the plugin.

**Step 5: Remove the old Make test pre-step**

Make the root `test` goal invoke Maven directly, allowing the `session-host` reactor module to run in Maven's
parallel graph. Preserve direct Make targets that remain useful for explicit Rust-only development unless they
duplicate the new canonical full-build path.

**Step 6: Verify lifecycle, change detection, clean, and distribution packaging**

Run outside the sandbox:

```bash
mvn compile -Pdev -T 4 -pl session-host -am
mvn test -Pdev -T 4 -pl session-host -am
mvn test -Pdev -T 4 -pl session-host -am
mvn clean test -Pdev -T 4 -pl session-host -am
mvn package -Pdist -T 4 -pl core/bootstrap -am
make test
```

Expected: the second unchanged test build reports a cache hit; touching a copied Rust input in a temporary test
checkout causes a miss; `clean test` executes Cargo tests; the dist package contains the native session host; and
the full project passes.

**Step 7: Stage, self-review, and request the integration commit**

Review that Maven Build Cache is the only module-level freshness owner, Cargo is the only compiler-level cache,
the plugin remains independently releasable, and no Orion release configuration was introduced. Request the
Simple workflow commit with subject:

```text
Integrate session-host into Maven lifecycle: Bind Rust build to Maven lifecycle
```
