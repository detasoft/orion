# Descendant Absence Confirmation

## Problem

After the PTY leader exits, the session host currently terminates as soon as one process-table snapshot
contains no tracked descendants. On macOS, process inspection can transiently omit a detached process or
fail to identify its PTY file descriptor. The host can therefore close its control socket while a detached
PTY descendant is still running.

## Design

`wait_for_descendants` will require three consecutive observations with no live descendants before it
allows the host to finish. An observation that finds any live descendant resets the empty-observation
count. The existing 10 ms polling interval remains unchanged, adding only about 20–30 ms to a genuinely
empty session.

The confirmation policy belongs in the platform-independent wait loop so macOS and Linux use the same
lifecycle invariant. Process discovery, PID identity checks, termination signaling, and process-table
errors remain unchanged. Errors still terminate the wait immediately instead of counting as absence.

## Verification

A deterministic unit test will exercise an observation sequence containing two empty snapshots, a live
snapshot, and then three empty snapshots. It must prove that the live snapshot resets confirmation and
that only the third consecutive empty snapshot completes the policy. The existing detached-PTY integration
test remains the end-to-end check.
