# Rust Maven Plugin

`pro.deta.maven:rust-maven-plugin` binds Cargo binaries and tests to a Maven lifecycle without invoking a shell
or maintaining a second incremental-build database. It is an independent Maven project stored under Orion's
`build-tools` directory; it does not inherit from or participate in the Orion reactor.

The plugin uses the stable Maven 3 Plugin API and requires Maven 3.9 or newer and Java 17. The same artifact is
compatible with Maven 4; the experimental Maven 4 Plugin API is intentionally not used.

## Goals

- `rust:build` runs `cargo build` and copies the selected binary to
  `<outputDirectory>/<target>/<binary>`.
- `rust:test` runs `cargo test`.
- `rust:test-on-clean` is a cache companion. When configured as an always-run Maven Build Cache goal, it reruns
  tests only if the Maven invocation contained `clean` and the normal `rust:test` execution was restored from
  cache.

All goals are thread-safe. Cargo must already be available; the plugin does not install Rustup, toolchains, or
cross-compilation targets.

## Parameters

| Parameter | Default | Purpose |
| --- | --- | --- |
| `cargoExecutable` / `rust.cargo` | `cargo` | Cargo executable |
| `manifest` / `rust.manifest` | required | `Cargo.toml` file |
| `cargoTargetDirectory` / `rust.cargoTargetDirectory` | `${project.build.directory}/cargo` | Cargo outputs |
| `target` / `rust.target` | Cargo host | Optional Rust target triple |
| `profile` / `rust.profile` | Cargo default | Named Cargo profile |
| `release` / `rust.release` | `false` | Use Cargo's release profile |
| `features` / `rust.features` | empty | Cargo feature list |
| `locked` / `rust.locked` | `true` | Pass `--locked` |
| `offline` / `rust.offline` | `false` | Pass `--offline` |
| `incremental` / `rust.incremental` | `true` | Set `CARGO_INCREMENTAL` |
| `binary` / `rust.binary` | required for `build` | Binary file name |
| `outputDirectory` / `rust.outputDirectory` | `${project.build.directory}/native` | Copied binary root |
| `skip` / `rust.skip` | `false` | Skip Cargo execution |

`release` and `profile` are mutually exclusive. Maven Build Cache is configured by the consuming build and is
independent from the `incremental` parameter.

## Build and compatibility checks

From the repository root:

```bash
mvn verify -f build-tools/rust-maven-plugin/pom.xml
```

Run the same command with a Maven 4 executable to check Maven 4 compatibility.

## Release

The plugin has its own `0.x` version line and Maven Release Plugin configuration. Releases do not version or
deploy the Orion reactor:

```bash
cd build-tools/rust-maven-plugin
mvn release:prepare
mvn release:perform -DpomFileName=build-tools/rust-maven-plugin/pom.xml
```

Release tags use `rust-maven-plugin-<version>`. Deployment targets GitHub Packages for `detasoft/orion`; Maven
settings must provide a GitHub Packages token under server id `github`:

```xml
<server>
  <id>github</id>
  <username>GITHUB_USER</username>
  <password>GITHUB_TOKEN</password>
</server>
```

Because the standalone project lives in the Orion Git repository, release preparation still requires the shared
Git working tree to be clean and creates a repository tag dedicated to the plugin. The explicit `pomFileName`
points the checked-out repository at the standalone plugin POM, so `release:perform` does not build or deploy the
Orion reactor.
