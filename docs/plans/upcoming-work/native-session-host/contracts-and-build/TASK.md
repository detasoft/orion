# Freeze Protocols and Bootstrap the Rust Build

Status: active
Owner: codex, session c5799df4, started 2026-09-01 23:18 Europe/Amsterdam.
Detailed plan: ../../../2026-09-01-native-session-host.md

Define the stable cross-language boundary before implementing host behavior.

## Scope

- Document the exact v1 segment, block, record, metadata, and control-message
  layouts, including byte order, limits, checksums, versioning, and error rules.
- Allocate event namespaces and payload versions; require unknown event types to
  remain skippable when framing is compatible.
- Add golden binary fixtures for Rust and future Java readers, including unknown
  events, raw PTY bytes, and malformed or truncated tails.
- Define the CLI for session identity and storage, working directory, initial
  terminal size and type, sandbox policy, and the child command after `--`.
- Create the `session-host` Cargo project and internal platform abstractions.
- Pin Rust and Cargo versions and add Maven packaging that obtains the toolchain
  without requiring a global Rust installation.
- Produce a runnable placeholder artifact through `./mvnw package`.

Completion requires protocol fixtures to be treated as compatibility tests by
all subsequent reader, writer, and control implementations.
