# Module Review: `rust-maven-plugin`

## 2. Ambient Cargo targets can cause successful packaging of a stale binary

**Problem.** With `rust.target` unset, `CARGO_BUILD_TARGET` or Cargo's `build.target` can redirect compilation
to a target-specific directory. The copier still searches the untargeted host path. A clean build fails;
an incremental build can copy an older host binary and report success under the detected host identity.

**Sources.** [Omitted target argument](src/main/java/pro/deta/maven/rust/CargoCommand.java#L44),
[inherited environment](src/main/java/pro/deta/maven/rust/CargoRunner.java#L32),
[post-build host detection and copying](src/main/java/pro/deta/maven/rust/CargoBuildMojo.java#L33),
[consumer without target](../../session-host/pom.xml#L41), and
[native resource identity](../../agentd/src/main/java/pro/deta/orion/agentd/runtime/BundledSessionHost.java#L163).

**Documented behavior and contract.** [README](README.md#parameters) defines the default as Cargo host.
Cargo documents [ambient targets and explicit overrides](https://doc.rust-lang.org/cargo/reference/config.html#buildtarget)
and [target-specific output](https://doc.rust-lang.org/cargo/reference/build-cache.html).
Invocation, selected source and destination identity must agree; packaging must use the requested build's binary.
No ambient multi-target support promise was found.

**Minimal repair.** Resolve one target before building and use it consistently in invocation and source/destination
paths. Explicitly enforcing the documented host default is the smallest deterministic choice. Settle whether
ambient selection must be preserved first; explicit host targeting also changes Cargo dependency/flag handling.
Test ambient selection with an existing stale untargeted binary, plus explicit target selection.

**Alternatives and consequences.** Honoring ambient selection requires discovering its effective value,
including potentially multiple targets. Clearing only the environment leaves file configuration unresolved.
Neither choice permits copying unrelated prior output. No new module or dependency is necessary.

**Confidence.** High for the trigger; no checked-in ambient override or live reproduction was found.

**Priority signals.** Importance: medium with artifact integrity risk. Repair ease: medium, local changes with
a target ownership decision and actual Cargo semantics to verify.
