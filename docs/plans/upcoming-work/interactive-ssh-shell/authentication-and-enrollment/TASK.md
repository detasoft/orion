# Add Named-User SSH Authentication and Key Enrollment

Status: todo
Detailed plan: ../../../2026-09-02-interactive-ssh-shell.md
Related key work: ../../../current-work/unified-key-material-bootstrap/TASK.md

Add the user-oriented SSH login path and one-time bootstrap enrollment without
changing the existing `git@orion` identity and Git command behavior.

## Scope

- Authenticate interactive and Orion exec requests by username plus an
  authorized SSH credential; keep Git identity derived from its public key.
- Generate a one-time bootstrap token on first startup, expose it through the
  configured startup mechanism, and regenerate it only on an explicit restart.
- Collect and fingerprint candidate public keys per SSH connection, deduplicate
  repeated attempts, and verify how Mina SSHD 2.13.2 proves private-key
  possession before allowing an offered key to be enrolled.
- Use keyboard-interactive bootstrap authentication, let the user select one
  or more proven keys or paste an OpenSSH public key, persist them, invalidate
  the token, and close the connection for a key-authenticated reconnect.
- Keep credential representation algorithm-neutral for future OpenSSH
  security-key, smart-card, and hardware-backed credentials.
- Cover invalid and reused tokens, unknown users, duplicate and multiple keys,
  unproved probes, persistence across restart, and unchanged Git authentication.
