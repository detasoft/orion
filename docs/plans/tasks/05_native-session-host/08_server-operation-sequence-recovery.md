# Resolve SERVER Operation Sequence Recovery

Status: todo
Depends on: source-aware controls (completed in `09ed12c0` and `b3c8953c`)
Coordinates with: [AgentD journal sync](../04_agentd/02_journal-sync.md) and
[server command orchestration](../04_agentd/04_command-orchestration.md)

## Goal

Let a stateless replacement AgentD safely allocate the next `SERVER`
`operationSequence`, including when the live host admitted an earlier operation
but could not append its `COMMAND_RESULT`.

## Current Model

The live `session-host` owns an in-memory accepted `SERVER` sequence high-water
mark. It advances that value during admission, before `RECEIVED`, the operation
effect, and the result append. The journal contains only results whose append
succeeded, so the maximum recorded sequence is a lower bound and cannot reveal
every admitted operation.

The server owns the durably committed journal prefix and its `eventId` cursor.
It can derive the maximum `SERVER` operation sequence represented by
`COMMAND_RESULT` records in that prefix. AgentD can derive the corresponding
maximum from the local suffix after the server cursor. Neither observation is
an admission authority.

Journal retention has a separate pair of values:

- the server's durable journal `eventId` cursor; and
- the host's durably applied `ACK_JOURNAL` event watermark.

Those event IDs determine whether AgentD owes the host another retention
acknowledgement. They must never be converted to or substituted for an
`operationSequence`.

## Required Behavioral Delta

- Expose one atomic live-host recovery operation that fences older local
  control connections and returns both the accepted `SERVER` sequence
  high-water mark and the applied journal-acknowledgement watermark.
- Combine that authoritative host observation with the server-durable prefix
  and local journal suffix without interpreting a missing result as permission
  to replay an uncertain command.
- Allocate the next unsigned sequence strictly above every admitted or recorded
  `SERVER` operation. Keep `MANUAL` sequences outside this recovery state and
  keep `ACK_JOURNAL` in the shared `SERVER` allocation order.
- Pause only the affected session when the host is unavailable, journal
  observations are incomplete, recovery values conflict, or the sequence space
  is exhausted.

## Preserved Behavior and Invariants

- AgentD remains stateless and persists neither a cursor nor a sequence.
- The host keeps no durable command intent or replay ledger.
- An empty `RECEIVED` remains transient admission evidence; durable completion
  still comes only from a replicated `COMMAND_RESULT`.
- A missing result remains unknown. Recovery never retries that envelope.
- Exact opaque server envelopes, source separation, unsigned `u64` values,
  sequence gaps, and the existing child-effect ordering remain unchanged.
- The server journal remains the sole replication cursor authority. The host's
  retention watermark is only deletion permission.

## Considered Approaches

### Recorded maxima only

Take the maximum sequence in the server prefix and local suffix and add one.
This loses an admitted operation whose result append is pending or failed, so a
replacement AgentD can reuse its sequence. Rejected.

### Read-only host status

Expose the host high-water mark through `STATUS`, then allocate above it. A
request already buffered on an older connection can still be admitted after
the status snapshot and race the replacement allocator. Rejected because the
observation and takeover are not atomic.

### Durable AgentD or host intent ledger

Persist every allocation or admission before the effect. This adds a second
command authority, crash-consistency rules, retention, and takeover
coordination. Rejected.

### Atomic live-host recovery barrier

Selected. The host already owns admission and its high-water mark. A dedicated
barrier fences connections accepted before the barrier, validates the recorded
lower bound, and returns the authoritative live values under the same state
lock used by admission. It adds only incarnation-local connection fencing and
no durable command state.

## Recovery Barrier Contract

Add schema-1 `CLAIM_SERVER_CONTROL` and `SERVER_CONTROL_CLAIMED` native control
messages. They are recovery messages, not operation controls: they do not have
an operation sequence, apply a child effect, or append a journal record. The
ordinary frame sequence remains request/response correlation only.

The request payload is one little-endian unsigned `u64`:

```text
observedServerSequenceFloor; 0 means no recorded SERVER result
```

The response payload is two little-endian unsigned `u64` values:

```text
acceptedServerSequenceHighWatermark; 0 means no admitted SERVER operation
acknowledgedJournalEventId;           0 means no applied ACK_JOURNAL watermark
```

Operation sequences remain restricted to `1..u64::MAX - 1`. Event ID zero is
already absent from a valid journal, so zero is an unambiguous optional-value
encoding in this fixed native payload.

Each accepted control connection receives a monotonically increasing,
host-incarnation-local ordinal. Under the shared admission lock, a successful
claim:

1. rejects a claim made on a connection older than the active fence;
2. rejects an observed sequence floor above the host's accepted high-water
   mark, including a nonzero floor when the host has no accepted sequence;
3. advances the active `SERVER` connection fence to the claiming connection;
4. snapshots the accepted sequence and durable retention watermark; and
5. returns both values before releasing the claim to AgentD.

A `SERVER` operation is admitted only from a connection whose ordinal is at or
above the active fence. Thus an older request admitted before the barrier is
included in the returned high-water mark, while an older request reaching
admission afterward is rejected without an effect. `MANUAL` operations ignore
the fence and do not change the server high-water mark.

Connection ordinals are neither wire identity nor durable state. Exhausting the
ordinal space makes new claims fail closed. The AgentD process lock and its
per-session recovery gate ensure that only the current AgentD opens new command
connections after claiming; a claim is not authentication for untrusted local
clients.

## Recovery Algorithm

For each session after AgentD reconnect or restart:

1. Receive the server's durable journal `eventId` cursor and optional maximum
   `SERVER operationSequence` represented by its committed prefix.
2. Scan the local journal strictly after that event cursor through a stable
   observed tail. Reject a gap or corruption and derive the maximum recorded
   `SERVER` result sequence while excluding every `MANUAL` result.
3. Take the unsigned maximum of the server-prefix and local-suffix sequences,
   or zero when neither exists, and send it as the claim's observed floor.
4. Accept the claim only when the host returns an accepted high-water mark at
   or above that floor. A lower host value indicates lost admission authority,
   a wrong host incarnation, or inconsistent history and pauses the session.
5. Initialize the in-memory allocator to the unsigned successor of the host
   high-water mark. If the mark is `u64::MAX - 1`, pause the session rather than
   wrapping or using reserved values.
6. Compare the server's durable journal cursor with the returned host retention
   watermark. When the server cursor is higher, enqueue one source-aware
   `SERVER` `ACK_JOURNAL` through the same allocator. When the host watermark is
   higher, report inconsistent durable state and pause the session.

The server-prefix sequence is recovery evidence, not a separately persisted
allocator. AgentD advances its allocator in memory as it dispatches commands.
Every later reconnect repeats the barrier after quiescing new delivery for that
session. Journal upload may continue independently, but no command or
`ACK_JOURNAL` passes the recovery gate first.

An ACK result alone must not create an endless acknowledgement cycle. A
durable cursor advance caused solely by the `COMMAND_RESULT` of the most recent
`ACK_JOURNAL` does not schedule another ACK. A later substantive durable event
may be acknowledged together with any intervening ACK result.

## Failure Semantics

- Missing or malformed claim responses, timeout, connection ambiguity, a stale
  claiming connection, and an unavailable host leave allocation unresolved.
- A journal gap or corrupt suffix prevents calculation of the observed floor.
- A server-prefix or local-suffix sequence above the host high-water mark is an
  integrity/incarnation mismatch, not permission to raise host state.
- A host retention watermark above the server cursor is a durable-state
  inconsistency, not permission to lower retention state or invent history.
- These failures pause one session and produce a bounded diagnostic. They do
  not stop heartbeat, discovery, journal upload for healthy sessions, or the
  hosted process.

## Verification Design

Native tests cover an operation admitted before the barrier, a buffered older
connection rejected after it, post-barrier command connections, unaffected
`MANUAL` operations, a floor mismatch, absent values, retention watermark
reporting, unsigned values above `i64::MAX`, and both sequence and connection
ordinal exhaustion.

Java codec tests freeze the exact claim frames and unsigned values. Live-peer
tests prove the Java client can claim a real host, observe both watermarks, and
cannot use an older connection for a later `SERVER` admission.

Recovery tests combine different server-prefix, local-suffix, and live-host
maxima; incomplete and corrupt scans; an admitted operation with no result;
retained-prefix gaps; ACK catch-up; ACK-only cursor advancement; and isolated
session failure. The replacement allocator must never become ready from
recorded maxima alone.

## Implementation Plan

### Task 1: Add the native recovery barrier

**Files:**

- Modify: `session-host/src/protocol.rs`
- Modify: `session-host/src/platform/unix.rs`
- Modify: `session-host/src/bin/generate_protocol_fixtures.rs`
- Modify: `session-host/tests/unix_process_host.rs`
- Modify: `session-host/protocol/README.md`
- Create: `session-host/protocol/fixtures/server-sequence-recovery.bin`

1. Add failing protocol and live-host tests for the claim request/response,
   optional zero encodings, unsigned comparisons, and connection fencing.
2. Run `make session-host-test` and confirm the new tests fail because the
   claim messages and fence do not exist.
3. Add the two message IDs, fixed payload codecs, connection ordinals, active
   fence, and atomic claim/admission checks. Reuse the existing shared state
   lock and retention owner.
4. Generate and inspect the new fixture, document the implemented wire and
   failure contract, then rerun `make session-host-test`.

### Task 2: Align the Java native-control client

**Files:**

- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlCommand.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlResult.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/NativeControlCodec.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionControlClient.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/package-info.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/NativeControlCodecTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionControlClientTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/NativeControlLivePeerTest.java`

1. Add failing codec and client tests for the observed floor, both returned
   watermarks, malformed responses, and unsigned values.
2. Run the focused tests with:

   ```bash
   sequence_tests='pro.deta.orion.agentd.session.NativeControlCodecTest,'\
   'pro.deta.orion.agentd.session.SessionControlClientTest'
   make run-test MODULE=agentd TEST="$sequence_tests"
   ```
3. Add one claim command and one claimed result to the existing sealed models;
   encode and decode the fixed payload without adding another transport or
   recovery service.
4. Extend the live-peer test against the real host fixture and run
   `make run-test MODULE=agentd TEST='pro.deta.orion.agentd.session.NativeControlLivePeerTest'`.
5. Run `make test` because this checkpoint changes both JVM and Rust sources.

### Task 3: Reconcile AgentD recovery consumers

**Files:**

- Modify: `docs/plans/tasks/04_agentd/02_journal-sync.md`
- Modify: `docs/plans/tasks/04_agentd/04_command-orchestration.md`

1. Replace recorded-maxima allocation language with the atomic claim and
   recovery-gate algorithm defined here.
2. Make journal sync compare the server cursor with the claim's host retention
   watermark and route any required ACK through the shared allocator.
3. Make command orchestration calculate the observed sequence floor, claim the
   host, reject inconsistent values, and enable its lane only from the returned
   host high-water mark.
4. Inspect the documentation diff for one recovery authority, no AgentD cursor
   or ledger, no replay of uncertain operations, and no ACK feedback loop.

## Acceptance

- After an admitted operation loses its result, a replacement AgentD allocates
  above the host's authoritative accepted value without replaying the uncertain
  command, or explicitly pauses that session.
- An older buffered connection cannot admit a `SERVER` operation after the
  recovery snapshot used by the replacement allocator.
- Recovery remains correct with retained-prefix gaps, interleaved `MANUAL`
  records, absent values, and unsigned values above `i64::MAX`.
- `ACK_JOURNAL` uses the recovered shared allocator while its event watermark
  remains distinct from both operation-sequence observations.
- Missing or inconsistent recovery authority cannot authorize another server
  operation.
- No AgentD durable sequence state, host durable intent ledger, compatibility
  path, or second allocator is introduced.
