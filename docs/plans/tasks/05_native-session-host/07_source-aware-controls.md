# Add Explicit Sources to Session Control Commands

Status: todo
Depends on: unified AgentD native controls (completed in `c298ad34`)
Blocks: [SERVER sequence recovery](08_server-operation-sequence-recovery.md) and
[local terminal attachment](../04_agentd/07_local-terminal/02_attach.md)
Related: [server command orchestration](../04_agentd/04_command-orchestration.md)

- Owner: codex, session source-aware-controls-701c, branch `codex/source-aware-controls-701c`, worktree
  `.worktrees/source-aware-controls-701c`, resumed 2026-09-09 23:24 Europe/Amsterdam.

Allow AgentD and a manual client to control the same live session concurrently,
including direct manual access while AgentD is unavailable. Mark every command
with its explicit source while keeping one wire contract, execution path, and
journal.

## Scope

- Replace the current control-operation payload with one canonical layout that
  carries `source: SERVER | MANUAL` beside `operationSequence`. Preserve the
  full unsigned `u64` range; do not encode source in reserved bits or assign
  priority by numeric range.
- Keep `SERVER` ordering and deduplication scoped to the host session. Admit
  operations through an in-memory sequence high-water mark and preserve exact
  opaque server command envelopes in `COMMAND_RESULT`. Attempt durable result
  append after each effect; missing results remain unknown and do not authorize
  replay.
- Give `MANUAL` no high-water mark, ordering state, deduplication, or durable
  retry identity. Its `operationSequence` is only a live response-correlation
  value: every delivered valid frame executes, including another frame with the
  same source and sequence on the same or a different connection. A manual
  client sends each intended effect once and does not retry an uncertain
  delivery after disconnect.
- Admit both sources into the same existing serialized PTY/process-control
  execution path after source-specific sequence validation. Journal `eventId`
  remains the common order; disconnect creates no `MANUAL` cleanup or retained
  source state. Preserve the existing termination ordering exception.
- Make all existing operations available to both sources: `INPUT`, `RESIZE`,
  `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL`. Source changes sequence semantics,
  not the operation allowlist. The first local-terminal client may choose to
  emit only input/resize and no ACK, but that is client policy rather than a
  protocol restriction; a delivered manual ACK has the normal retention
  effect.
- Record source and operation sequence in `COMMAND_RESULT`, together with the
  exact accepted source envelope: the opaque server command envelope for
  `SERVER`, and the exact received control-operation payload for `MANUAL`.
  Preserve outcome and detail. Manual events remain part of replicated session
  history, but later server recovery ignores their sequences.
- Preserve the existing delivery boundary: `RECEIVED` confirms admission, not
  durable effect completion. A lost receipt does not cancel an admitted effect;
  effect failures produce `COMMAND_RESULT`, and result-append failures leave the
  effect outcome unknown rather than authorizing replay.
- Update Rust controls, Java codecs/models, protocol fixtures, journal readers,
  and every existing consumer together. The replacement layout is the only
  current layout: remove the old schema constants, readers, writers, fixtures,
  documentation, and single-sequence path. Do not add migration, fallback,
  compatibility shims, or parallel legacy implementations.
- Reconcile the local-terminal and command-orchestration designs/plans with
  these contracts, including one-shot manual delivery and exclusion of manual
  sequences from server recovery.

## Acceptance

- Two live control connections can interleave `SERVER:42`, `MANUAL:1`,
  `SERVER:43`, and `MANUAL:2` without collisions or false stale rejections.
  Effects and journal records retain one host-assigned order.
- Repeating a `SERVER` sequence is rejected by the session high-water mark;
  repeating a `MANUAL` sequence executes again, including across simultaneous
  or reconnected control clients. No manual high-water or duplicate-removal
  state exists.
- `INPUT`, `RESIZE`, `SIGNAL`, `TERMINATE`, and `ACK_JOURNAL` use the same
  validation, execution, and result-journaling path for both sources. Tests
  retain meaningful coverage of the existing termination ordering exception.
- Each result attributes its source and full unsigned sequence, including values
  above `i64::MAX`, and preserves the exact source envelope/control payload.
- Real-host and Java/Rust fixture tests cover interleaved sources, repeated
  manual delivery, server stale rejection, all operation types, result
  attribution, effect failure, lost receipt, and result-append failure using
  only the replacement wire and journal layouts.

## Boundary

This task owns source-aware control and journal contracts plus their existing
consumers. Recovering the next `SERVER` sequence after AgentD reconnect belongs
to `08_server-operation-sequence-recovery.md`. Terminal UI construction,
process ownership, termination coordination, and physical retention remain
separate.

---

## Source-Aware Session Controls Implementation Plan

**Goal:** Let `SERVER` and `MANUAL` commands share the native session-control path while applying replay protection only to server commands.

**Architecture:** Replace operation payload schema 2 with one current schema-3 layout carrying an explicit source. Keep the existing host-owned execution serialization and journal order; branch only at admission, where `SERVER` advances the session high-water mark and every valid `MANUAL` delivery is admitted without retained sequence state. Persist a source-aware `COMMAND_RESULT` and consume the same fixtures from Rust and Java without a legacy codec path.

**Tech Stack:** Rust 2024, Unix domain sockets, CBOR Sequence journals, Java 21, JUnit 5, AssertJ, Maven, Make.

**Build integration:** the integrated root `session-host-test` Make target

---

### Behavioral delta

**Current model:** Every native operation uses schema 2, carries a nonempty opaque server envelope, and shares one session-wide sequence high-water mark. `COMMAND_RESULT` contains sequence, server envelope, outcome, and detail. AgentD encodes only this server-shaped model, and its journal codec leaves command results opaque.

**Required behavioral delta:** Schema 3 adds `SERVER = 1` and `MANUAL = 2`. Server commands retain the current high-water semantics; manual commands have no high-water, deduplication, replay, reconnect, or cleanup state. Both sources can issue all five existing operations and share the current effect and result paths.

**Preserved behavior and invariants:** The 32-byte control frame, response schema, sequence correlation, unsigned `u64` range, `RECEIVED` admission boundary, operation coordinator, ordinary-effect serialization, `TERMINATE` bypass, effect behavior, journal `eventId` ordering, retention rules, and result-append failure behavior remain unchanged.

**Chosen implementation:** Extend the existing operation payload and `COMMAND_RESULT`; do not add another control message family, dispatcher, execution lane, ledger, or persistent state. Add only source/outcome value types needed by both Java control and journal models.

### Canonical replacement layouts

Operation request frames use payload schema `3` only. The header sequence remains `operationSequence`.

```text
u16 source                 # 1 SERVER, 2 MANUAL
u16 reserved               # zero
u32 serverEnvelopeLength
serverEnvelopeLength bytes # nonempty for SERVER, zero for MANUAL
command-specific effect
```

For a successful decode, retain both the typed effect and the result envelope:

- `SERVER`: the result envelope is the exact opaque server envelope bytes.
- `MANUAL`: the result envelope is the exact received schema-3 operation payload, including source prefix and effect.

The current journal payload is:

```text
[source, operationSequence, exactSourceEnvelope, outcome, detail]
```

No schema-1/schema-2 operation reader, writer, fixture, fallback, migration mode, or negative compatibility test remains. STATUS, responses, and unrelated payloads keep their existing schemas.

#### Task 0: Restore the root session-host test target

**Files:**

- Modify: `Makefile`

**Step 1: Verify that the target is absent**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: Make fails with `No rule to make target 'session-host-test'`.

**Step 2: Add the minimal root target**

Add `session-host-test` to `RUN_TEST_RESERVED_GOALS` and `.PHONY`. Make it
depend on the existing `rust-install` target and run:

```make
session-host-test: rust-install
	cd session-host && $(HOME)/.cargo/bin/cargo test --locked
```

Make the existing Maven target depend on it without changing the Maven recipe:

```make
test: session-host-test
```

Do not restore `session-host/Makefile` or add another script or wrapper.

**Step 3: Verify the dedicated target and composed ordering**

Run outside the sandbox:

```bash
make session-host-test
make -n test
```

Expected: the Rust suite passes, and the dry run shows the Rust test command
before the unchanged Maven test command.

#### Task 1: Replace the Rust wire and journal model

**Files:**

- Modify: `session-host/src/protocol.rs`
- Modify: `session-host/src/journal.rs`
- Modify: `session-host/src/bin/generate_protocol_fixtures.rs`
- Modify: `session-host/tests/support/journal.rs`
- Modify: `session-host/protocol/fixtures/command-events-v1.hex`
- Create: `session-host/protocol/fixtures/control-source-aware.bin`
- Delete: `session-host/protocol/fixtures/control-idempotency-v2.bin`
- Delete: `session-host/protocol/fixtures/control-v1.bin`

**Step 1: Add the minimal compilable model skeleton**

Add `OperationSource::{Server, Manual}` with wire codes `1` and `2`. Extend `OperationControlPayload` with `source` and `result_envelope`, and extend `JournalEvent::CommandResult` plus `encode_command_result` with source. Change the operation encoder signature to accept source and an optional server-envelope slice; leave admission behavior unchanged until Task 2.

**Step 2: Add protocol tests for the new behavior**

Replace the schema-two tests in `protocol.rs` with schema-three cases that assert:

- all five effects round-trip for both sources;
- only `SERVER` requires a nonempty inner envelope;
- `MANUAL` retains the exact encoded operation payload as its result envelope;
- reserved bytes and unknown sources are rejected;
- sequences above `i64::MAX` survive both control and journal encoding;
- `COMMAND_RESULT` encodes exactly as `[source, sequence, envelope, outcome, detail]`.

Update the test journal decoder to expose source before sequence so real-host assertions do not infer fields from byte offsets.

**Step 3: Run the selected Rust test and observe the behavioral failure**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: the new source-aware assertions fail until the codec and journal encoder implement the replacement layouts; the crate must compile.

**Step 4: Implement the canonical codecs**

Decode the source prefix once, validate the source-specific envelope rule, retain exact request payload bytes only for `MANUAL`, and keep the existing effect validators source-neutral. Encode the five-field result and pass source/result envelope through `JournalEvent::CommandResult`. Do not retain a schema-2 function or branch.

**Step 5: Replace the canonical fixtures**

Rename the generator entry to `control_source_aware`, emit both sources for all five operation types, and regenerate fixtures:

```bash
cd session-host
"$HOME/.cargo/bin/cargo" run --locked --release --bin generate-protocol-fixtures
```

Delete the two superseded operation fixtures and their generator/test references. Keep `command-events-v1.hex` because journal format version 1 remains current, but replace its bytes with source-aware server and manual results.

**Step 6: Verify**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: all Rust tests pass.

#### Task 2: Apply source-specific host admission

**Files:**

- Modify: `session-host/src/platform/unix.rs`
- Modify: `session-host/tests/unix_process_host.rs`

**Step 1: Add real-host admission tests**

Add source parameters to the existing operation helpers and keep established tests explicitly `SERVER`. Add cases that:

- interleave `SERVER:42`, `MANUAL:1`, `SERVER:43`, and `MANUAL:2` and retain journal order;
- reject a repeated server sequence;
- execute repeated manual sequence `1` again on the same and another connection;
- accept a manual sequence below the server high-water mark without changing that mark;
- attribute each result to its source and exact source envelope;
- exercise all five operation decoders for each source while retaining existing effect-level tests, including manual ACK behavior and the terminate bypass.

Reuse the existing lost-receipt, effect-failure, and result-append-failure coverage by updating its frames and result assertions to the current schema. Do not add a test whose only purpose is to reject schema 2.

**Step 2: Run the host tests and observe manual stale rejection**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: the new repeated/interleaved `MANUAL` tests fail because the global high-water mark still applies to every operation.

**Step 3: Make admission source-aware in the existing handler**

Keep `handle_operation`, `OperationCoordinator`, `operation_order`, and `execute_operation_effect` as the sole production path. In the existing shared-state lock:

```text
SERVER -> reject at/below accepted_sequence_high_watermark, otherwise advance it
MANUAL -> do not read or write accepted_sequence_high_watermark
both   -> register the active operation or reject finalizing state
```

After `RECEIVED`, execute and append the same `JournalEvent::CommandResult` for either source. Do not add connection-owned or durable manual state.

**Step 4: Verify**

Run outside the sandbox:

```bash
make session-host-test
```

Expected: all Rust unit and real-host tests pass.

#### Task 3: Decode source-aware command results in the shared Java protocol

**Files:**

- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionCommandSource.java`
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionCommandOutcome.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventPayload.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventType.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/SessionEventCodec.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/CborWriter.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/SessionEventCodecTest.java`
- Create: `agent-protocol/protocol/fixtures/command-events-v1.hex`

**Step 1: Add the compilable Java payload skeleton**

Add source and outcome enums with exact native wire codes. Add `SessionEventPayload.CommandResult(source, operationSequence, sourceEnvelope, outcome, detail)` and the `COMMAND_RESULT = 0x0002` event type. Validate the native sequence exclusions, nonempty copied envelope, successful empty detail, and 4096-byte UTF-8 detail limit.

**Step 2: Add failing codec and shared-fixture tests**

Cover server/manual round trips, unsigned sequences above `Long.MAX_VALUE`, malformed source/outcome/field shapes, exact envelope preservation, and exact equality with the Rust-generated `command-events-v1.hex`. Preserve opaque unknown-event and outer-tail behavior.

**Step 3: Run the focused protocol test**

Run outside the sandbox:

```bash
make run-test MODULE=agent-protocol TEST='SessionEventCodecTest'
```

Expected: new command-result decode assertions fail until the codec recognizes event `0x0002`.

**Step 4: Implement typed encoding and decoding**

Extend the existing `SessionEventCodec` switch and payload helpers; do not add a second journal reader or CBOR implementation. Add the narrow package-private unsigned-`long` CBOR support needed to preserve the full `u64` sequence.

**Step 5: Verify**

Run outside the sandbox:

```bash
make run-test MODULE=agent-protocol TEST='SessionEventCodecTest'
```

Expected: the focused test passes.

#### Task 4: Replace the AgentD operation model and codec

**Files:**

- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/ControlCommand.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/NativeControlCodec.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/session/package-info.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/NativeControlCodecTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionControlClientTest.java`

**Step 1: Change the model signatures without adding compatibility constructors**

Give every operation record a mandatory `SessionCommandSource` and `Optional<ProtocolBytes> serverCommandEnvelope`. Require a present, nonempty envelope for `SERVER` and an empty optional for `MANUAL`. Keep STATUS source-free. Update all real callers directly; do not retain the old constructor family.

**Step 2: Add current-layout codec tests**

Replace the old fixture assertions with `control-source-aware.bin`. Verify both sources for all five operations, schema `3`, zero reserved bytes, absent manual server envelope, full unsigned sequences, exact effect bytes, and unchanged response decoding. Keep the existing single-exchange ambiguous-delivery test for manual commands to prove the client does not retry.

**Step 3: Run the focused AgentD tests**

Run outside the sandbox:

```bash
make run-test MODULE=agentd TEST='NativeControlCodecTest,SessionControlClientTest'
```

Expected: new schema/source assertions fail until `NativeControlCodec` writes the replacement prefix.

**Step 4: Implement the current encoder only**

Use one `OPERATION_PAYLOAD_SCHEMA = 3` constant and one operation-frame function for both sources. Write source, reserved zero, optional server-envelope length/bytes, and the unchanged effect. Remove schema-2 names and text from AgentD; do not decode or emit the old layout.

**Step 5: Verify**

Run outside the sandbox:

```bash
make run-test MODULE=agentd TEST='NativeControlCodecTest,SessionControlClientTest'
```

Expected: focused tests pass and `rg -n -i 'schema.?2|schema two|control-idempotency-v2' agentd` returns no matches.

#### Task 5: Prove Java/Rust interoperability and perform current-only cleanup

**Files:**

- Modify: `agentd/src/test/java/pro/deta/orion/agentd/session/NativeControlLivePeerTest.java`
- Modify as required by complete replacement: `session-host/src/protocol.rs`
- Modify as required by complete replacement: `session-host/tests/unix_process_host.rs`

**Step 1: Extend the live-peer test**

Send server and manual operations through `SessionControlClient`, including repeated manual sequences and a server stale rejection. Decode `COMMAND_RESULT` with `SessionEventCodec.decodeKnownPayload` and assert source, unsigned sequence, exact server envelope, exact manual operation payload, outcome, and detail. Keep ACK and TERMINATE available to both sources; client policy in the future terminal is outside this task.

**Step 2: Run cross-language tests**

Run outside the sandbox:

```bash
make session-host
make run-test MODULE=agentd TEST='NativeControlLivePeerTest'
```

Expected: the Java client interoperates with the newly built native host and the live journal decodes through the shared typed codec.

**Step 3: Remove legacy-only assertions**

After the replacement behavior passes, remove remaining tests that exist only to exercise or reject the old operation payloads. Do not add source-scanning tests; use repository search as a review check.

**Step 4: Run complete verification**

Run outside the sandbox:

```bash
make test
mvn verify -Pdev -T 4
git diff --check
```

Expected: every command succeeds. Inspect the complete branch diff and confirm there is one source enum per language, one operation decoder/encoder per language, one host handler/effect path, only the server high-water mark, no new persistent state, and no schema-2 operation reference.

### Documentation alignment

Update `session-host/protocol/README.md`, the reconciled native-control design/record, and affected
AgentD/local-terminal/command-orchestration plans to describe only the current source-aware contract.
