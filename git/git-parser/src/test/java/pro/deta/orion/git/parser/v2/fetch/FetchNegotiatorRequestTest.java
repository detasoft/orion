package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.command.FetchCommand;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.InMemoryGitStorage;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.GitReader;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.v2.GitWriter;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData.ProtocolVersion;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.GitTransport.SSH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FetchNegotiatorRequestTest {
    private static final String WANT = "ab".repeat(20);
    private static final String HAVE = "cd".repeat(20);

    @Test
    void legacyRequestLeavesNegotiationUnread() throws Exception {
        String request = packet("want " + WANT.toUpperCase() + " multi_ack_detailed thin-pack ofs-delta\n")
                + packet("want " + WANT) + packet("shallow " + HAVE + "\n") + "0000";
        try (var input = input(request + packet("have " + HAVE + "\n") + packet("done\n"))) {
            FetchRequest result = FetchRequest.parseLegacy(new GitReader(input));
            assertThat(result.wants()).containsExactly(new ObjectId(WANT));
            assertThat(result.shallowCommits()).containsExactly(new ObjectId(HAVE));
            assertThat(result.mode()).isEqualTo(FetchRequest.Mode.MULTI_ACK_DETAILED);
            assertThat(result.capabilities()).contains(GitCapability.THIN_PACK.entry(), GitCapability.OFS_DELTA.entry());
            assertThat(result.initialMessages()).isEmpty();
            GitReader reader = new GitReader(input);
            assertThat(FetchNegotiator.readNegotiationMessage(reader))
                    .isEqualTo(new NegotiationMessage.Have(new ObjectId(HAVE)));
            assertThat(FetchNegotiator.readNegotiationMessage(reader))
                    .isEqualTo(NegotiationMessage.Control.DONE);
        }
    }

    @Test
    void legacyEmptyFlushEndsWithoutReadingAnotherRequest() throws Exception {
        try (var input = input("0000NEXT")) {
            FetchRequest request = FetchRequest.parseLegacy(new GitReader(input));
            assertThat(request.wants()).isEmpty();
            assertThat(input.readUnsignedByte()).isEqualTo('N');
        }
    }

    @Test
    void rejectsLegacyCapabilitiesAfterFirstWantAndPrematureHaves() throws Exception {
        for (String line : new String[]{"want " + HAVE + " multi_ack", "have " + HAVE}) {
            try (var input = input(packet("want " + WANT) + packet(line) + "0000")) {
                assertThatThrownBy(() -> FetchRequest.parseLegacy(new GitReader(input)))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void choosesLegacyAckModeFromFirstWant() throws Exception {
        try (var input = input(packet("want " + WANT + " multi_ack") + "0000")) {
            assertThat(FetchRequest.parseLegacy(new GitReader(input)).mode())
                    .isEqualTo(FetchRequest.Mode.MULTI_ACK);
        }
        try (var input = input(packet("want " + WANT) + "0000")) {
            assertThat(FetchRequest.parseLegacy(new GitReader(input)).mode())
                    .isEqualTo(FetchRequest.Mode.SINGLE_ACK);
        }
    }

    @Test
    void v2PreservesMessagesAndOptionsWithoutConsumingNextCommand() throws Exception {
        String request = packet("want " + WANT) + packet("thin-pack") + packet("have " + HAVE)
                + packet("shallow " + HAVE) + packet("wait-for-done") + packet("done")
                + packet("include-tag") + "0000";
        try (var input = input(request + "NEXT")) {
            FetchRequest result = FetchRequest.parseV2(new GitReader(input));
            assertThat(result.mode()).isEqualTo(FetchRequest.Mode.PROTOCOL_V2);
            assertThat(result.waitForDone()).isTrue();
            assertThat(result.capabilities()).contains(GitCapability.THIN_PACK.entry(), GitCapability.INCLUDE_TAG.entry());
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
            FetchRequest result = FetchRequest.parseV2(new GitReader(input));
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
            assertThat(FetchRequest.parseLegacy(new GitReader(input)).depth().getAsInt())
                    .isEqualTo(3);
        }
        try (var input = input(packet("want " + WANT) + packet("deepen-relative")
                + packet("deepen 3") + "0000")) {
            assertThat(FetchRequest.parseV2(new GitReader(input)).depth().getAsInt())
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
            assertThatThrownBy(() -> FetchRequest.parseV2(new GitReader(input)))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsMalformedHeadersAndUnexpectedControls() throws Exception {
        for (String header : new String[]{"zzzz", "0003", "ffff", "0001", "0002", "0004"}) {
            try (var input = input(header)) {
                assertThatThrownBy(() -> FetchRequest.parseV2(new GitReader(input)))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void rejectsTruncationInsteadOfCompletingRequest() throws Exception {
        for (String truncated : new String[]{"", "00", "000aw", packet("want " + WANT)}) {
            try (var input = input(truncated)) {
                assertThatThrownBy(() -> FetchRequest.parseV2(new GitReader(input)))
                        .isInstanceOf(EOFException.class);
            }
        }
    }

    @Test
    void rejectsUnadvertisedCapabilitiesBeforeHavesOrRepliesInEveryWireVersion() throws Exception {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            boolean v2 = version == ProtocolVersion.V2;
            String wire = v2
                    ? packet("want " + WANT) + packet("sideband-all") + packet("have " + HAVE) + "0000NEXT"
                    : packet("want " + WANT + " thin-pack") + "0000" + packet("have " + HAVE);
            try (var input = input(wire)) {
                var bytes = new ByteArrayOutputStream();
                var reader = new GitReader(input);
                var negotiator = new FetchNegotiator(reader,
                        new GitWriter(new OutputStreamBufferedByteOutput(bytes)), version);
                var command = new FetchCommand(new GitStorageApi(), Set.of());
                assertThatThrownBy(() -> command.negotiate(negotiator, HTTP))
                        .isInstanceOf(IOException.class).hasMessageContaining("not advertised");
                assertThat(bytes.size()).isZero();
                if (v2) {
                    assertThat(input.readUnsignedByte()).isEqualTo('N');
                } else {
                    assertThat(FetchNegotiator.readNegotiationMessage(reader))
                            .isEqualTo(new NegotiationMessage.Have(new ObjectId(HAVE)));
                }
            }
        }
    }

    @Test
    void missingWantsFailBeforeAcknowledgingHavesInEveryWireVersion() throws Exception {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            boolean v2 = version == ProtocolVersion.V2;
            String wire = packet("want " + WANT) + (v2 ? "" : "0000")
                    + packet("have " + HAVE) + packet("done") + (v2 ? "0000" : "") + "NEXT";
            try (var input = input(wire)) {
                var bytes = new ByteArrayOutputStream();
                var reader = new GitReader(input);
                var negotiator = new FetchNegotiator(reader,
                        new GitWriter(new OutputStreamBufferedByteOutput(bytes)), version);
                var storage = new InMemoryGitStorage();
                var command = new FetchCommand(storage.api, Set.of());

                assertThatThrownBy(() -> command.negotiate(negotiator, HTTP))
                        .isInstanceOf(IOException.class).hasMessageContaining(WANT);
                assertThat(storage.lookups).containsExactly(new ObjectId(WANT));

                assertThat(bytes.size()).isZero();
                if (v2) {
                    assertThat(input.readUnsignedByte()).isEqualTo('N');
                } else {
                    assertThat(FetchNegotiator.readNegotiationMessage(reader))
                            .isEqualTo(new NegotiationMessage.Have(new ObjectId(HAVE)));
                }
            }
        }
    }

    @Test
    void commandPassesAdvertisedFeaturesIntoTheParsedRequestContext() throws Exception {
        try (var input = input(packet("want " + WANT) + packet("wait-for-done") + packet("done") + "0000")) {
            var bytes = new ByteArrayOutputStream();
            var negotiator = new FetchNegotiator(new GitReader(input),
                    new GitWriter(new OutputStreamBufferedByteOutput(bytes)), ProtocolVersion.V2);
            var storage = new InMemoryGitStorage();
            storage.put(new ObjectId(WANT), ObjectType.BLOB, java.util.Optional.empty(), new byte[]{42});
            var command = new FetchCommand(storage.api, Set.of(GitCapability.WAIT_FOR_DONE));
            NegotiationContext context = command.negotiate(negotiator, HTTP);
            assertThat(context.request().waitForDone()).isTrue();
            assertThat(context.doneReceived()).isTrue();
            assertThat(bytes.size()).isZero();
        }
    }

    @Test
    void readRequestStopsAtItsBoundaryWithoutStartingNegotiation() throws Exception {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            String arguments = packet("want " + WANT);
            if (version == ProtocolVersion.V2) {
                arguments += packet("have " + HAVE) + packet("done");
            }
            try (var input = input(arguments + "0000NEXT")) {
                var bytes = new ByteArrayOutputStream();
                var negotiator = new FetchNegotiator(new GitReader(input),
                        new GitWriter(new OutputStreamBufferedByteOutput(bytes)), version);

                FetchRequest request = negotiator.readRequest();

                assertThat(request.wants()).containsExactly(new ObjectId(WANT));
                assertThat(bytes.size()).isZero();
                assertThat(input.readUnsignedByte()).isEqualTo('N');
                if (version == ProtocolVersion.V2) {
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
        for (ProtocolVersion version : new ProtocolVersion[]{ProtocolVersion.V0, ProtocolVersion.V1}) {
            try (var input = input("0000NEXT")) {
                var bytes = new ByteArrayOutputStream();
                var negotiator = new FetchNegotiator(new GitReader(input),
                        new GitWriter(new OutputStreamBufferedByteOutput(bytes)), version);
                assertThat(new FetchCommand(new GitStorageApi(), Set.of()).negotiate(negotiator, SSH)
                        .request().wants()).isEmpty();
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
            var negotiator = new FetchNegotiator(new GitReader(input),
                    new GitWriter(new OutputStreamBufferedByteOutput(bytes)), ProtocolVersion.V2);
            var checks = new NegotiationContext(negotiator.readRequest(), new GitStorageApi(), Set.of()) {
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
            NegotiationContext context = negotiator.negotiate(new FetchNegotiatorIterator(checks, HTTP));
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
        for (ProtocolVersion version : new ProtocolVersion[]{ProtocolVersion.V0, ProtocolVersion.V1}) {
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
            try (var input = new InputStreamBufferedByteInput(source)) {
                var negotiator = new FetchNegotiator(new GitReader(input),
                        new GitWriter(new OutputStreamBufferedByteOutput(bytes)), version);
                FetchRequest request = negotiator.readRequest();
                var checks = checks(request, false, GitCapability.MULTI_ACK_DETAILED, GitCapability.SIDE_BAND_64K);
                NegotiationContext context = negotiator.negotiate(new FetchNegotiatorIterator(checks, SSH));
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
            var negotiator = new FetchNegotiator(new GitReader(input),
                    new GitWriter(new OutputStreamBufferedByteOutput(bytes)), ProtocolVersion.V2);
            var checks = checks(negotiator.readRequest(), true, GitCapability.SIDEBAND_ALL);
            NegotiationContext context = negotiator.negotiate(new FetchNegotiatorIterator(checks, HTTP));
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
            var negotiator = new FetchNegotiator(new GitReader(input),
                    new GitWriter(new OutputStreamBufferedByteOutput(bytes)), ProtocolVersion.V2);
            var checks = checks(negotiator.readRequest(), true, GitCapability.WAIT_FOR_DONE);
            NegotiationContext context = negotiator.negotiate(new FetchNegotiatorIterator(checks, HTTP));
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
            var reader = new GitReader(input);
            assertThat(FetchNegotiator.readNegotiationMessage(reader))
                    .isEqualTo(NegotiationMessage.Control.END_ROUND);
            assertThat(FetchNegotiator.readNegotiationMessage(reader))
                    .isEqualTo(NegotiationMessage.Control.DONE);
            assertThat(input.readUnsignedByte()).isEqualTo('N');
        }
        for (String wire : new String[]{packet("have invalid"), packet("have"), packet("done extra"),
                packet("want " + WANT), packet("want-ref refs/heads/main"), "0001", ""}) {
            try (var input = input(wire)) {
                assertThatThrownBy(() -> FetchNegotiator.readNegotiationMessage(new GitReader(input)))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void mutableRequestsRetainValuedCapabilitiesWithoutSharingState() throws Exception {
        String wire = packet("want " + WANT
                + " multi_ack agent=client/1 object-format=sha1 custom-feature=value") + "0000";
        try (var firstInput = input(wire); var secondInput = input(wire)) {
            FetchRequest first = FetchRequest.parseLegacy(new GitReader(firstInput));
            FetchRequest second = FetchRequest.parseLegacy(new GitReader(secondInput));
            assertThat(first.capabilities()).contains(GitCapability.MULTI_ACK.entry(),
                    GitCapability.AGENT.withValue("client/1"), GitCapability.OBJECT_FORMAT.withValue("sha1"),
                    GitCapability.Entry.custom("custom-feature", "value"));
            first.wants().clear();
            first.capabilities().add(GitCapability.WAIT_FOR_DONE.entry());
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
            FetchRequest request = FetchRequest.parseLegacy(new GitReader(input));
            assertThat(request.mode()).isEqualTo(FetchRequest.Mode.SINGLE_ACK);
            assertThat(request.capabilities()).contains(GitCapability.MULTI_ACK.withValue("custom"));
        }
        try (var input = input(packet("want " + WANT + " object-format=sha256") + "0000")) {
            assertThatThrownBy(() -> FetchRequest.parseLegacy(new GitReader(input)))
                    .isInstanceOf(IOException.class).hasMessageContaining("Expected object format sha1");
        }
    }

    private static void assertInvalidV2(String arguments) throws Exception {
        try (var input = input(packet("want " + WANT) + arguments + "0000")) {
            assertThatThrownBy(() -> FetchRequest.parseV2(new GitReader(input)))
                    .isInstanceOf(IOException.class);
        }
    }

    private static NegotiationContext checks(FetchRequest request, boolean ready, GitCapability... advertised) {
        return new NegotiationContext(request, new GitStorageApi(), Set.of(advertised)) {
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

    private static String packet(String payload) {
        return "%04x".formatted(payload.getBytes(StandardCharsets.UTF_8).length + 4) + payload;
    }

    private static InputStreamBufferedByteInput input(String wire) {
        return new InputStreamBufferedByteInput(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
    }
}
