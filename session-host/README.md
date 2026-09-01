# Orion Session Host

`session-host` is Orion's standalone native process and terminal owner. The
current module freezes protocol v1, supplies compatibility fixtures, and
provides the command-line and platform skeleton. PTY/ConPTY execution is added
by later task nodes.

## Build

The Maven module downloads a minimal Rust 1.97.0 toolchain into
`target/rust-toolchains` when one is not already present. It does not use a
globally installed `rustc` or Cargo. The Makefile bootstraps it with the pinned
Rustup 1.29.1 archive for the current host and verifies its checked-in SHA-256
before the bootstrap executable receives permission to run.

```bash
mvn package -pl session-host
```

Maven stores each native build below
`session-host/target/native-resources/META-INF/orion/native/session-host/<target>`.
The `session-host-native` carrier JAR and the bootstrap executable JAR include
every target directory present there. On macOS the executable is a native
Mach-O binary for the host architecture. Release packaging targets x86_64 and
arm64 independently on Linux, macOS, and Windows.

Run Rust tests through Maven:

```bash
mvn test -pl session-host
```

Regenerate checked-in binary protocol fixtures after an intentional protocol
change:

```bash
make session-host-fixtures
```

Changing a v1 fixture is a compatibility change and requires a new format or
payload-schema version. Ordinary implementation work must leave the fixtures
unchanged.
