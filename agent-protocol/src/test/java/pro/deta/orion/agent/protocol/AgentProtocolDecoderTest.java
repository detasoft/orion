package pro.deta.orion.agent.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AgentProtocolDecoderTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(LIMITS);
    private static final SessionId SESSION_ID = new SessionId("session-1");

    @Test
    void decodesTheSameValuesAcrossChunkBoundaries() throws Exception {
        List<AgentMessage> expected = List.of(
                new AgentMessage.RequestSessionList(),
                new AgentMessage.SessionSync(SESSION_ID, Optional.of(new EventId(7))));
        byte[] sequence = concatenate(CODEC.encode(expected.get(0)), CODEC.encode(expected.get(1)));

        assertThat(decode(sequence, sequence.length)).containsExactlyElementsOf(expected);
        assertThat(decode(sequence, 1)).containsExactlyElementsOf(expected);
        assertThat(decode(sequence, 3)).containsExactlyElementsOf(expected);
    }

    @Test
    void consumesTheSourceRangeAndOwnsReturnedValues() throws Exception {
        AgentMessage.Input expected = new AgentMessage.Input(
                new CommandId("command-1"),
                SESSION_ID,
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                ProtocolBytes.copyOf(new byte[]{1, 2, 3}));
        byte[] encoded = CODEC.encode(expected);
        byte[] source = concatenate(new byte[]{99}, encoded, new byte[]{100});
        ByteBuffer input = ByteBuffer.wrap(source, 1, encoded.length).slice();
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(LIMITS);

        SequenceDecodeResult<AgentMessage> result = decoder.accept(input);
        java.util.Arrays.fill(source, (byte) 0);

        assertThat(input.position()).isEqualTo(input.limit());
        assertThat(decoded(result)).containsExactly(expected);
    }

    @Test
    void returnsRecoverableFailureBetweenValidMessagesAndRemainsUsable() throws Exception {
        byte[] valid = CODEC.encode(new AgentMessage.RequestSessionList());
        byte[] invalidKnown = Hex.parse("81198001");
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(LIMITS);

        SequenceDecodeResult<AgentMessage> result = decoder.accept(
                ByteBuffer.wrap(concatenate(valid, invalidKnown, valid)));

        assertThat(decoded(result)).containsExactly(
                new AgentMessage.RequestSessionList(), new AgentMessage.RequestSessionList());
        assertThat(result.outcomes()).hasSize(3);
        assertThat(result.outcomes().get(1)).isInstanceOf(SequenceDecodeResult.Rejected.class);
        SequenceDecodeIssue.Recoverable issue =
                ((SequenceDecodeResult.Rejected<AgentMessage>) result.outcomes().get(1)).issue();
        assertThat(issue.exception().reason()).isEqualTo(AgentProtocolException.Reason.MISSING_FIELD);
        assertThat(issue.encodedLength()).isEqualTo(invalidKnown.length);
        assertThat(result.terminalIssue()).isEmpty();
        assertThat(decoded(decoder.accept(ByteBuffer.wrap(valid))))
                .containsExactly(new AgentMessage.RequestSessionList());
    }

    @Test
    void returnsValidPrefixWithTerminalFailureAndRequiresReset() throws Exception {
        byte[] valid = CODEC.encode(new AgentMessage.RequestSessionList());
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(LIMITS);

        SequenceDecodeResult<AgentMessage> result = decoder.accept(
                ByteBuffer.wrap(concatenate(valid, new byte[]{(byte) 0xff}, valid)));

        assertThat(decoded(result)).containsExactly(new AgentMessage.RequestSessionList());
        assertThat(result.terminalIssue()).isPresent();
        assertThat(result.terminalIssue().orElseThrow().exception().reason())
                .isEqualTo(AgentProtocolException.Reason.MALFORMED_CBOR);
        assertThatIllegalStateException().isThrownBy(() -> decoder.accept(ByteBuffer.wrap(valid)));

        decoder.reset();

        assertThat(decoded(decoder.accept(ByteBuffer.wrap(valid))))
                .containsExactly(new AgentMessage.RequestSessionList());
    }

    @Test
    void waitsForIncompleteInputAndReportsTruncationAtFinish() throws Exception {
        byte[] valid = CODEC.encode(new AgentMessage.SessionSync(SESSION_ID, Optional.empty()));
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(LIMITS);

        SequenceDecodeResult<AgentMessage> partial = decoder.accept(
                ByteBuffer.wrap(valid, 0, valid.length - 1));

        assertThat(partial.outcomes()).isEmpty();
        assertThat(partial.terminalIssue()).isEmpty();
        assertThat(decoder.pendingBytes()).isEqualTo(valid.length - 1);
        SequenceDecodeResult<AgentMessage> finished = decoder.finish();
        assertThat(finished.terminalIssue()).isPresent();
        assertThat(finished.terminalIssue().orElseThrow().pendingBytes()).isEqualTo(valid.length - 1);
        assertThat(finished.terminalIssue().orElseThrow().exception().reason())
                .isEqualTo(AgentProtocolException.Reason.MALFORMED_CBOR);
    }

    @Test
    void acceptsStructuralFormsAndRejectsMalformedForms() {
        AgentProtocolLimits limits = new AgentProtocolLimits(64, 8, 16, 16, 3);
        List<byte[]> completeSemanticFailures = List.of(
                Hex.parse("9f01ff"),
                Hex.parse("bf0102ff"),
                Hex.parse("5f4101ff"),
                Hex.parse("7f6161ff"),
                Hex.parse("82018102"));
        for (byte[] item : completeSemanticFailures) {
            SequenceDecodeResult<AgentMessage> result = new AgentProtocolDecoder(limits)
                    .accept(ByteBuffer.wrap(item));
            assertThat(result.outcomes()).hasSize(1);
            assertThat(result.terminalIssue()).isEmpty();
        }

        List<byte[]> malformed = List.of(
                Hex.parse("1c"),
                Hex.parse("ff"),
                Hex.parse("bf01ff"),
                Hex.parse("5f6101ff"),
                Hex.parse("818181818100"));
        for (byte[] item : malformed) {
            SequenceDecodeResult<AgentMessage> result = new AgentProtocolDecoder(limits)
                    .accept(ByteBuffer.wrap(item));
            assertThat(result.terminalIssue()).isPresent();
        }
    }

    @Test
    void boundsOneIncompleteItemButAllowsLargeCoalescedSequences() throws Exception {
        AgentProtocolLimits limits = new AgentProtocolLimits(8, 8, 4, 4, 4);
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(limits);
        byte[] item = CODEC.encode(new AgentMessage.RequestSessionList());
        byte[][] items = new byte[20][];
        java.util.Arrays.fill(items, item);

        SequenceDecodeResult<AgentMessage> coalesced = decoder.accept(ByteBuffer.wrap(concatenate(items)));

        assertThat(decoded(coalesced)).hasSize(20);
        assertThat(decoder.pendingBytes()).isZero();

        SequenceDecodeResult<AgentMessage> oversized = decoder.accept(
                ByteBuffer.wrap(Hex.parse("5f4741424344454647")));
        assertThat(oversized.terminalIssue()).isPresent();
        assertThat(oversized.terminalIssue().orElseThrow().exception().reason())
                .isEqualTo(AgentProtocolException.Reason.LIMIT_EXCEEDED);
    }

    private static List<AgentMessage> decode(byte[] sequence, int chunkSize) {
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(LIMITS);
        List<AgentMessage> values = new ArrayList<>();
        for (int position = 0; position < sequence.length; position += chunkSize) {
            int length = Math.min(chunkSize, sequence.length - position);
            values.addAll(decoded(decoder.accept(ByteBuffer.wrap(sequence, position, length))));
        }
        return values;
    }

    private static List<AgentMessage> decoded(SequenceDecodeResult<AgentMessage> result) {
        List<AgentMessage> values = new ArrayList<>();
        for (SequenceDecodeResult.Outcome<AgentMessage> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<AgentMessage> decoded) {
                values.add(decoded.value());
            }
        }
        return values;
    }

    private static byte[] concatenate(byte[]... items) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] item : items) {
            output.writeBytes(item);
        }
        return output.toByteArray();
    }
}
