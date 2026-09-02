# Package Targets and Verify End-to-End Acceptance

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: all sibling native-session-host tasks

Turn the platform implementations into reproducible Orion artifacts and verify
the complete agent-harness scenario.

## Scope

- Build Linux x86_64/aarch64, macOS x86_64/aarch64, and Windows
  x86_64/aarch64 standalone artifacts from the pinned toolchain; prefer
  self-contained Linux binaries where PTY and Landlock dependencies permit it.
- Package the correct host artifact through Maven and record provenance and
  protocol version information.
- Exercise shell input, raw output, resize ordering, input deduplication,
  rotation, crash recovery, and `xterm.js` replay.
- Run Claude or Codex through the host, disconnect and reconnect the local
  control client, and continue input/output without terminating the session.
- Verify Landlock restrictions for the agent and one of its subprocesses.
- Publish a support matrix, operational defaults, and diagnostics for session
  discovery, gaps, sandbox failures, and incompatible formats.
