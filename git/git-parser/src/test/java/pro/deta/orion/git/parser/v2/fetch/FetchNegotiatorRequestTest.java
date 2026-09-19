package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.command.FetchCommand;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitProtocolVersion;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.fetch.FetchTestSupport;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.proto.GitProtocolContext;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;
import static pro.deta.orion.git.parser.v2.data.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.data.GitTransport.SSH;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;

class FetchNegotiatorRequestTest {
    @TempDir
    static Path directory;
    private static final String WANT = PackTestData.objectId(GitObjectType.BLOB, new byte[]{42}).toHex();
    private static final String HAVE = "cd".repeat(20);

    @Test
    void legacyRequestLeavesNegotiationUnread() throws Exception {
        String request = packet("want " + WANT.toUpperCase() + " multi_ack_detailed thin-pack ofs-delta\n")
                + packet("want " + WANT) + packet("shallow " + HAVE + "\n") + "0000";
        try (var input = input(request + packet("have " + HAVE + "\n") + packet("done\n"))) {
            FetchRequest result = FetchRequest.parseLegacy(reader(input));
            assertThat(result.wants()).containsExactly(new ObjectId(WANT));
            assertThat(result.shallowCommits()).containsExactly(new ObjectId(HAVE));
            assertThat(result.mode()).isEqualTo(FetchRequest.Mode.MULTI_ACK_DETAILED);
            assertThat(result.capabilities()).contains(value(GitCapability.THIN_PACK), value(GitCapability.OFS_DELTA));
            assertThat(result.initialMessages()).isEmpty();
            GitProtocolContext.Reader reader = reader(input);
            assertThat(FetchCommand.readNegotiationMessage(reader))
                    .isEqualTo(new NegotiationMessage.Have(new ObjectId(HAVE)));
            assertThat(FetchCommand.readNegotiationMessage(reader))
                    .isEqualTo(NegotiationMessage.Control.DONE);
        }
    }

    @Test
    void legacyEmptyFlushEndsWithoutReadingAnotherRequest() throws Exception {
        try (var input = input("0000NEXT")) {
            FetchRequest request = FetchRequest.parseLegacy(reader(input));
            assertThat(request.wants()).isEmpty();
            assertThat(input.readUnsignedByte()).isEqualTo('N');
        }
    }

    @Test
    void rejectsLegacyCapabilitiesAfterFirstWantAndPrematureHaves() throws Exception {
        for (String line : new String[]{"want " + HAVE + " multi_ack", "have " + HAVE}) {
            try (var input = input(packet("want " + WANT) + packet(line) + "0000")) {
                assertThatThrownBy(() -> FetchRequest.parseLegacy(reader(input)))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void choosesLegacyAckModeFromFirstWant() throws Exception {
        try (var input = input(packet("want " + WANT + " multi_ack") + "0000")) {
            assertThat(FetchRequest.parseLegacy(reader(input)).mode())
                    .isEqualTo(FetchRequest.Mode.MULTI_ACK);
        }
        try (var input = input(packet("want " + WANT) + "0000")) {
            assertThat(FetchRequest.parseLegacy(reader(input)).mode())
                    .isEqualTo(FetchRequest.Mode.SINGLE_ACK);
        }
    }

    @Test
    void v2PreservesMessagesAndOptionsWithoutConsumingNextCommand() throws Exception {
        String request = packet("want " + WANT) + packet("thin-pack") + packet("have " + HAVE)
                + packet("shallow " + HAVE) + packet("wait-for-done") + packet("done")
                + packet("include-tag") + "0000";
        try (var input = input(request + "NEXT")) {
            FetchRequest result = FetchRequest.parseV2(reader(input));
            assertThat(result.mode()).isEqualTo(FetchRequest.Mode.PROTOCOL_V2);
            assertThat(result.waitForDone()).isTrue();
            assertThat(result.capabilities()).contains(value(GitCapability.THIN_PACK), value(GitCapability.INCLUDE_TAG));
            assertThat(result.initialMessages()).containsExactly(
                    new NegotiationMessage.Have(new ObjectId(HAVE)),
                    NegotiationMessage.Control.DONE, NegotiationMessage.Control.END_ROUND);
            assertThat(input.readUnsignedByte()).isEqualTo('N');
        }
    }

    @Test
    void preservesRefWantsFilterAndCompatibleTimeAndRefDeepening() throws Exception {
        String request = packet("want-ref refs/heads/ветка") + packet("deepen-since 100")
                + packet("deepen-not refs/heads/old") + packet("filter blob:limit=1k")
                + packet("packfile-uris https,http") + "0000";
        try (var input = input(request)) {
            FetchRequest result = FetchRequest.parseV2(reader(input));
            assertThat(result.wants()).isEmpty();
            assertThat(result.wantRefs()).containsExactly("refs/heads/ветка");
            assertThat(result.deepenSince().getAsLong()).isEqualTo(100);
            assertThat(result.deepenNot()).containsExactly("refs/heads/old");
            assertThat(result.filter()).contains("blob:limit=1k");
            assertThat(result.packfileUriProtocols()).containsExactlyInAnyOrder("https", "http");
        }
    }

    @Test
    void parsesRelativeDepthInBothGrammars() throws Exception {
        try (var input = input(packet("want " + WANT + " deepen-relative")
                + packet("deepen 3") + "0000")) {
            assertThat(FetchRequest.parseLegacy(reader(input)).depth().getAsInt())
                    .isEqualTo(3);
        }
        try (var input = input(packet("want " + WANT) + packet("deepen-relative")
                + packet("deepen 3") + "0000")) {
            assertThat(FetchRequest.parseV2(reader(input)).depth().getAsInt())
                    .isEqualTo(3);
        }
    }

    @Test
    void rejectsInvalidAndOverflowingNumericArguments() throws Exception {
        for (String argument : new String[]{"deepen 0", "deepen -1", "deepen 2147483648",
                "deepen-since 184467440737095516160", "deepen-since +1"}) {
            assertInvalidV2(packet(argument));
        }
    }

    @Test
    void rejectsContradictoryOrDuplicateOptions() throws Exception {
        assertInvalidV2(packet("deepen 2") + packet("deepen-since 1"));
        assertInvalidV2(packet("deepen-not main") + packet("deepen 2"));
        assertInvalidV2(packet("deepen-relative"));
        assertInvalidV2(packet("filter blob:none") + packet("filter blob:none"));
        assertInvalidV2(packet("done") + packet("done"));
    }

    @Test
    void rejectsMalformedIdsTextAndUnknownArguments() throws Exception {
        for (String argument : new String[]{"want invalid", "have " + HAVE + " ", "future-option", "multi_ack", "atomic",
                "want  " + WANT, "want=" + WANT, "thin-pack extra", "thin-pack ", "done extra", "done ",
                "want-ref refs/heads/.hidden", "want-ref refs/heads/main.lock", "have " + HAVE + "\nextra"}) {
            assertInvalidV2(packet(argument));
        }
        try (var input = input("0000")) {
            assertThatThrownBy(() -> FetchRequest.parseV2(reader(input)))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsMalformedHeadersAndUnexpectedControls() throws Exception {
        for (String header : new String[]{"zzzz", "0003", "ffff", "0001", "0002", "0004"}) {
            try (var input = input(header)) {
                assertThatThrownBy(() -> FetchRequest.parseV2(reader(input)))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void rejectsTruncationInsteadOfCompletingRequest() throws Exception {
        for (String truncated : new String[]{"", "00", "000aw", packet("want " + WANT)}) {
            try (var input = input(truncated)) {
                assertThatThrownBy(() -> FetchRequest.parseV2(reader(input)))
                        .isInstanceOf(EOFException.class);
            }
        }
    }

    @Test
    void rejectsUnadvertisedCapabilitiesBeforeHavesOrRepliesInEveryWireVersion() throws Exception {
        for (GitProtocolVersion version : GitProtocolVersion.values()) {
            boolean v2 = version == GitProtocolVersion.V2;
            String wire = v2
                    ? packet("want " + WANT) + packet("sideband-all") + packet("have " + HAVE) + "0000NEXT"
                    : packet("want " + WANT + " thin-pack") + "0000" + packet("have " + HAVE);
            try (var input = input(wire)) {
                var bytes = new ByteArrayOutputStream();
                var reader = reader(input);
                var protocol = protocol(input, bytes, version, HTTP);
                var command = new FetchCommand(FetchTestSupport.storage(directory), capabilities());
                assertThatThrownBy(() -> command.action(protocol))
                        .isInstanceOf(IOException.class).hasMessageContaining("not advertised");
                assertThat(bytes.size()).isZero();
                if (v2) {
                    assertThat(input.readUnsignedByte()).isEqualTo('N');
                } else {
                    assertThat(FetchCommand.readNegotiationMessage(reader))
                            .isEqualTo(new NegotiationMessage.Have(new ObjectId(HAVE)));
                }
            }
        }
    }

    @Test
    void missingWantsFailBeforeAcknowledgingHavesInEveryWireVersion() throws Exception {
        for (GitProtocolVersion version : GitProtocolVersion.values()) {
            boolean v2 = version == GitProtocolVersion.V2;
            String wire = packet("want " + WANT) + (v2 ? "" : "0000")
                    + packet("have " + HAVE) + packet("done") + (v2 ? "0000" : "") + "NEXT";
            try (var input = input(wire)) {
                var bytes = new ByteArrayOutputStream();
                var reader = reader(input);
                var protocol = protocol(input, bytes, version, HTTP);
                var storage = FetchTestSupport.storage(directory);
                var command = new FetchCommand(storage, capabilities());

                assertThatThrownBy(() -> command.action(protocol))
                        .isInstanceOf(IOException.class).hasMessageContaining(WANT);

                assertThat(bytes.size()).isZero();
                if (v2) {
                    assertThat(input.readUnsignedByte()).isEqualTo('N');
                } else {
                    assertThat(FetchCommand.readNegotiationMessage(reader))
                            .isEqualTo(new NegotiationMessage.Have(new ObjectId(HAVE)));
                }
            }
        }
    }

    @Test
    void commandPassesNegotiatedFeaturesIntoThePlanAfterDone() throws Exception {
        try (var input = input(packet("want " + WANT) + packet("wait-for-done") + packet("done") + "0000")) {
            var bytes = new ByteArrayOutputStream();
            var protocol = protocol(input, bytes, GitProtocolVersion.V2, HTTP);
            var storage = FetchTestSupport.storage(directory);
            PackTestData.store(storage, GitObjectType.BLOB, new byte[]{42});
            var command = new FetchCommand(storage, capabilities(GitCapability.WAIT_FOR_DONE));
            var plan = negotiate(command, protocol).orElseThrow();
            assertThat(plan.capabilities()).contains(value(GitCapability.WAIT_FOR_DONE));
            assertThat(plan.wantedObjects()).containsExactly(new ObjectId(WANT));
            assertThat(plan.commonObjects()).isEmpty();
            assertThat(bytes.size()).isZero();
        }
    }

    @Test
    void unfinishedCommandWritesTheRoundAndReturnsNoPlan() throws Exception {
        try (var input = input(packet("want " + WANT) + packet("have " + HAVE) + "0000NEXT")) {
            var bytes = new ByteArrayOutputStream();
            var protocol = protocol(input, bytes, GitProtocolVersion.V2, HTTP);
            var storage = FetchTestSupport.storage(directory);
            PackTestData.store(storage, GitObjectType.BLOB, new byte[]{42});
            var command = new FetchCommand(storage, capabilities());
            assertThat(negotiate(command, protocol)).isEmpty();
            assertThat(bytes.toString(StandardCharsets.UTF_8))
                    .isEqualTo(packet("acknowledgments\n") + packet("NAK\n") + "0000");
            assertThat(input.readUnsignedByte()).isEqualTo('N');
        }
    }

    @Test
    void readRequestStopsAtItsBoundaryWithoutStartingNegotiation() throws Exception {
        for (GitProtocolVersion version : GitProtocolVersion.values()) {
            String arguments = packet("want " + WANT);
            if (version == GitProtocolVersion.V2) {
                arguments += packet("have " + HAVE) + packet("done");
            }
            try (var input = input(arguments + "0000NEXT")) {
                var bytes = new ByteArrayOutputStream();
                var protocol = protocol(input, bytes, version, HTTP);

                FetchRequest request = FetchRequest.parseRequest(protocol.reader(), protocol.version());

                assertThat(request.wants()).containsExactly(new ObjectId(WANT));
                assertThat(bytes.size()).isZero();
                assertThat(input.readUnsignedByte()).isEqualTo('N');
                if (version == GitProtocolVersion.V2) {
                    assertThat(request.initialMessages()).containsExactly(
                            new NegotiationMessage.Have(new ObjectId(HAVE)),
                            NegotiationMessage.Control.DONE, NegotiationMessage.Control.END_ROUND);
                } else {
                    assertThat(request.initialMessages()).isEmpty();
                }
            }
        }
    }

    @Test
    void emptyLegacyNegotiationDoesNotReadOrWriteAnotherExchange() throws Exception {
        for (GitProtocolVersion version : new GitProtocolVersion[]{GitProtocolVersion.V0, GitProtocolVersion.V1}) {
            try (var input = input("0000NEXT")) {
                var bytes = new ByteArrayOutputStream();
                var protocol = protocol(input, bytes, version, HTTP);
                assertThat(negotiate(new FetchCommand(FetchTestSupport.storage(directory), capabilities()), protocol)).isEmpty();
                assertThat(input.readUnsignedByte()).isEqualTo('N');
                assertThat(bytes.size()).isZero();
            }
        }
    }

    @Test
    void v2DoneFeedsParsedHavesAndEndsWithoutReadingOrWritingAnotherExchange() throws Exception {
        ObjectId common = new ObjectId(HAVE);
        try (var input = input(packet("want " + WANT) + packet("have " + HAVE)
                + packet("done") + "0000NEXT")) {
            var bytes = new ByteArrayOutputStream();
            var protocol = protocol(input, bytes, GitProtocolVersion.V2, HTTP);
            var request = FetchRequest.parseRequest(protocol.reader(), protocol.version());
            var checks = new NegotiationContext(request, FetchTestSupport.storage(directory), capabilities()) {
                @Override
                public boolean objectExists(ObjectId objectId) {
                    assertThat(objectId).isEqualTo(common);
                    return true;
                }

                @Override
                public boolean isReady() {
                    throw new AssertionError("DONE must not require early readiness");
                }
            };
            NegotiationContext context = new FetchCommand(FetchTestSupport.storage(directory), capabilities()).negotiate(
                    new FetchNegotiatorIterator(checks, HTTP), protocol.reader(), protocol.writer());
            assertThat(context).isSameAs(checks);
            assertThat(context.request().wants()).containsExactly(new ObjectId(WANT));
            assertThat(context.commonObjects()).containsExactly(common);
            assertThat(context.doneReceived()).isTrue();
            assertThat(context.ready()).isFalse();
            assertThat(input.readUnsignedByte()).isEqualTo('N');
            assertThat(bytes.size()).isZero();
        }
    }

    @Test
    void legacyFlushesEachReplyBeforeReadingMoreAndKeepsNegotiationOutsideSideband() throws Exception {
        for (GitProtocolVersion version : new GitProtocolVersion[]{GitProtocolVersion.V0, GitProtocolVersion.V1}) {
            String throughHave = packet("want " + WANT + " multi_ack_detailed side-band-64k")
                    + "0000" + packet("have " + HAVE);
            String wire = throughHave + "0000" + packet("done") + "NEXT";
            var bytes = new ByteArrayOutputStream() {
                private int flushes;

                @Override
                public void flush() {
                    flushes++;
                }
            };
            var source = new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)) {
                @Override
                public synchronized int read() {
                    if (pos == throughHave.length()) {
                        assertThat(bytes.flushes).isEqualTo(1);
                    } else if (pos == throughHave.length() + 4) {
                        assertThat(bytes.flushes).isEqualTo(2);
                    }
                    return super.read();
                }
            };
            try (var input = new BufferedByteInputV2(source)) {
                var protocol = protocol(input, bytes, version, HTTP);
                FetchRequest request = FetchRequest.parseRequest(protocol.reader(), protocol.version());
                var checks = checks(request, false, GitCapability.MULTI_ACK_DETAILED, GitCapability.SIDE_BAND_64K);
                NegotiationContext context = new FetchCommand(FetchTestSupport.storage(directory), capabilities()).negotiate(
                        new FetchNegotiatorIterator(checks, SSH), protocol.reader(), protocol.writer());
                assertThat(context.commonObjects()).containsExactly(new ObjectId(HAVE));
                assertThat(context.doneReceived()).isTrue();
                assertThat(input.readUnsignedByte()).isEqualTo('N');
                assertThat(bytes.flushes).isEqualTo(3);
                assertThat(bytes.toString(StandardCharsets.US_ASCII))
                        .isEqualTo("0038ACK " + HAVE + " common\n0008NAK\n0031ACK " + HAVE + "\n");
            }
        }
    }

    @Test
    void v2WritesReadySectionUsingNegotiatedSidebandWithoutReadingAnotherRequest() throws Exception {
        try (var input = input(packet("want " + WANT) + packet("sideband-all")
                + packet("have " + HAVE) + "0000NEXT")) {
            var bytes = new ByteArrayOutputStream();
            var protocol = protocol(input, bytes, GitProtocolVersion.V2, HTTP);
            var request = FetchRequest.parseRequest(protocol.reader(), protocol.version());
            var checks = checks(request, true, GitCapability.SIDEBAND_ALL);
            NegotiationContext context = new FetchCommand(FetchTestSupport.storage(directory), capabilities()).negotiate(
                    new FetchNegotiatorIterator(checks, HTTP), protocol.reader(), protocol.writer());
            assertThat(context.ready()).isTrue();
            assertThat(context.doneReceived()).isFalse();
            assertThat(input.readUnsignedByte()).isEqualTo('N');
            assertThat(bytes.toString(StandardCharsets.US_ASCII)).isEqualTo(
                    "0015\u0001acknowledgments\n0032\u0001ACK " + HAVE + "\n000b\u0001ready\n0001");
        }
    }

    @Test
    void v2WaitForDoneEndsAcknowledgmentsWithFlushEvenIfCommonGraphIsReady() throws Exception {
        try (var input = input(packet("want " + WANT) + packet("wait-for-done")
                + packet("have " + HAVE) + "0000NEXT")) {
            var bytes = new ByteArrayOutputStream();
            var protocol = protocol(input, bytes, GitProtocolVersion.V2, HTTP);
            var request = FetchRequest.parseRequest(protocol.reader(), protocol.version());
            var checks = checks(request, true, GitCapability.WAIT_FOR_DONE);
            NegotiationContext context = new FetchCommand(FetchTestSupport.storage(directory), capabilities()).negotiate(
                    new FetchNegotiatorIterator(checks, HTTP), protocol.reader(), protocol.writer());
            assertThat(context.ready()).isFalse();
            assertThat(context.doneReceived()).isFalse();
            assertThat(input.readUnsignedByte()).isEqualTo('N');
            assertThat(bytes.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0014acknowledgments\n0031ACK " + HAVE + "\n0000");
        }
    }

    @Test
    void legacyMessageParsingPreservesBoundariesAndRejectsInvalidInput() throws Exception {
        try (var input = input("0000" + packet("done") + "NEXT")) {
            var reader = reader(input);
            assertThat(FetchCommand.readNegotiationMessage(reader))
                    .isEqualTo(NegotiationMessage.Control.END_ROUND);
            assertThat(FetchCommand.readNegotiationMessage(reader))
                    .isEqualTo(NegotiationMessage.Control.DONE);
            assertThat(input.readUnsignedByte()).isEqualTo('N');
        }
        for (String wire : new String[]{packet("have invalid"), packet("have"), packet("done extra"),
                packet("want " + WANT), packet("want-ref refs/heads/main"), "0001", ""}) {
            try (var input = input(wire)) {
                assertThatThrownBy(() -> FetchCommand.readNegotiationMessage(reader(input)))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void mutableRequestsRetainValuedCapabilitiesWithoutSharingState() throws Exception {
        String wire = packet("want " + WANT
                + " multi_ack agent=client/1 object-format=sha1 custom-feature=value") + "0000";
        try (var firstInput = input(wire); var secondInput = input(wire)) {
            FetchRequest first = FetchRequest.parseLegacy(reader(firstInput));
            FetchRequest second = FetchRequest.parseLegacy(reader(secondInput));
            assertThat(first.capabilities()).contains(value(GitCapability.MULTI_ACK),
                    value(GitCapability.AGENT, "client/1"), value(GitCapability.OBJECT_FORMAT, "sha1"),
                    value("custom-feature", "value"));
            first.wants().clear();
            first.capabilities().add(value(GitCapability.WAIT_FOR_DONE));
            first.setDepth(java.util.OptionalInt.of(3));
            assertThat(first.wants()).isEmpty();
            assertThat(first.waitForDone()).isTrue();
            assertThat(first.depth().getAsInt()).isEqualTo(3);
            assertThat(second.wants()).containsExactly(new ObjectId(WANT));
            assertThat(second.waitForDone()).isFalse();
            assertThat(second.depth()).isEmpty();
        }
    }

    @Test
    void valuedAckCapabilityDoesNotEnableBareFlagAndUnsupportedObjectFormatFails() throws Exception {
        try (var input = input(packet("want " + WANT + " multi_ack=custom") + "0000")) {
            FetchRequest request = FetchRequest.parseLegacy(reader(input));
            assertThat(request.mode()).isEqualTo(FetchRequest.Mode.SINGLE_ACK);
            assertThat(request.capabilities()).contains(value(GitCapability.MULTI_ACK, "custom"));
        }
        try (var input = input(packet("want " + WANT + " object-format=sha256") + "0000")) {
            var request = FetchRequest.parseLegacy(reader(input));
            var command = new FetchCommand(FetchTestSupport.storage(directory), capabilities(GitCapability.OBJECT_FORMAT));
            assertThatThrownBy(() -> command.prepareNegotiation(request, SSH))
                    .isInstanceOf(IOException.class).hasMessageContaining("Expected object format sha1");
        }
    }

    private static void assertInvalidV2(String arguments) throws Exception {
        try (var input = input(packet("want " + WANT) + arguments + "0000")) {
            assertThatThrownBy(() -> FetchRequest.parseV2(reader(input)))
                    .isInstanceOf(IOException.class);
        }
    }

    private static NegotiationContext checks(FetchRequest request, boolean ready, GitCapability... advertised) {
        return new NegotiationContext(request, FetchTestSupport.storage(directory), capabilities(advertised)) {
            @Override
            public boolean objectExists(ObjectId objectId) {
                return objectId.equals(new ObjectId(HAVE));
            }

            @Override
            public boolean isReady() {
                return ready;
            }
        };
    }

    private static GitProtocolContext protocol(BufferedByteInputV2 input, ByteArrayOutputStream output,
                                               GitProtocolVersion version, GitTransport transport) {
        return new GitProtocolContext(input, new OutputStreamBufferedByteOutput(output), version, transport);
    }

    private static GitProtocolContext.Reader reader(BufferedByteInputV2 input) {
        return new GitProtocolContext(input, new OutputStreamBufferedByteOutput(OutputStream.nullOutputStream()),
                GitProtocolVersion.V0, SSH).reader();
    }

    private static Optional<FetchPlan> negotiate(FetchCommand command, GitProtocolContext protocol)
            throws IOException {
        var reader = protocol.reader();
        var request = FetchRequest.parseRequest(reader, protocol.version());
        var iterator = command.prepareNegotiation(request, protocol.transport());
        return command.prepareResponse(command.negotiate(iterator, reader, protocol.writer()));
    }

    private static String packet(String payload) {
        return "%04x".formatted(payload.getBytes(StandardCharsets.UTF_8).length + 4) + payload;
    }

    private static BufferedByteInputV2 input(String wire) {
        return new BufferedByteInputV2(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
    }
}
