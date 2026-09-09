# Durable Agent and Launch Records

Task: `current-work/agent-session-server/control-and-registries/agent-and-launch-records`
Pool: [Server agent registration](current-work/agent-session-server/control-and-registries/TASK.md)
Design: [Server-launched identity](2026-09-02-agentd-server-launched-identity-design.md)

This ordinary implementation plan is owned by the review orchestrator on
`main`. The worker reports material gaps before changing this plan.

## Verified Current Model

- `agent-protocol` owns AgentId, AgentGeneration, AgentLaunchId,
  AgentInstanceId, MachineInfo, and the HELLO metadata and credential contract.
- `agent-session-server` owns durable session journals but has no durable
  agent identity or launch records. Journal file operations are coupled to
  segment identity, append recovery, and journal ownership.
- `LocalKeyMaterialContentStore` demonstrates temporary-file force, atomic
  replacement, parent-directory force, and locking. Its API and file-security
  helpers are domain-specific. The Native Git key-material adapter publishes
  by expected Git ref, but would add unrelated dependencies here.
- Provisioning already accepts server-issued identity through
  `AgentdLaunchRequest` and `AgentdLaunchAttemptSource`. Its process record
  describes the remote process; it is not the server's authentication store.
- Authentication and runtime wiring are later leaves. Durable metadata must
  never be interpreted as a live physical connection.

## Required Delta and Minimal Implementation

Add one concrete filesystem owner inside `agent-session-server`, with immutable
record snapshots and the narrow durable operations needed by subsequent
authentication and provisioning work. Reuse protocol identity and metadata
types. Keep encoding and file-operation helpers private or package-local.

Use the existing JDK atomic-file pattern with bounded, versioned records.
Prefer one complete record per canonical AgentId filename: identity, current
launch, credential state, and observation commit together. Do not turn session
journals or key-material storage into a generic database abstraction. No new
module, dependency, transport message, runtime configuration, machine
configuration store, scheduler, or additional storage backend is required.

The necessary new concepts are the durable agent/launch record and its
filesystem owner. Nested immutable values and a small failure vocabulary may
express the record's invariants; avoid interface/implementation pairs and
parallel DTOs without a concrete boundary.

## Record and Operation Contract

- Creating a stable AgentId is distinct from allocating its first launch.
  A never-launched agent has no current launch; positive AgentGeneration starts
  at one. Duplicate creation must not reset existing state.
- Allocate a strictly higher generation and a new LaunchId atomically while
  revoking every prior permit and reconnect token. Reject generation overflow.
  Concurrent allocations must not lose updates or reuse a generation.
- Keep the current launch's progress sufficient for the approved recovery,
  starting, and failure flow. Guard launch-specific updates by generation and
  LaunchId so delayed work from an old launch cannot modify its replacement.
- Support installing a bounded-lifetime permit hash after recovery has made
  launch safe. Permit consumption and installation of the reconnect-token
  hash/expiry must be one durable operation, conditional on the current launch,
  expected unconsumed permit, and expiry.
- Support current-token verification/conditional renewal without reviving a
  superseded generation or an already expired token. The later authentication
  leaf owns random credential issuance, hashing of incoming credentials,
  protocol-version checks, policy durations, and WELCOME.
- Store credential digests only, with immutable bytes and an explicit digest
  size/algorithm contract. Do not persist or log HELLO/authentication objects,
  raw permits, raw reconnect tokens, or their plaintext representations.
- Persist last observed instance, agent version, capabilities, machine
  information, and observation time through launch-guarded updates. Preserve
  those facts on restart; a recorded successful launch or observation is
  historical evidence, not connection authority.
- Missing records, stale/invalid conditional updates, conflicts, and storage
  failures must be distinguishable using a small consistent outcome model.
  Avoid an unrestricted save operation that bypasses generation or credential
  invariants. Keep SSH settings and machine administration outside this record.

## Atomicity, Ownership, and Recovery

The approved design uses one server. Serialize operations under one live store
owner and reject a second cooperating owner of the same canonical root using a
lifetime file lock. Test close/reopen and alias contention. External processes
that deliberately bypass the lock are outside the contract; distributed
coordination is not required.

Write a complete bounded snapshot to a unique temporary file in the target
directory, force its contents, atomically replace the target, and force the
directory before returning success or making the result usable by callers.
Persist newly created directory entries as well. Do not silently fall back to
a non-atomic move or claim durability on an unsupported filesystem.

A failure before publication returns failure without publishing the candidate.
A move or post-replacement durability failure may have an indeterminate disk
outcome: report it explicitly, return no successful launch/credential result,
and prevent further reads or mutations through stale live state. The smallest
safe response is to invalidate that owner until close/reopen. Recovery must
read and establish durability of the selected on-disk record before serving it;
never pretend an indeterminate update definitely rolled back.

Recovery accepts only complete validated records of the supported format.
Bound record bytes, strings, and collection counts before allocating from file
data; reject malformed, truncated, mismatched-identity, or unsupported data.
Incomplete temporary files must not become committed records. Keep recovery
local; do not introduce a second journal or an in-memory replication protocol.

## Validation

The implementation worker owns tests, all outside the sandbox.

- Cover create, duplicate create, update, close/reopen, metadata retention,
  missing/invalid state, and independent agents.
- Exercise concurrent launch allocation, stale updates after replacement,
  generation overflow, credential revocation, and concurrent permit consumption
  with exactly one committed reconnect credential.
- Cover expiry boundaries and token renewal races with generation replacement.
- Inject failures before file force, before publication, and after replacement
  before directory durability. Verify caller outcomes, poisoned-owner behavior,
  restart recovery, and that no uncommitted credential is reported usable.
- Cover truncated/corrupt records, size bounds, ignored temporary files, and
  exclusive-root ownership/release. Use narrow `@TestOnly` hooks as needed.
- Use `make run-test MODULE=:agent-session-server TEST='<test-locator>'`,
  `mvn verify -Pdev -T 4`, and post-commit `make test` under AGENTS.md.
  Known unrelated Git-configuration fixture and SSH PTY failures have separate
  upcoming task nodes and are outside this leaf.

## Review and Completion

Apply minimal-delta before implementation and perform read-only
architecture-review and architecture-simplifier checks against the final
subsystem. Preserve the atomicity, durability, generation, credential, and
ownership guarantees that justify local complexity.

Return the committed implementation for orchestrator review. After fixes and
clean review, squash the task branch, remove the completed leaf and its links,
and update next-task references to launch-and-reconnect-authentication. Keep
this ordinary plan. Do not transfer to main or remove the worktree/branch before
the per-task user gate.

