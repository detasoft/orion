# Add the Interactive Orion SSH Shell

Status: todo
Detailed plan: ../../2026-09-02-interactive-ssh-shell.md

Turn the existing Apache Mina SSHD endpoint into Orion's shared operator entry
point while preserving Git-over-SSH and never exposing a system shell.

The first functional version is read-only except for the authenticated user's
SSH credentials. Querying, streaming monitors, and session-host attachment are
explicit follow-up slices over the same command and result abstractions.

## Child Tasks

- [ ] [Make root recovery enrollment one-time](root-recovery-enrollment/TASK.md)
- [ ] [Add SSH credential commands](credential-commands/TASK.md)
- [ ] [Add read-only domain commands](read-only-domain-commands/TASK.md)
- [ ] [Add querying and automation renderers](query-and-output/TASK.md)
- [ ] [Add streaming commands and monitoring](streaming-monitoring/TASK.md)
- [ ] [Add the session-host PTY gateway](session-host-pty-gateway/TASK.md)
- [ ] [Complete SSH shell security and acceptance coverage](security-and-acceptance/TASK.md)
