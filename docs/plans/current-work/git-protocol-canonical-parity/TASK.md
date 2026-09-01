# Git Protocol Canonical Parity

Status: complete
Source: follow-up from Git protocol parity audit against canonical `git/git`.

Track and close behavior differences between Orion's native Git protocol paths
and canonical Git. Use the canonical C implementation and technical specs as
the reference before changing behavior.

## Canonical References

- `daemon.c`
- `builtin/upload-pack.c`
- `builtin/receive-pack.c`
- `http-backend.c`
- `Documentation/technical/pack-protocol.txt`
- `Documentation/technical/protocol-v2.txt`
- `Documentation/technical/protocol-common.txt`
- `Documentation/technical/http-protocol.txt`

## Known Open Gaps

These are follow-up protocol parity tasks, not remaining work for the current
closed fix.

- [x] Complete protocol v2 shallow history support:
      `docs/plans/current-work/complete-shallow-history/TASK.md`.
- [x] Audit upload-pack, receive-pack, daemon, and HTTP edge cases against the
      canonical references above, then split any confirmed behavior gaps into
      focused task nodes near the owning protocol parent.

## Audit Follow-ups

- [x] [Align protocol version negotiation](protocol-version-negotiation/TASK.md)
- [x] [Align native daemon bootstrap parsing](native-daemon-bootstrap/TASK.md)
- [x] [Complete legacy upload-pack negotiation rounds](legacy-upload-pack-negotiation-rounds/TASK.md)
- [x] [Complete the legacy upload-pack request surface](legacy-upload-pack-request-surface/TASK.md)
- [x] [Complete receive-pack command and status handling](receive-pack-command-status/TASK.md)
- [x] [Align receive-pack ref update semantics](receive-pack-update-semantics/TASK.md)
- [x] [Align Smart HTTP request semantics](smart-http-request-semantics/TASK.md)

## Recently Closed Gaps

- [x] Legacy upload-pack advertises both `multi_ack_detailed` and `multi_ack`
      and emits their common/continue ACK forms. Remaining round termination
      parity is tracked separately above.
- [x] Receive-pack refname parsing accepts UTF-8/non-ASCII ref names while
      rejecting ASCII control, space, and forbidden Git ref characters.
- [x] Protocol v2 `want-ref` rejects duplicate ref requests instead of silently
      deduplicating them.
- [x] Smart HTTP protocol v2 discovery omits the legacy `# service=...`
      announcement while preserving v0/v1 discovery framing.
