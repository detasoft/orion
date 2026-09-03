package pro.deta.orion.agent.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SessionEventDecoderTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final SessionEventCodec CODEC = new SessionEventCodec(LIMITS);

    @Test
    void decodesTheSameValuesAcrossChunkBoundaries() throws Exception {
        List<byte[]> encoded = List.of(event(1, new byte[]{1, 2}), event(2, new byte[]{3, 4}));
        byte[] sequence = concatenate(encoded.toArray(byte[][]::new));
        List<SessionEventRecord> expected = List.of(CODEC.decode(encoded.get(0)), CODEC.decode(encoded.get(1)));

        assertThat(decode(sequence, sequence.length)).containsExactlyElementsOf(expected);
        assertThat(decode(sequence, 1)).containsExactlyElementsOf(expected);
        assertThat(decode(sequence, 5)).containsExactlyElementsOf(expected);
    }

    @Test
    void consumesTheSourceRangeAndOwnsReturnedValues() throws Exception {
        byte[] encoded = event(3, new byte[]{1, 2, 3});
        byte[] source = concatenate(new byte[]{99}, encoded, new byte[]{100});
        ByteBuffer input = ByteBuffer.wrap(source, 1, encoded.length).slice();
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);

        SequenceDecodeResult<SessionEventRecord> result = decoder.accept(input);
        java.util.Arrays.fill(source, (byte) 0);

        assertThat(input.position()).isEqualTo(input.limit());
        SessionEventRecord record = decoded(result).getFirst();
        assertThat(record.encodedRecord().toByteArray()).containsExactly(encoded);
        assertThat(record.encodedPayload().toByteArray()).containsExactly(0x43, 1, 2, 3);
    }

    @Test
    void recoversAfterCompleteSemanticFailure() throws Exception {
        byte[] validOne = event(1, new byte[]{1});
        byte[] invalidKnown = Hex.parse("8201190100");
        byte[] validTwo = event(2, new byte[]{2});
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);

        SequenceDecodeResult<SessionEventRecord> result = decoder.accept(
                ByteBuffer.wrap(concatenate(validOne, invalidKnown, validTwo)));

        assertThat(decoded(result)).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1), new EventId(2));
        assertThat(result.outcomes()).hasSize(3);
        SequenceDecodeIssue.Recoverable issue =
                ((SequenceDecodeResult.Rejected<SessionEventRecord>) result.outcomes().get(1)).issue();
        assertThat(issue.exception().reason()).isEqualTo(AgentProtocolException.Reason.INVALID_FIELD);
        assertThat(issue.encodedLength()).isEqualTo(invalidKnown.length);
        assertThat(result.terminalIssue()).isEmpty();
        assertThat(decoded(decoder.accept(ByteBuffer.wrap(validOne)))).hasSize(1);
    }

    @Test
    void returnsPrefixBeforeTerminalFailureAndSupportsReset() throws Exception {
        byte[] valid = event(1, new byte[]{1});
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);

        SequenceDecodeResult<SessionEventRecord> result = decoder.accept(
                ByteBuffer.wrap(concatenate(valid, new byte[]{(byte) 0xff}, valid)));

        assertThat(decoded(result)).extracting(SessionEventRecord::eventId).containsExactly(new EventId(1));
        assertThat(result.terminalIssue()).isPresent();
        assertThatIllegalStateException().isThrownBy(() -> decoder.accept(ByteBuffer.wrap(valid)));

        decoder.reset();

        assertThat(decoded(decoder.accept(ByteBuffer.wrap(valid)))).hasSize(1);
    }

    @Test
    void reportsIncompleteTailOnlyAtFinish() throws Exception {
        byte[] valid = event(1, new byte[]{1, 2});
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);

        SequenceDecodeResult<SessionEventRecord> partial = decoder.accept(
                ByteBuffer.wrap(valid, 0, valid.length - 1));

        assertThat(partial.outcomes()).isEmpty();
        assertThat(partial.terminalIssue()).isEmpty();
        assertThat(decoder.pendingBytes()).isEqualTo(valid.length - 1);
        assertThat(decoder.finish().terminalIssue()).isPresent();
    }

    private static List<SessionEventRecord> decode(byte[] sequence, int chunkSize) {
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);
        List<SessionEventRecord> values = new ArrayList<>();
        for (int position = 0; position < sequence.length; position += chunkSize) {
            int length = Math.min(chunkSize, sequence.length - position);
            values.addAll(decoded(decoder.accept(ByteBuffer.wrap(sequence, position, length))));
        }
        return values;
    }

    private static List<SessionEventRecord> decoded(SequenceDecodeResult<SessionEventRecord> result) {
        List<SessionEventRecord> values = new ArrayList<>();
        for (SequenceDecodeResult.Outcome<SessionEventRecord> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<SessionEventRecord> decoded) {
                values.add(decoded.value());
            }
        }
        return values;
    }

    private static byte[] event(long id, byte[] payload) throws AgentProtocolException {
        return CODEC.encode(new EventId(id), new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(payload)));
    }

    private static byte[] concatenate(byte[]... items) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] item : items) {
            output.writeBytes(item);
        }
        return output.toByteArray();
    }
}
