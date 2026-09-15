package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.GitReader;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FetchNegotiatorRequestTest {
    private static final String WANT = "ab".repeat(20);
    private static final String HAVE = "cd".repeat(20);

    @Test
    void legacyVersionsShareGrammarAndLeaveNegotiationUnread() throws Exception {
        String request = packet("want " + WANT.toUpperCase() + " multi_ack_detailed thin-pack ofs-delta\n")
                + packet("want " + WANT) + packet("shallow " + HAVE + "\n") + "0000";
        for (boolean v1 : new boolean[]{false, true}) {
            try (var input = input(request + packet("have " + HAVE + "\n") + packet("done\n"))) {
                FetchRequest result = v1 ? FetchNegotiator.parseV1Request(input)
                        : FetchNegotiator.parseV0Request(input);
                assertThat(result.wants()).containsExactly(new ObjectId(WANT));
                assertThat(result.shallowCommits()).containsExactly(new ObjectId(HAVE));
                assertThat(result.mode()).isEqualTo(FetchRequest.Mode.MULTI_ACK_DETAILED);
                assertThat(result.capabilities()).contains("thin-pack", "ofs-delta");
                assertThat(result.initialMessages()).isEmpty();
                GitReader reader = new GitReader(input);
                assertThat(reader.readNegotiationMessage()).isEqualTo(new NegotiationMessage.Have(new ObjectId(HAVE)));
                assertThat(reader.readNegotiationMessage()).isEqualTo(NegotiationMessage.Control.DONE);
            }
        }
    }

    @Test
    void legacyEmptyFlushEndsWithoutReadingAnotherRequest() throws Exception {
        try (var input = input("0000NEXT")) {
            FetchRequest request = FetchNegotiator.parseV0Request(input);
            assertThat(request.wants()).isEmpty();
            assertThat(input.readUnsignedByte()).isEqualTo('N');
        }
    }

    @Test
    void rejectsLegacyCapabilitiesAfterFirstWantAndPrematureHaves() throws Exception {
        for (String line : new String[]{"want " + HAVE + " multi_ack", "have " + HAVE}) {
            try (var input = input(packet("want " + WANT) + packet(line) + "0000")) {
                assertThatThrownBy(() -> FetchNegotiator.parseV1Request(input)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void choosesLegacyAckModeFromFirstWant() throws Exception {
        try (var input = input(packet("want " + WANT + " multi_ack") + "0000")) {
            assertThat(FetchNegotiator.parseV0Request(input).mode()).isEqualTo(FetchRequest.Mode.MULTI_ACK);
        }
        try (var input = input(packet("want " + WANT) + "0000")) {
            assertThat(FetchNegotiator.parseV1Request(input).mode()).isEqualTo(FetchRequest.Mode.SINGLE_ACK);
        }
    }

    @Test
    void v2PreservesMessagesAndOptionsWithoutConsumingNextCommand() throws Exception {
        String request = packet("want " + WANT) + packet("thin-pack") + packet("have " + HAVE)
                + packet("shallow " + HAVE) + packet("wait-for-done") + packet("done")
                + packet("include-tag") + "0000";
        try (var input = input(request + "NEXT")) {
            FetchRequest result = FetchNegotiator.parseV2Request(input);
            assertThat(result.mode()).isEqualTo(FetchRequest.Mode.PROTOCOL_V2);
            assertThat(result.waitForDone()).isTrue();
            assertThat(result.capabilities()).contains("thin-pack", "include-tag");
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
            FetchRequest result = FetchNegotiator.parseV2Request(input);
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
            assertThat(FetchNegotiator.parseV0Request(input).depth().getAsInt()).isEqualTo(3);
        }
        try (var input = input(packet("want " + WANT) + packet("deepen-relative")
                + packet("deepen 3") + "0000")) {
            assertThat(FetchNegotiator.parseV2Request(input).depth().getAsInt()).isEqualTo(3);
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
                "want-ref refs/heads/.hidden", "want-ref refs/heads/main.lock", "have " + HAVE + "\nextra"}) {
            assertInvalidV2(packet(argument));
        }
        try (var input = input("0000")) {
            assertThatThrownBy(() -> FetchNegotiator.parseV2Request(input)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsMalformedHeadersAndUnexpectedControls() throws Exception {
        for (String header : new String[]{"zzzz", "0003", "ffff", "0001", "0002", "0004"}) {
            try (var input = input(header)) {
                assertThatThrownBy(() -> FetchNegotiator.parseV2Request(input)).isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void rejectsTruncationInsteadOfCompletingRequest() throws Exception {
        for (String truncated : new String[]{"", "00", "000aw", packet("want " + WANT)}) {
            try (var input = input(truncated)) {
                assertThatThrownBy(() -> FetchNegotiator.parseV2Request(input)).isInstanceOf(EOFException.class);
            }
        }
    }

    private static void assertInvalidV2(String arguments) throws Exception {
        try (var input = input(packet("want " + WANT) + arguments + "0000")) {
            assertThatThrownBy(() -> FetchNegotiator.parseV2Request(input)).isInstanceOf(IOException.class);
        }
    }

    private static String packet(String payload) {
        return "%04x".formatted(payload.getBytes(StandardCharsets.UTF_8).length + 4) + payload;
    }

    private static InputStreamBufferedByteInput input(String wire) {
        return new InputStreamBufferedByteInput(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
    }
}
