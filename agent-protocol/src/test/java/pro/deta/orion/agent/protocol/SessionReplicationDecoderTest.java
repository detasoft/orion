package pro.deta.orion.agent.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SessionReplicationDecoderTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final AgentProtocolCodec MESSAGES = new AgentProtocolCodec(LIMITS);
    private static final SessionEventCodec EVENTS = new SessionEventCodec(LIMITS);
    private static final SessionId SESSION_ID = new SessionId("session-1");

    @Test
    void decodesSplitOpeningAndCoalescedEventRecords() throws Exception {
        AgentMessage.SessionOpen open = open(SESSION_ID);
        byte[] encodedOpen = MESSAGES.encode(open);
        SessionEventRecord first = event(1, new byte[]{1, 2});
        SessionEventRecord second = event(2, new byte[]{3, 4});
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(LIMITS);

        SequenceDecodeResult<SessionReplicationItem> partial = decoder.accept(
                ByteBuffer.wrap(encodedOpen, 0, encodedOpen.length - 1));
        SequenceDecodeResult<SessionReplicationItem> completed = decoder.accept(ByteBuffer.wrap(concatenate(
                Arrays.copyOfRange(encodedOpen, encodedOpen.length - 1, encodedOpen.length),
                first.encodedRecord().toByteArray(), second.encodedRecord().toByteArray())));

        assertThat(partial.outcomes()).isEmpty();
        assertThat(partial.terminalIssue()).isEmpty();
        assertThat(decoded(completed)).containsExactly(
                new SessionReplicationItem.Open(open),
                new SessionReplicationItem.Event(first),
                new SessionReplicationItem.Event(second));
        assertThat(decoder.opened()).isTrue();
        assertThat(decoder.pendingBytes()).isZero();
    }

    @Test
    void preservesUnknownEventAndOptionalTailBytes() throws Exception {
        byte[] unknown = EVENTS.encodeOpaque(
                new EventId(7),
                0x7ffe,
                ProtocolBytes.copyOf(Hex.parse("426869")),
                List.of(ProtocolBytes.copyOf(Hex.parse("a1617801"))));
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(LIMITS);

        SequenceDecodeResult<SessionReplicationItem> result = decoder.accept(
                ByteBuffer.wrap(concatenate(MESSAGES.encode(open(SESSION_ID)), unknown)));

        SessionReplicationItem.Event item = (SessionReplicationItem.Event) decoded(result).get(1);
        assertThat(item.record().eventType()).isEqualTo(0x7ffe);
        assertThat(item.record().trailingFieldCount()).isOne();
        assertThat(item.record().encodedRecord().toByteArray()).containsExactly(unknown);
    }

    @Test
    void rejectsAFirstItemOtherThanSessionOpen() throws Exception {
        byte[] sync = MESSAGES.encode(new AgentMessage.SessionSync(SESSION_ID, Optional.empty()));
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(LIMITS);

        SequenceDecodeResult<SessionReplicationItem> result = decoder.accept(ByteBuffer.wrap(sync));

        assertThat(result.outcomes()).singleElement().isInstanceOf(SequenceDecodeResult.Rejected.class);
        SequenceDecodeIssue.Recoverable issue =
                ((SequenceDecodeResult.Rejected<SessionReplicationItem>) result.outcomes().getFirst()).issue();
        assertThat(issue.exception().reason()).isEqualTo(AgentProtocolException.Reason.INVALID_FIELD);
        assertThat(decoder.opened()).isFalse();
    }

    @Test
    void reportsInvalidEventAfterReturningTheValidOpening() throws Exception {
        byte[] invalidEvent = Hex.parse("8101");
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(LIMITS);

        SequenceDecodeResult<SessionReplicationItem> result = decoder.accept(
                ByteBuffer.wrap(concatenate(MESSAGES.encode(open(SESSION_ID)), invalidEvent)));

        assertThat(result.outcomes()).hasSize(2);
        assertThat(result.outcomes().getFirst()).isEqualTo(
                new SequenceDecodeResult.Decoded<>(new SessionReplicationItem.Open(open(SESSION_ID))));
        assertThat(result.outcomes().get(1)).isInstanceOf(SequenceDecodeResult.Rejected.class);
        SequenceDecodeIssue.Recoverable issue =
                ((SequenceDecodeResult.Rejected<SessionReplicationItem>) result.outcomes().get(1)).issue();
        assertThat(issue.exception().reason()).isEqualTo(AgentProtocolException.Reason.INVALID_FIELD);
        assertThat(result.terminalIssue()).isEmpty();
    }

    @Test
    void boundsAnOversizedPartialRecordAndSupportsReset() throws Exception {
        AgentProtocolLimits limits = new AgentProtocolLimits(16, 8, 8, 16, 4);
        AgentProtocolCodec messages = new AgentProtocolCodec(limits);
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(limits);
        decoder.accept(ByteBuffer.wrap(messages.encode(open(new SessionId("s")))));
        byte[] partialIndefiniteBytes = new byte[17];
        partialIndefiniteBytes[0] = 0x5f;
        partialIndefiniteBytes[1] = 0x4f;

        SequenceDecodeResult<SessionReplicationItem> result = decoder.accept(
                ByteBuffer.wrap(partialIndefiniteBytes));

        assertThat(result.terminalIssue()).isPresent();
        assertThat(result.terminalIssue().orElseThrow().exception().reason())
                .isEqualTo(AgentProtocolException.Reason.LIMIT_EXCEEDED);
        assertThatIllegalStateException().isThrownBy(
                () -> decoder.accept(ByteBuffer.wrap(new byte[]{(byte) 0x80})));

        decoder.reset();

        assertThat(decoder.opened()).isFalse();
        assertThat(decoded(decoder.accept(ByteBuffer.wrap(messages.encode(open(new SessionId("s")))))))
                .containsExactly(new SessionReplicationItem.Open(open(new SessionId("s"))));
    }

    @Test
    void reportsIncompleteEventOnlyWhenTheStreamFinishes() throws Exception {
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(LIMITS);
        SessionEventRecord event = event(3, new byte[]{5, 6});
        byte[] encodedEvent = event.encodedRecord().toByteArray();
        decoder.accept(ByteBuffer.wrap(MESSAGES.encode(open(SESSION_ID))));

        SequenceDecodeResult<SessionReplicationItem> partial = decoder.accept(
                ByteBuffer.wrap(encodedEvent, 0, encodedEvent.length - 1));
        SequenceDecodeResult<SessionReplicationItem> finished = decoder.finish();

        assertThat(partial.outcomes()).isEmpty();
        assertThat(partial.terminalIssue()).isEmpty();
        assertThat(finished.terminalIssue()).isPresent();
        assertThat(finished.terminalIssue().orElseThrow().pendingBytes())
                .isEqualTo(encodedEvent.length - 1);
    }

    @Test
    void rejectsEndOfStreamBeforeSessionOpen() throws Exception {
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(LIMITS);

        SequenceDecodeResult<SessionReplicationItem> result = decoder.finish();

        assertThat(result.terminalIssue()).isPresent();
        assertThat(result.terminalIssue().orElseThrow().exception().reason())
                .isEqualTo(AgentProtocolException.Reason.INVALID_FIELD);
        assertThat(result.terminalIssue().orElseThrow().pendingBytes()).isZero();
        assertThatIllegalStateException().isThrownBy(
                () -> decoder.accept(ByteBuffer.wrap(MESSAGES.encode(open(SESSION_ID)))));
        assertThatIllegalStateException().isThrownBy(decoder::finish);

        decoder.reset();

        assertThat(decoded(decoder.accept(ByteBuffer.wrap(MESSAGES.encode(open(SESSION_ID))))))
                .containsExactly(new SessionReplicationItem.Open(open(SESSION_ID)));
    }

    @Test
    void acceptsEndOfStreamAfterSessionOpen() throws Exception {
        SessionReplicationDecoder decoder = new SessionReplicationDecoder(LIMITS);
        decoder.accept(ByteBuffer.wrap(MESSAGES.encode(open(SESSION_ID))));

        SequenceDecodeResult<SessionReplicationItem> result = decoder.finish();

        assertThat(result.outcomes()).isEmpty();
        assertThat(result.terminalIssue()).isEmpty();
    }

    private static AgentMessage.SessionOpen open(SessionId sessionId) {
        return new AgentMessage.SessionOpen(
                sessionId, Optional.of(new EventId(1)), Optional.of(new EventId(3)),
                AgentMessage.SessionState.RUNNING);
    }

    private static SessionEventRecord event(long eventId, byte[] payload) throws AgentProtocolException {
        return EVENTS.decode(EVENTS.encode(
                new EventId(eventId),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(payload))));
    }

    private static List<SessionReplicationItem> decoded(
            SequenceDecodeResult<SessionReplicationItem> result) {
        List<SessionReplicationItem> items = new ArrayList<>();
        for (SequenceDecodeResult.Outcome<SessionReplicationItem> outcome : result.outcomes()) {
            if (outcome instanceof SequenceDecodeResult.Decoded<SessionReplicationItem> decoded) {
                items.add(decoded.value());
            }
        }
        return items;
    }

    private static byte[] concatenate(byte[]... items) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] item : items) {
            output.writeBytes(item);
        }
        return output.toByteArray();
    }
}
