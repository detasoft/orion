package pro.deta.orion.agent.protocol;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SessionEventCodecTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final SessionEventCodec CODEC = new SessionEventCodec(LIMITS);

    @Test
    void roundTripsRequiredEventPayloads() throws Exception {
        List<SessionEventPayload> payloads = List.of(
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[]{0x1b, 0, (byte) 0xff})),
                new SessionEventPayload.PtyInput(
                        new CommandId("command-1"),
                        ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff})),
                new SessionEventPayload.PtyResize(180, 50),
                new SessionEventPayload.ProcessExited(-17));

        for (int index = 0; index < payloads.size(); index++) {
            SessionEventPayload expected = payloads.get(index);
            SessionEventRecord record = CODEC.decode(CODEC.encode(new EventId(index + 1), expected));

            assertThat(record.eventId()).isEqualTo(new EventId(index + 1));
            assertThat(CODEC.decodeKnownPayload(record)).contains(expected);
            assertThat(record.trailingFieldCount()).isZero();
        }
    }

    @Test
    void preservesUnknownPayloadAndFutureRecordTailExactly() throws Exception {
        byte[] encoded = Hex.parse("8405197ffe44deadbeef66667574757265");

        SessionEventRecord record = CODEC.decode(encoded);

        assertThat(record.eventId()).isEqualTo(new EventId(5));
        assertThat(record.eventType()).isEqualTo(0x7ffe);
        assertThat(record.encodedPayload().toByteArray()).containsExactly(Hex.parse("44deadbeef"));
        assertThat(record.encodedRecord().toByteArray()).containsExactly(encoded);
        assertThat(record.trailingFieldCount()).isOne();
        assertThat(CODEC.decodeKnownPayload(record)).isEmpty();
        assertThat(CODEC.encodeOpaque(
                record.eventId(),
                record.eventType(),
                record.encodedPayload(),
                List.of(ProtocolBytes.copyOf(Hex.parse("66667574757265")))))
                .containsExactly(encoded);
    }

    @Test
    void decodesUnknownPayloadAndFutureTailFromBoundedRange() throws Exception {
        byte[] encoded = Hex.parse("8405197ffe44deadbeef66667574757265");
        byte[] surrounded = new byte[encoded.length + 4];
        java.util.Arrays.fill(surrounded, (byte) 0xff);
        System.arraycopy(encoded, 0, surrounded, 2, encoded.length);

        SessionEventRecord record = CODEC.decode(surrounded, 2, 2 + encoded.length);

        assertThat(record.encodedPayload().toByteArray()).containsExactly(Hex.parse("44deadbeef"));
        assertThat(record.encodedRecord().toByteArray()).containsExactly(encoded);
        assertThat(record.trailingFieldCount()).isOne();
    }

    @Test
    void ignoresFutureFieldsInsideKnownPayloadAndPreservesOuterTail() throws Exception {
        byte[] encoded = Hex.parse("840619010283185018186a7061796c6f61642d7631697265636f72642d7631");

        SessionEventRecord record = CODEC.decode(encoded);

        assertThat(CODEC.decodeKnownPayload(record))
                .contains(new SessionEventPayload.PtyResize(80, 24));
        assertThat(record.trailingFieldCount()).isOne();
        assertThat(record.encodedRecord().toByteArray()).containsExactly(encoded);
    }

    @Test
    void supportsMaximumUnsignedEventId() throws Exception {
        EventId maximum = EventId.fromUnsigned(new BigInteger("18446744073709551615"));
        SessionEventPayload payload = new SessionEventPayload.ProcessExited(0);

        SessionEventRecord decoded = CODEC.decode(CODEC.encode(maximum, payload));

        assertThat(decoded.eventId()).isEqualTo(maximum);
        assertThat(CODEC.decodeKnownPayload(decoded)).contains(payload);
    }

    @Test
    void incrementallyDecodesJournalSequenceAcrossArbitraryChunks() throws Exception {
        List<byte[]> encoded = List.of(
                CODEC.encode(new EventId(1), new SessionEventPayload.PtyOutput(
                        ProtocolBytes.copyOf(new byte[]{1, 2, 3}))),
                CODEC.encode(new EventId(2), new SessionEventPayload.PtyResize(120, 40)),
                Hex.parse("8403197ffe4200ff00"));
        byte[] sequence = concatenate(encoded);
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);
        List<SessionEventRecord> events = new ArrayList<>();

        int position = 0;
        int[] chunkSizes = {1, 7, 2, 11, 3};
        int chunkIndex = 0;
        while (position < sequence.length) {
            int size = Math.min(chunkSizes[chunkIndex++ % chunkSizes.length], sequence.length - position);
            addDecoded(events, decoder.accept(ByteBuffer.wrap(sequence, position, size)));
            position += size;
        }

        assertThat(events).extracting(SessionEventRecord::eventId)
                .containsExactly(new EventId(1), new EventId(2), new EventId(3));
        assertThat(events.get(2).eventType()).isEqualTo(0x7ffe);
        assertThat(events.get(2).trailingFieldCount()).isOne();
        assertThat(decoder.pendingBytes()).isZero();
    }

    @Test
    void retainsIncompleteTailAndRejectsInvalidCompleteItems() throws Exception {
        byte[] event = CODEC.encode(
                new EventId(1),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[]{1, 2, 3})));
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);

        assertThat(decoder.accept(ByteBuffer.wrap(java.util.Arrays.copyOf(event, event.length - 1))).outcomes())
                .isEmpty();
        assertThat(decoder.pendingBytes()).isEqualTo(event.length - 1);
        assertThat(decoder.accept(ByteBuffer.wrap(new byte[]{event[event.length - 1]})).outcomes()).hasSize(1);

        assertThat(decoder.accept(ByteBuffer.wrap(new byte[]{(byte) 0xff})).terminalIssue())
                .get()
                .extracting(issue -> issue.exception().reason())
                .isEqualTo(AgentProtocolException.Reason.MALFORMED_CBOR);
    }

    @Test
    void validatesKnownPayloadShapeAndEventBounds() throws Exception {
        SessionEventRecord invalidResize = CODEC.decode(Hex.parse("83011901028100"));

        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> CODEC.decodeKnownPayload(invalidResize))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(AgentProtocolException.Reason.INVALID_FIELD);
        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> CODEC.decode(Hex.parse("83011a0001000000")))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(AgentProtocolException.Reason.INVALID_FIELD);
    }

    @Test
    void freezesEventTypeAllocation() {
        assertThat(SessionEventType.PTY_OUTPUT).isEqualTo(0x0100);
        assertThat(SessionEventType.PTY_INPUT).isEqualTo(0x0101);
        assertThat(SessionEventType.PTY_RESIZE).isEqualTo(0x0102);
        assertThat(SessionEventType.PROCESS_EXITED).isEqualTo(0x0201);
    }

    private static byte[] concatenate(List<byte[]> items) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        for (byte[] item : items) {
            output.writeBytes(item);
        }
        return output.toByteArray();
    }

    private static void addDecoded(
            List<SessionEventRecord> events,
            SequenceDecodeResult<SessionEventRecord> result
    ) {
        for (SequenceDecodeResult.Outcome<SessionEventRecord> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<SessionEventRecord> decoded) {
                events.add(decoded.value());
            }
        }
    }
}
