# Add SSH Credential Commands

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md
Depends on: ../authentication-and-enrollment/TASK.md,
../command-core-and-exec/TASK.md, ../interactive-terminal/TASK.md

Let an authenticated user inspect and manage their own SSH credentials from the
Orion shell without granting broader ACL administration.

## Scope

- Implement `/auth/key ls`, `/auth/key add`, and `/auth/key rm
  <fingerprint-prefix>` as ordinary command handlers.
- Let `add` select one or more ownership-proven candidate keys from the current
  connection or accept a manually pasted OpenSSH public key.
- Make additions immediately usable without reconnect and make removal require
  an unambiguous fingerprint prefix.
- Define safe last-key removal and lost-key recovery behavior without turning
  password-authenticated enrollment into a bypass of Orion identity checks.
- Persist changes through the existing credential configuration path while
  keeping SSH algorithms extensible.
- Audit additions and removals separately and redact pasted key material where
  appropriate.
- Test duplicates, invalid keys, ambiguous fingerprints, multiple algorithms,
  current-key removal, persistence, and ACL isolation between users.
