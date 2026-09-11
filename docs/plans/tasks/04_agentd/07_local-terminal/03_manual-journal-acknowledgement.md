# Add Explicit Local Journal Acknowledgement

Status: todo
Depends on: completed local terminal attach `8667378a`

Add an opt-in `--ack-journal` testing mode without changing the default
non-acknowledging attach behavior.

## Requirements

- After a contiguous journal page is fully decoded and its terminal output is
  successfully written, send that page's last EventId through the monotonic
  `ACK_JOURNAL` retention control.
- Never acknowledge an incomplete tail, gap, corrupt page, output failure, or
  partially observed page.
- Keep watermarks monotonic only in memory for the current invocation. Do not
  persist a local replica cursor or imply server durability.
- A repeated acknowledgement after ambiguous delivery is harmless. Report
  definite rejection and continue non-acknowledging output for that invocation.
- Warn that native retention may delete history required by a later stateless
  attach.

## Acceptance

- Default start and attach still send no acknowledgement.
- Opt-in behavior advances the real host retention watermark only after safe
  page delivery and is covered across restart, rotation, and failure cases.
