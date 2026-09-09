# Preserve Unsupported Handshake Version Failures

Status: todo
Parent: TASK.md
Finding: ../../../../../agent-protocol/MODULE_REVIEW.md
Depends on: completed AgentD launch identity and HTTP/2 transport.

- [ ] Preserve unsupported handshake version failures.
  - Owner: codex, session agent-protocol-handshake-version-7c2d, started 2026-09-09 21:15 Europe/Amsterdam.

Deliver the existing `UNSUPPORTED_VERSION` decode result through the ordered
control receive flow so the handshake owner rejects that connection attempt.

## Scope

- Preserve generic recovery from semantic control-message failures.
- Fail an initial handshake on an unsupported `WELCOME` before any later
  supported message can complete the same attempt.
- Add production-path coverage for an unsupported `WELCOME` alone and followed
  by a supported one across multiple input chunkings.
- Reuse the existing receive and handshake failure flow; add no new version,
  envelope, or negotiation-policy concept.

## Acceptance

- Unsupported peer versions produce the documented handshake failure without
  changing local sessions.
- Ordered delivery prevents a later supported item from erasing that failure.
- Post-handshake semantic decode recovery remains unchanged.
