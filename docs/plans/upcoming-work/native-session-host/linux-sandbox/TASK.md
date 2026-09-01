# Enforce the Linux Landlock Sandbox

Status: todo
Detailed plan: ../../../2026-09-01-native-session-host.md
Depends on: ../unix-process-host/TASK.md

Apply filesystem policy to the hosted child tree while leaving the host able to
manage journal and control files.

## Scope

- Model read-write and read-only path grants independently of PTY and journal
  protocols.
- Apply `no_new_privs` and Landlock before child `exec`, with restrictions
  inherited by every descendant.
- Fail closed by default when a requested policy cannot be enforced, with an
  explicit opt-in unsandboxed fallback mode.
- Persist an effective, non-secret sandbox description in session metadata.
- Test allowed workspace and temporary paths, denied credential and sibling
  workspace paths, descendant inheritance, unsupported-kernel behavior, and
  host access to its own session directory.
