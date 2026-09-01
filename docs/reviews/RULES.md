# Code Review Rules

These rules define findings that block approval. Report a violation as a
blocking issue rather than an optional improvement.

## Verification Ownership

Do not run Maven verification solely for a code review. The agent implementing
the change owns Maven verification; reviewers may rely on its recorded results
and perform read-only inspection. A reviewer may still call out missing,
inadequate, or failed verification.

## Timeout Enforcement

Block an implementation that creates a dedicated platform or virtual thread
for each blocking I/O call solely to enforce its timeout. Thread allocation is
not a timeout mechanism, and virtual threads make blocking cheaper without
bounding how long an operation or its underlying transport can remain open.

One virtual thread for a long-lived session or complete operation is allowed.
Enforce timeouts with transport-native deadlines where available. Otherwise,
use cancellation driven by a shared scheduler or operation watchdog that
closes the underlying session or transport without allocating a thread per I/O
call.

When an API exposes connect, read, write, or whole-operation timeouts, verify
that each promised timeout is applied at the relevant boundary. If a transport
cannot support one of them, require the unsupported timeout to be removed from
that transport's contract or documented as unsupported instead of simulating
it with per-call thread allocation.
