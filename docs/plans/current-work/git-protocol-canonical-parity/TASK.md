# Git Protocol Canonical Parity

Status: active
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

- [ ] [Fix legacy upload-pack `multi_ack_detailed`](../multi-ack-detailed/TASK.md).
- [ ] Complete protocol v2 shallow history support:
      `docs/plans/upcoming-work/protocol-v2-server-fetch-extensions/complete-shallow-history/TASK.md`.
- [ ] Audit upload-pack, receive-pack, daemon, and HTTP edge cases against the
      canonical references above, then split any confirmed behavior gaps into
      focused task nodes near the owning protocol parent.

## Recently Closed Gaps

- [x] Receive-pack refname parsing accepts UTF-8/non-ASCII ref names while
      rejecting ASCII control, space, and forbidden Git ref characters.
- [x] Protocol v2 `want-ref` rejects duplicate ref requests instead of silently
      deduplicating them.
- [x] Smart HTTP protocol v2 discovery omits the legacy `# service=...`
      announcement while preserving v0/v1 discovery framing.
