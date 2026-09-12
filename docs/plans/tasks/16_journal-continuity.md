# Rework Verifiable Session Journal Continuity

Status: todo
Depends on: completed AgentD journal relay (04/02) and server and AgentD MVP
acceptance (03/06 and 04/08). This is follow-up work, not an MVP gate.

## Problem

Session EventIds are unique and strictly increasing, but are derived from a
monotonic clock and need not be consecutive. The current server replication and
AgentD reader paths infer a gap when a durable cursor is numerically below the
first locally available EventId. That comparison cannot distinguish normal ID
spacing from a lost journal record; the server journal read API has the same
problem. The MVP relay must remove these false claims; it does not guarantee
detection of every lost record.

## Requirements

- Decide with the user whether exact detection of lost records is required and
  which continuity evidence should cross the native, AgentD, and server boundary.
- Preserve AgentD as a stateless relay and server storage as the authoritative
  long-term copy. Never infer a missing record solely from an EventId difference.
- If a continuity guarantee is chosen, make the server validate it before
  advancing its durable cursor or authorizing local retention.
- Define behavior for reconnect, host retention, missing or corrupt segments,
  partially written tails, and existing persisted journals.

## Design and Implementation Plan

1. Compare a consecutive record ordinal, an explicit predecessor link, and
   weaker evidence from the existing journal layout. Record the chosen contract
   and its compatibility consequences in this task before changing code.
2. Implement only the selected continuity mechanism and remove superseded gap
   inference in the same change.
3. Exercise normal nonconsecutive EventIds, actual loss before and after server
   commit, reconnect, restart, retention, and unaffected sessions.

## Acceptance

- Normal EventId spacing is never reported as lost history.
- Any reported missing record has evidence defined by the selected contract;
  the server never acknowledges unverified history as durable.
- AgentD has no persisted replication cursor, journal copy, or recovery ledger.
