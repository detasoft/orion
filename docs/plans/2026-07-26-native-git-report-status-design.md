# Native Git Report-Status Design

## Goal

Add JGit-free receive-pack `report-status` v1 parsing and writing primitives to
`core/git-parser`.

## Scope

The first implementation supports the classic receive-pack status grammar:

- `unpack ok`;
- `unpack <reason>`;
- `ok <ref>`;
- `ng <ref> <reason>`;
- flush termination.

`report-status-v2`, side-band wrapping, receive-pack policy, ref validation, and
reason sanitization stay outside this change.

## API Shape

Add small wire-level records:

- `GitReportStatus`: unpack status plus ordered per-ref results.
- `GitReportStatusRef`: one `ok` or `ng` result for a ref.

Add split parser and writer classes:

- `GitReportStatusParser` reads pkt-line framed status lines from a `ByteBuf`
  until flush.
- `GitReportStatusWriter` writes status lines with `GitPktLineWriter` and ends
  with flush.

This follows the existing `GitCapabilityParser` and `GitCapabilityWriter`
pattern and keeps `GitPktLineWriter` focused on pkt-line framing.

## Parsing Rules

The parser expects exactly one unpack line before ref status lines. It strips one
optional LF, preserves reason text after the status prefix, and keeps ref results
in wire order.

Malformed pkt-line headers, truncated payloads, and semantic grammar errors use
`GitWireException` with `GitWireError`. Report-status semantic failures use
typed kinds such as `MISSING_UNPACK_STATUS`, `DUPLICATE_UNPACK_STATUS`, and
`INVALID_REPORT_STATUS_LINE` so receive-pack callers can distinguish wire
grammar failures from caller-side model validation.

## Writing Rules

The writer accepts a complete `GitReportStatus` and emits:

1. one unpack line;
2. each ordered per-ref status line;
3. a flush packet.

The caller owns receive-pack semantics, including whether a ref succeeded,
whether an unpack failure should suppress ref entries, and how rejection reasons
are sanitized.

## Tests

Focused `core/git-parser` tests cover:

- successful unpack with multiple `ok` refs;
- unpack failure plus rejected ref;
- preservation of per-ref reject reasons;
- exact writer pkt-line sequence;
- typed malformed semantic input such as ref status before unpack or invalid
  `ng`.
