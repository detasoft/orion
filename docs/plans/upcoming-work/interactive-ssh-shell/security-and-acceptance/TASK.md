# Complete SSH Shell Security and Acceptance Coverage

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md
Depends on: all sibling interactive-ssh-shell tasks required by the release

Verify that the shared SSH endpoint preserves Git compatibility, contains the
Orion shell, enforces resource authorization, and safely handles every channel
lifecycle.

## Scope

- Prove that shell and exec requests cannot start an OS shell, execute arbitrary
  programs, escape through quoting, or enable SSH forwarding or filesystem use.
- Cover Git authentication and upload/receive compatibility alongside named-user
  shell and exec authentication.
- Cover bootstrap output and regeneration, lost-key recovery, multiple offered
  keys, ownership proof, token invalidation, and credential audit redaction.
- Verify ACL-filtered help, completion, lists, scoped resolution, actions,
  ambiguous prefixes, and cross-organization isolation for ordinary users.
- Verify interactive/no-PTY equivalence, stable exit codes and plain output,
  PTY resize, cancellation, EOF, reconnect, and server shutdown.
- Extend acceptance coverage for streaming backpressure and session attachment
  when those follow-up tasks are included in the release.
- Publish operator documentation for login, bootstrap recovery, key management,
  automation, audit behavior, and session detach semantics.
