# Read Session Journals

Status: todo
Detailed plan: ../../../2026-09-02-agentd.md
Depends on: native session-host journal contracts and
../../../current-work/native-session-host/journal-retention/TASK.md

Implement the JVM reader for the session-host journal without coupling AgentD
protocol versions or semantics to the journal format.

## Scope

- Read closed and active segments, uncompressed tails, and compressed blocks
  after a session-scoped event ID cursor.
- Ignore incomplete tail data, verify framing and checksums, cross rotation,
  and recover every complete record preceding damage.
- Expose the first and last available event IDs and return an explicit cursor
  gap when retention deleted requested history.
- Preserve unknown event types, payload bytes, and optional trailing fields
  exactly while skipping structures the current AgentD does not interpret.
- Test segment and block boundaries, partial and corrupt tails, rotation,
  retention gaps, concurrent writers, unknown events, and restart reads.
