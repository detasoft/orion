# Redact Session Launch Failure Diagnostics

Status: todo
Depends on: ../command-orchestration/TASK.md
Required before: ../release-and-acceptance/TASK.md

Sanitize `SESSION_START_FAILED` diagnostic text before journal encoding,
transmission, or logging while preserving useful bounded launch diagnostics.

## Scope

- Keep at most 1 MiB: the first 64 KiB and last 960 KiB, with the omitted byte
  count recorded when truncation occurs.
- Redact launch and reconnect credentials, authorization and bearer tokens,
  passwords, private key material, secret environment values, and common
  encoded forms.
- Never retain, persist, transmit, or log an unsanitized diagnostic copy.
- Avoid indiscriminate false-positive destruction, while failing closed for
  exact credentials and other secrets already known to AgentD.
- Preserve useful debugging context and explicit truncation metadata.
- Test exact, embedded, boundary-spanning, encoded, and multiple secrets;
  false positives; size and truncation behavior; and absence of raw copies.
