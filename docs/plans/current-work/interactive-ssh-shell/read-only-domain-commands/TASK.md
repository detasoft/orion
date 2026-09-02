# Add Read-Only Domain Commands

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md
Depends on: ../command-core-and-exec/TASK.md,
../interactive-terminal/TASK.md
Related hierarchy: ../../../upcoming-work/hierarchical-orion-configuration/TASK.md
Related sessions: ../../../current-work/agent-session-server/TASK.md

Expose Orion domain state through structured, ACL-filtered command handlers and
hierarchical resource scopes.

## Scope

- Implement `whoami`, `/repository ls` and `show`, `/organization ls`,
  `/session ls` and `show`, `/proxy ls`, `/system resource`, and
  `/system service ls` as their backing services become available.
- Support organization-local paths such as `/organization/acme/user ls` and
  `/organization/acme/repository ls` without global-name leakage.
- Resolve resources by full ID, unique prefix, or unique scoped name and return
  useful ambiguity results.
- Return structured rows and objects from handlers; leave terminal and plain
  formatting to renderers.
- Filter collections by existing ACL and authorize actions on each resolved
  resource for both ordinary users and administrators.
- Test cross-organization name collisions, partial access, empty collections,
  ambiguous prefixes, unavailable services, and interactive/exec equivalence.
