# Implement the Windows ConPTY Host

Status: todo
Detailed plan: ../../2026-09-01-native-session-host.md
Depends on: completed contracts and build, completed journal core,
completed acknowledgement-gated journal retention
Contract: ../../2026-09-03-native-control-journal-idempotency-design.md
Related: completed process control and PTY closure (`10e92141`, `dcebb944`)

Provide Windows parity without forking the logical journal, lifecycle, or
control behavior.

## Scope

- Remove the Windows Rust-bootstrap limitation in the Maven/Make build. Use
  the pinned toolchain and verified bootstrap downloads, then package the
  Windows executable through the existing native resource carrier.
- Replace the unsupported platform stub with ConPTY execution and named-pipe
  control. Keep the host and its child independent of the launching AgentD.
- Reuse the common journal and metadata contracts, in-memory sequence
  admission, transient `RECEIVED`, and journaled `COMMAND_RESULT`. All operation
  controls, including `ACK_JOURNAL`, use schema 2; response framing uses schema 1.
- Own the Windows child process tree and terminal handles through exit and
  cleanup. Execute each requested signal/termination effect once; keep grace
  periods, escalation, and recovery policy outside the host.
- Map resize, status, exit code, signal availability, and platform failures to
  the shared contracts. Integrate the process-list and PTY-closure contracts
  from their completed common contracts; do not create a Windows-only model.
- Update real AgentD launch/control consumers for Windows execution and named
  pipes, using the same protocol fixtures and operation semantics.
- Verify raw byte preservation and document unavoidable ConPTY behavior
  differences without normalizing the journal stream.

## Acceptance

- Build from the pinned toolchain and verify native resource packaging for
  Windows x86_64 and aarch64. Keep the existing six-target release requirement.
- Exercise a real Windows host: interactive input/output, resize, named-pipe
  reconnect, stale-sequence rejection, journal ACK, and launching-process loss.
- Cover child/descendant exit, explicit termination during blocked input,
  terminal closure, resource cleanup, and journal crash-tail reading. Record
  which Windows architectures were exercised.
- Enable Windows CI and describe Windows as supported only after bootstrap and
  ConPTY acceptance succeeds. Final six-target packaging and end-to-end release
  acceptance remain in `11_release-and-acceptance.md`.
