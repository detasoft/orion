# Git Wire Architecture Simplification Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Finish the blocking Git wire migration, make object and repository
identity explicit, separate wire parsing from Orion storage, and provide one
global veto for advertised capabilities.

**Architecture:** `git-parser` owns storage-neutral wire framing, parsing, and
serialization. Each Orion transport entrypoint combines those wire primitives
with `git-native-storage` through a command-scoped server context owned by
`net/git-transport`; one immutable capability policy filters every advertised
surface at that composition boundary.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Netty `ByteBuf`, Orion
`BufferedByteInput` and `BufferedByteOutput`.

---

The order below is intentional. Canonical object IDs and direct blocking output
reduce the number of storage-coupled and resumable concepts before files move
between modules. The repository context and capability policy then build on the
final ownership boundary instead of introducing adapters that would immediately
be deleted.

### Task 1: Make `GitObjectId` own its identity

**Files:**

- Create: `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/GitObjectIdTest.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/GitObjectId.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/exchange/LegacyReceiveCommand.java`
- Modify: native-storage and transport call sites returned by
  `rg -n "toLowerCase|equalsIgnoreCase|NULL_ID|validateObjectId" git net`

1. Add tests proving that valid lowercase input is unchanged, uppercase input
   is normalized, and both forms compare and hash equally.
2. Add tests rejecting null, non-40-character, and non-hexadecimal values.
3. Add tests for a canonical `GitObjectId.ZERO` value and `isZero()`.
4. Run:

   ```bash
   mvn test -Pdev -T 4 -q -pl git/git-native-storage -am \
     -Dtest=GitObjectIdTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected: RED because the record currently accepts unvalidated spelling and
   has no zero-ID API.
5. Normalize and validate in the compact record constructor, then add the zero
   constant and query:

   ```java
   public record GitObjectId(String value) {
       public static final GitObjectId ZERO =
               new GitObjectId("0".repeat(40));

       public GitObjectId {
           value = canonicalValue(value);
       }

       public boolean isZero() {
           return equals(ZERO);
       }
   }
   ```

6. Rerun the focused test and expect GREEN.
7. Replace consumer-side normalization, zero tests, and validation only where
   the value is already typed or should become typed at that boundary. Retain
   syntax checks for raw wire text until it is converted.
8. Run the native-storage, parser, and Git transport unit tests with `-Pdev`,
   `-T 4`, `-am`, and `-Dsurefire.failIfNoSpecifiedTests=false`.
9. Commit the value-type change and its tests as one logical commit.

### Task 2: Replace resumable serializers with direct writes

**Files:**

- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransportTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/AsciiPacketUtils.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/OutputSerialization.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/AsciiPacketSequenceSerialization.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/PacketListSerialization.java`
- Delete: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/serialization/PktLineSerialization.java`

1. Extend existing byte-for-byte tests for v1 advertisement, v2 capability
   advertisement, `ls-refs`, acknowledgments, shallow information, and receive
   status so each touched output family has a stable expected wire sequence.
2. Run `GitBlockingWireTransportTest` and record GREEN as the characterization
   baseline.
3. Add a direct helper that validates one payload and writes its header,
   optional sideband byte, and payload immediately:

   ```java
   private void writeAsciiPacket(String payload, boolean sideband)
           throws IOException {
       validateAsciiPacket(payload, sideband ? 1 : 0);
       byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
       if (sideband) {
           outputSink.write(pktLineWriter.writeSidebandHeader(
                   SideBandChannel.DATA.wireValue(), bytes.length));
       } else {
           outputSink.write(pktLineWriter.writeDataHeader(bytes.length));
       }
       outputSink.write(bytes);
   }
   ```

4. Replace serializer construction with ordinary loops that call the direct
   helper and write the final control packet once. Validate all domain inputs
   before the first output byte where the current API promises validation
   before delivery.
5. Do not build `List<String>` or `List<byte[]>` merely to pass encoded output
   to another layer; domain collections supplied by callers may still be
   iterated directly.
6. Write receive-pack's nested pkt-lines directly, including the outer
   sideband header when enabled, and flush at the same response boundary.
7. Delete the four serializer types and remove `sendSerialization` plus unused
   imports/helpers. Do not remove unrelated pkt-line helpers in this slice.
8. Run:

   ```bash
   mvn test -Pdev -T 4 -q -pl git/git-parser -am \
     -Dtest=GitBlockingWireTransportTest \
     -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected: GREEN with unchanged byte assertions.
9. Commit the direct serializer replacement with its characterization tests.

### Task 3: Replace stateful response wrappers with one-shot sends

**Files:**

- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java`
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/ProtocolV2PackfileResponseTest.java`
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/ProtocolV2ShallowInfoResponseTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java`

1. Convert response tests to call one-shot `sendLegacySideBand64k`,
   `sendLegacyPack`, and `sendProtocolV2Packfile` methods while preserving all
   existing byte-for-byte expectations.
2. Add or retain tests proving producer closure after success, `IOException`,
   and runtime delivery failure.
3. Run the focused parser and transport tests and record RED for the missing
   one-shot methods.
4. Replace each `begin...` method, inner `*Response` type, and `advance()` with a
   direct send method. Own the producer at method entry:

   ```java
   public void sendLegacyPack(
           NativePackProducer producer,
           boolean sendNakBeforePack) throws IOException {
       try (NativePackProducer owned = Objects.requireNonNull(
               producer, "producer")) {
           // Write NAK when required, stream the producer, and flush.
       }
   }
   ```

5. Stream protocol v2 pre-pack sections directly in order rather than storing
   an encoded packet list on a response object.
6. Remove session-side nullable response variables and `finally` blocks; the
   invoked `send...` method is the sole producer owner.
7. Rerun the focused tests and expect GREEN.
8. Run `architecture-simplifier` in read-only mode on the resulting output path.
   Record additional safe removals as separate task nodes, or record that none
   were found. Do not remove further helpers or the `GitWireError` classification
   in this commit; server diagnostics still use structured error kinds.
9. Commit the one-shot response migration and tests.

### Task 4: Establish the parser/storage module boundary

**Files:**

- Modify: `git/git-parser/pom.xml`
- Modify: `net/git-transport/pom.xml`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitServerWireOutput.java`
- Move from `git-parser` to `net/git-transport`: `GitBlockingWireSession.java`,
  `GitNativeRepositoryService.java`, `GitNativeRepositoryAccessHook.java`,
  `NativePackfileUriSourceFactory.java`, and storage-coupled exchange types
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java`
- Modify: affected tests under `git/git-parser`, `net/git-transport`,
  `net/http-core`, and `tests/git-engine-orion-adapters`

1. Add or move characterization tests so server session behavior is owned by
   `net/git-transport`, while pkt-line and storage-neutral serialization tests
   remain in `git-parser`.
2. Move the Orion server session, repository port, access hook, packfile-URI
   factory, and native-storage exchange values to the transport module without
   changing behavior.
3. Extract `GitServerWireOutput` around the storage-dependent response methods;
   keep only raw pkt-line read/write and storage-neutral values in
   `GitBlockingWireTransport`.
4. Add an explicit `git-native-storage` dependency to `net/git-transport` and
   remove it from `git-parser`.
5. Update native TCP, SSH, Smart HTTP, Dagger, and workflow-test imports so the
   class that starts bootstrap also assembles the server session with its
   storage-backed collaborators.
6. Run focused parser, client, transport, HTTP, and Orion adapter tests.
7. Inspect the resolved client graph:

   ```bash
   mvn dependency:tree -Pdev -pl git/git-client \
     -Dincludes=pro.deta.orion.git:git-native-storage
   ```

   Expected: no `git-native-storage` dependency beneath `git-client`.
8. Inspect parser production imports:

   ```bash
   rg -n "git\.nativestorage" git/git-parser/src/main/java git/git-parser/pom.xml
   ```

   Expected: no matches.
9. Commit the module-boundary migration and tests.

### Task 5: Bind one repository context per command

**Files:**

- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitRepositoryCommand.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitBlockingWireSession.java`
- Modify: native TCP, SSH, and Smart HTTP bootstrap initiators
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryServiceTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java`

1. Add a counting `NativeGitRepositoryProvider` test proving that one command
   currently resolves the same repository more than once across advertisement,
   negotiation, and fetch or receive completion.
2. Add receive-pack coverage proving that ingestion and publication must share
   one resolved repository instance.
3. Run the focused transport tests and record RED against the one-resolution
   expectation.
4. Introduce a concrete command-scoped object holding `InitialRequestData`, the
   normalized path, one resolved or created `NativeGitRepository`, and the
   access hook. Prefer a concrete object over a second generic service port.
5. Move repository operations behind methods on that context and remove
   repeated `InitialRequestData` and access-hook parameters.
6. Keep `beforeFetch` checks per requested want and `beforeUpdate` checks per
   ref; only invariant lookup and initial read/receive authorization move to
   context creation.
7. At each bootstrap entrypoint, open one context after parsing initial request
   data, pass it into `GitBlockingWireSession`, and close command-owned resources
   once. Treat Smart HTTP discovery and POST as separate commands.
8. Delete the single-implementation repository service interface if no second
   production implementation remains after the migration.
9. Rerun the focused native TCP, SSH, HTTP, and repository tests and expect
   GREEN with one provider resolution per command.
10. Commit the command-context change and tests.

### Task 6: Add the global capability advertisement veto

**Files:**

- Create: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/capability/GitCapabilityAdvertisementPolicy.java`
- Create: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/capability/GitCapabilityAdvertisementPolicyTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireConfiguration.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitBlockingWireSession.java`
- Modify: native TCP, SSH, Smart HTTP, and Dagger composition code
- Modify: parser, transport, and HTTP advertisement/negotiation tests

1. Add policy tests for default allow, an immutable deny set, matching a valued
   capability by its canonical name, and rejecting blank or parameterized deny
   keys.
2. Add cross-protocol characterization tests for the default advertisements,
   then tests denying one legacy capability, `agent`, the v2 `fetch` command,
   and one v2 fetch feature such as `shallow`.
3. Run the focused tests and record RED for the missing policy.
4. Implement the veto-only contract:

   ```java
   @FunctionalInterface
   public interface GitCapabilityAdvertisementPolicy {
       GitCapabilityAdvertisementPolicy ALLOW_ALL = ignored -> true;

       boolean allows(String canonicalName);
   }
   ```

   Provide an immutable deny-set factory; do not add mutation or an enable
   operation.
5. Build the implemented capability candidates in their owning protocol code,
   filter all candidates through the same policy immediately before creating
   the effective advertisement, and reuse that effective set for negotiation
   validation.
6. If a parent v2 command is denied, suppress its child features and reject the
   command. Denying a child feature must not suppress unrelated features.
7. Replace `GitWireConfiguration` booleans that only control advertisement with
   the policy. Keep any setting that genuinely changes implemented behavior and
   make that distinction explicit in naming.
8. Provide one immutable policy at the Orion composition root and pass it to
   native TCP, SSH, and Smart HTTP command construction.
9. Run parser, transport, HTTP, and workflow tests; expect unchanged bytes under
   `ALLOW_ALL` and consistent suppression under the deny policy.
10. Commit the capability-policy change and tests.

### Task 7: Verify the complete simplification and update the review

1. Run focused reactor tests for `git-parser`, `git-native-storage`,
   `git-client`, `net/git-transport`, `net/http-core`, and
   `tests/git-engine-orion-adapters` with `-Pdev -T 4 -am`.
2. Run:

   ```bash
   mvn verify -Pdev -T 4
   ```

   Expected: BUILD SUCCESS.
3. Run `git diff --check` and the repository source line-length audit; fix only
   issues introduced by this work.
4. Confirm the parser dependency and import checks from Task 4 remain empty.
5. Review the final diff against all five task nodes, especially producer close
   ownership, action-specific authorization, and default byte compatibility.
6. Complete the read-only task at
   `docs/plans/upcoming-work/git-wire-architecture-simplification/post-simplification-review/TASK.md`
   with `architecture-simplifier` after all five implementation tasks are done.
7. Update
   `docs/reviews/2026-09-03-git-parser-architecture-simplification.md` with a
   dated `resolved`, `remaining`, or `regressed` conclusion and evidence for
   every finding.
8. Create separate task nodes for any remaining or newly confirmed
   simplification. Do not implement those findings during the read-only audit.
