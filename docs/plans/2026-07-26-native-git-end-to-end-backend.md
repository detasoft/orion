# Native Git End-to-End Backend Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Complete the native Git server path so Orion can serve clone, fetch,
pull, and push through native wire, repository, pack, ref, and transport layers.

**Architecture:** Keep the existing `GitMinimalWireMachine` as the wire-core
integration point instead of introducing a parallel `GitWireSession`. The wire
core separates structured pkt-line handling from raw pack payload forwarding and
reports typed protocol errors. Upload-pack and receive-pack compose the native
repository backend through `GitRepository.upload()` and `GitRepository.receive()`
while transport adapters only feed bytes and request metadata into the protocol
layer.

**Tech Stack:** Java 21, Maven with `-Pdev`, Netty `ByteBuf`, JUnit 5, AssertJ,
Git CLI compatibility fixtures, existing `core/git-parser`, `core/git-common`,
`core/git-engine`, `core/git-storage`, and native Git plan documents.

---

## Related Plans

- `docs/plans/2026-05-14-native-git-wire-protocol-core.md`
- `docs/plans/2026-05-14-native-git-upload-pack-serving.md`
- `docs/plans/2026-05-14-native-git-receive-pack-serving.md`
- `docs/plans/2026-05-14-native-git-repository-backend.md`
- `docs/plans/2026-05-14-git-pack-parsing-implementation.md`
- `docs/plans/2026-05-14-git-pack-index-object-lookup.md`
- `docs/plans/2026-05-14-git-ref-storage-atomic-updates.md`
- `docs/plans/2026-05-14-native-git-transport-nio.md`
- `docs/plans/2026-05-15-git-ssh-transport-adapters.md`
- `docs/plans/2026-05-15-git-smart-http-transport-adapters.md`

This plan supersedes any older wording that creates a second connection-level
wire session beside `GitMinimalWireMachine`. The durable streaming state should
remain in the existing machine, or be renamed only as a single migration after
callers are moved.

## Task 1: Wire Core

**Files:**

- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitMinimalWireMachine.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitFixedControlFrameReader.java`
- Modify: `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/CheckpointedByteBufReader.java`
- Create or extend: wire pkt-line writer, capability parser/writer, protocol v2
  section parser, side-band, and report-status classes under
  `core/git-parser/src/main/java/pro/deta/orion/git/parser/wire/`
- Test: `core/git-parser/src/test/java/pro/deta/orion/git/parser/wire/`

**Work:**

- Extend `GitMinimalWireMachine`; do not introduce a parallel `GitWireSession`.
- Add explicit callbacks or events for DATA, FLUSH, DELIMITER, and RESPONSE_END.
- Separate structured data pkt-lines from raw pack payload forwarding.
- Add a typed wire error model instead of silent parser resets.
- Validate EOF and close for incomplete pkt-line headers and payloads.
- Add pkt-line writer support.
- Add capability parsing and writing.
- Add protocol v2 section parsing.
- Add side-band and side-band-64k helpers.
- Add report-status parsing and writing.

**Tests:**

- Fragmented header, fragmented payload, and mixed control/raw buffers.
- DATA, FLUSH, DELIMITER, and RESPONSE_END event ordering.
- Raw pack payload is streamed without being buffered as structured pkt-line data.
- Invalid length, invalid hex, oversized packet, incomplete header, and incomplete
  payload all produce typed errors.
- Writer round trips pkt-line, capability, protocol v2 section, side-band, and
  report-status fixtures.

## Task 2: Native Upload-Pack Equals Pull and Fetch

**Files:**

- Modify: `core/git-common/src/main/java/pro/deta/orion/git/common/GitRepository.java`
- Modify: `core/git-engine/src/main/java/pro/deta/orion/git/GitInternalService.java`
- Create or extend: native upload-pack service classes in the native Git server
  package chosen by the existing module boundaries
- Test: Git CLI clone, fetch, and pull compatibility tests

**Work:**

- Implement `ls-refs` advertisement for refs, tags, `HEAD`, and symrefs.
- Parse fetch requests including wants, haves, capabilities, and filters.
- Enforce access checks on wanted objects and refs before object enumeration.
- Enumerate object closure for requested tips and haves.
- Build response packs, starting with a no-delta pack builder for MVP.
- Send side-band-64k responses when negotiated.
- Integrate native serving behind `GitRepository.upload()` for the native backend.
- Prove compatibility with `git clone`, `git fetch`, and `git pull`.

**Tests:**

- Empty repository and unborn `HEAD` advertisement.
- Branch and tag advertisement with deterministic ordering.
- Authorized and rejected wanted objects.
- Clone from scratch and incremental fetch with haves.
- Filter parsing, including rejected unsupported filters until implemented.
- Git CLI clone, fetch, and pull against the native backend.

## Task 3: Native Receive-Pack Equals Push

**Files:**

- Modify: `core/git-common/src/main/java/pro/deta/orion/git/common/GitRepository.java`
- Modify: `core/git-engine/src/main/java/pro/deta/orion/git/GitInternalService.java`
- Create or extend: native receive-pack service classes in the native Git server
  package chosen by the existing module boundaries
- Test: Git CLI push compatibility tests

**Work:**

- Implement receive-pack advertisement and capability negotiation.
- Parse create, update, and delete commands.
- Reject duplicate refs and invalid object ids.
- Ingest raw packs through the native parser, delta resolver, and object store.
- Use quarantine validation so refs are not updated until the whole pack is
  verified.
- Enforce fast-forward checks, protected refs, force policy, delete policy, and
  tag policy.
- Update refs atomically with compare-and-set semantics.
- Generate report-status responses.
- Emit receive events matching the JGit-backed path.
- Prove compatibility with first push, update push, and rejected push.

**Tests:**

- Empty repository first push.
- Fast-forward branch update.
- Non-fast-forward rejection without force policy.
- Delete and tag policy rejection.
- Duplicate ref command rejection.
- Pack validation failure leaves refs unchanged.
- Receive events match existing JGit-backed event shape.

## Task 4: Native Repository Backend

**Files:**

- Modify or create: native repository implementation behind
  `core/git-common/src/main/java/pro/deta/orion/git/common/GitRepository.java`
- Extend: native ref, object, pack, index, delta, and projection plans and modules
- Test: parity tests against JGit fixtures

**Work:**

- Add a ref store with atomic updates.
- Add object lookup and storage.
- Add pack parser and pack index support.
- Add delta reconstruction.
- Add pack builder support for upload-pack responses.
- Add canonical commit, tree, blob, and tag object models.
- Update projections after receive and save operations.
- Add parity tests against JGit fixtures.

**Tests:**

- Object id, type, and serialization parity for commit, tree, blob, and tag.
- Pack parse, index lookup, and delta reconstruction fixtures.
- Atomic ref create, update, delete, and compare-and-set conflicts.
- Projection updates after receive and save.
- Native and JGit fixture parity for representative repositories.

## Task 5: Transport Integration

**Files:**

- Modify or create: native TCP or socket transport path
- Modify or create: SSH route for `git-upload-pack` and `git-receive-pack`
- Modify or create: smart HTTP discovery and POST routes for upload-pack and
  receive-pack
- Test: transport-level Git CLI compatibility tests

**Work:**

- Feed native TCP/socket `ByteBuf` input into the wire core.
- Route SSH `git-upload-pack` and `git-receive-pack` commands through the native
  services.
- Propagate `GIT_PROTOCOL=version=2` for SSH where provided.
- Implement smart HTTP discovery and POST for upload-pack and receive-pack.
- Enforce authentication and authorization at transport boundaries.
- Add request and pack size limits.
- Clean up disconnects and half-closed connections.
- Sanitize protocol and transport errors.

**Tests:**

- TCP/socket clone, fetch, pull, and push.
- SSH clone, fetch, pull, and push with protocol v2 environment handling.
- Smart HTTP discovery and POST for upload-pack and receive-pack.
- Authentication failure, authorization failure, request limit, pack limit, and
  disconnect cleanup.
- Error responses do not leak filesystem paths, credentials, or internal state.

## Execution Notes

- Keep each implementation commit focused on one layer or one externally visible
  behavior.
- Add or extend tests in the same commit as each behavior change.
- Prefer native typed failures over falling back to JGit silently.
- Keep JGit parity tests as fixtures, not as production dependencies for native
  code.
- Run routine verification with `mvn verify -Pdev`; run the commit workflow with
  `mvn test -Pdev`.
