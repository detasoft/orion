# Add Explicit Local Journal Acknowledgement

Status: todo
Depends on: completed local terminal attach `8667378a`

- Owner: codex, session 01a09f52-b1d0-7522-bbed-2c6c389576b6,
  branch `codex/local-journal-ack-01a09f52`,
  worktree `.worktrees/local-journal-ack-01a09f52`, paused 2026-09-15 17:40 Europe/Amsterdam;
  next: rerun focused terminal tests after unrelated native timeouts are resolved, then
  run `mvn test -Pdev -T 4` and finish review of the unstaged implementation.

Add an opt-in `--ack-journal` testing mode without changing the default
non-acknowledging attach behavior.

## Requirements

- After a contiguous journal page is fully decoded and its terminal output is
  successfully written, send that page's last EventId through the monotonic
  `ACK_JOURNAL` retention control.
- Never acknowledge an incomplete tail, missing segment, corrupt page, output
  failure, or partially observed page. Numeric EventId jumps alone do not prove
  a missing record.
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

## Design and Implementation

- Parse `--ack-journal` for local terminal start and attach, and pass the explicit
  choice through the existing terminal attachment path.
- Let the journal follower identify fully decoded and successfully delivered
  pages using the existing reader boundaries and issues. Use the existing native
  control client and acknowledgement result; retain no cursor on disk and add
  no server connection or alternative journal reader.
- Keep successful acknowledgement watermarks monotonic within one invocation.
  Contain acknowledgement rejection to this optional mode, preserving terminal
  output and ordinary manual controls after acknowledgement is disabled.
- Extend parsing, follower, attachment, and real native-host tests to cover the
  default, opt-in, replay/restart, rotation, incomplete/corrupt/missing data,
  output failure, ambiguous delivery, and definite rejection. Check retained
  history and actual host watermarks rather than only emitted command shapes.
- Update local terminal help and documentation with the retention warning and
  the absence of any server-durability guarantee.
