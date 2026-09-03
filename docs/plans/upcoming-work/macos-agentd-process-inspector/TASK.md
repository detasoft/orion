# Add Safe macOS AgentD Process Inspection

Status: todo

Enable fail-closed AgentD replacement recovery on macOS without trusting optional
host tools or coarse process metadata.

## Candidate Direction

- Give AgentD its own owner-only `agentd-control.sock`; do not reuse a session-host
  socket or durable session journal as AgentD identity.
- Pass a per-launch control secret through stdin or an inherited descriptor, never
  argv, and use an authenticated nonce challenge bound to launch ID and generation.
- Ask the proven AgentD to terminate itself so no external signal can hit a reused
  PID; preserve all session-host processes.
- Wait for the AgentD kernel lock to become acquirable before starting its replacement.
- If the AgentD does not answer, fail closed. Forced recovery of a hung process still
  requires trustworthy native process inspection.

## Scope

- Decide whether cooperative-only recovery is sufficient on macOS.
- If forced recovery is required, ship and verify a native inspector/helper that
  proves process birth, executable identity, and advisory-lock ownership.
- Integrate the selected proof with replacement, termination, adoption, and macOS
  tests.
