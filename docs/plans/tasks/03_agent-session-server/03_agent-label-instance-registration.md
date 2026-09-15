# Replace AgentId with unique labels and agent instance registration

- Owner: codex, session 01a09f52-b1d0-7522-bbed-2c6c389576b6, branch `codex/agent-label-instance-b1d0`,
  worktree `.worktrees/agent-label-instance-b1d0`, paused 2026-09-15 08:02 Europe/Amsterdam;
  next: integrate reviewed commit `cef0fad5ca41fcb7b277f11711ee98247945f9e7` after user authorization.

## Required result

The agent runtime begins with an `AgentInstance`: every AgentD process launch
creates a fresh `agentInstanceId`. Remove the separate `AgentId` concept from
the production model, launch arguments, protocol, and real repository consumers.

`agentLabel` is the unique name of one logical agent within one Orion server.
It is not a group of agents. The server owns label registration, session
ownership, and the current instance binding. AgentD presents the label when
registering, together with its instance ID and a server-issued startup token.
The label alone grants no authority.

At every instant, a label has at most one registered, authoritative instance.
An ordinary concurrent registration for an occupied label is rejected. Only an
explicitly authorized restart may atomically replace its current instance.

## Design and preserved invariants

- Keep the label stable across AgentD process restarts. Define one validation
  and equality rule for labels and apply it to issuance, registration, storage,
  and lookup. Label renaming is outside this task.
- The server issues a bounded-lifetime, single-use startup token for an exact
  label. AgentD submits `agentLabel`, `agentInstanceId`, and that token during
  registration; a mismatch is rejected without acquiring the label.
- Distinguish initial registration from authorized replacement. For restart,
  bind the startup authorization to the expected current registration, reusing
  existing launch/generation mechanisms where suitable. A delayed token must
  not replace an instance that superseded its intended predecessor.
- Define token issuance and delivery through the existing server/provisioner
  launch path. Restarting AgentD requires a new startup authorization; the old
  process's reconnect token must not authorize a new instance.
- Atomically consume a valid startup token and establish the instance binding.
  For replacement, revoke the previous instance and its reconnect authority at
  the same ownership transition. Issuance alone must not register a new instance.
  Specify recovery if registration succeeds but the response is lost, without
  permitting token replay or two authoritative instances.
- Reconnect retains the same instance ID and uses a separate reconnect token
  bound to that instance and label. Connection IDs remain disposable transport
  identities. Reject messages and replication from superseded instances,
  including already-open streams and delayed callbacks.
- Keep sessions associated with their label across instance replacement.
  Authenticate both command routing and journal replication against the current
  label/instance binding and session ownership. Reuse the existing registry;
  do not introduce a second ownership model.
- AgentD remains stateless with respect to durable identity, credentials,
  command completion, and replication cursors. Its restart neither restarts nor
  stops session-host processes. Journal resume still uses committed server data.
- Persist enough server-side registration and token-consumption state to prevent
  duplicate registration and revoked-credential resurrection after server
  restart or a failed/ambiguous storage operation. Preserve existing credential
  expiry, redaction, process identity checks, and fencing guarantees.
- Specify the effect on existing persisted registrations and credentials before
  changing their format. Use one canonical production model; do not retain an
  AgentId alias, dual authentication path, or compatibility mode.

### Target-format decision

Implement the target label/instance format directly. Existing AgentId-based
persisted formats are unsupported and must fail explicitly without deleting or
rewriting their files. No migration, conversion, transition mode, or legacy
reader is required. Old credentials must never acquire authority in the new
model. This applies to agent registration, session ownership, and command-ledger
representations affected by the identity replacement.

Retain the existing recovery approach for a lost initial WELCOME: the
provisioner obtains a fresh authorized launch against the current registration.
The consumed startup token remains unusable. Reconnect within a running process
keeps its instance ID and uses its separate instance-bound credential.

## Scope and dependencies

Build on the implemented agent registry, launch authorization, authenticated
connections, remote provisioning/recovery, AgentD handshake, and session
registry. Trace their current wiring before replacing the identity contract.
The affected consumers include `agent-protocol`, `agent-session-server`,
`agent-provisioning`, `agentd`, HTTP adapters, fixtures, and their tests and docs.

Changes to `operationSequence`, command-envelope comparison, journal event
identity, label grouping, and label renaming are outside its scope.

## Implementation plan

1. Trace AgentId consumers and durable representations, including startup and
   reconnect credentials, recovery attempts, session ownership, and HTTP streams.
   Identify which existing launch/generation fields are still required.
2. Define the label/instance registration transitions, initial and restart token
   issuance, atomic replacement, and failure recovery using existing owners.
3. Replace the identity model across server storage, authorization, provisioning,
   AgentD launch/handshake, routing, and replication in one coherent production
   path. Update every real consumer and relevant protocol fixture.
4. Verify preserved session recovery and credential guarantees through behavior
   tests, and update architecture and protocol documentation to the final model.

## Acceptance criteria

- A valid token registers one fresh instance under its exact unique label;
  different labels can register independently.
- Two concurrent ordinary registrations for one label cannot both succeed.
  Wrong-label, expired, reused, or unauthorized-replacement tokens are rejected.
- An authorized restart changes the instance ID while preserving the label and
  its sessions. Old and new instances are never authoritative simultaneously.
- Stale replacement tokens, old reconnect tokens, old streams, and delayed old
  instance messages cannot reclaim authority or mutate the replacement's state.
- Reconnect of the current process preserves its instance ID and journal resume;
  a new process cannot use that reconnect token as startup authorization.
- Registration races, lost responses, persistence failures, and server restart
  do not create two owners or revive consumed tokens and revoked credentials.
- Session commands and replication reject foreign-label and superseded-instance
  access; existing host processes and journal history survive AgentD replacement.
- Production consumers use the label/instance model without AgentId or a parallel
  legacy path. Tests cover both normal operation and the races/failures above.
