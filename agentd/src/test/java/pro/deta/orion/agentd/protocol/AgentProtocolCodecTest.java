package pro.deta.orion.agentd.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentProtocolCodecTest {
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());
    private static final AgentId AGENT_ID = new AgentId("agent-01KABC");
    private static final InstanceId INSTANCE_ID = new InstanceId(
            UUID.fromString("12345678-1234-5678-90ab-cdef01234567"));
    private static final SessionId SESSION_ID = new SessionId("session-01KDEF");
    private static final CommandId COMMAND_ID = new CommandId("command-01KGHI");

    @ParameterizedTest
    @MethodSource("knownMessages")
    void roundTripsEveryKnownMessage(AgentMessage message) throws Exception {
        assertThat(CODEC.decode(CODEC.encode(message))).isEqualTo(message);
    }

    @Test
    void preservesUnknownMessagePayload() throws Exception {
        AgentMessage.Unknown message = new AgentMessage.Unknown(
                0x7ffe,
                ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff, 3, 4}));

        byte[] encoded = CODEC.encode(message);

        assertThat(CODEC.decode(encoded)).isEqualTo(message);
        assertThat(CODEC.encode(CODEC.decode(encoded))).containsExactly(encoded);
    }

    @Test
    void ignoresUnknownFieldsInKnownMessages() throws Exception {
        AgentMessage.SessionAck message = new AgentMessage.SessionAck(SESSION_ID, new JournalCursor(42));
        byte[] withUnknownField = appendField(CODEC.encode(message), 0x7ffe, new byte[]{1, 2, 3});

        assertThat(CODEC.decode(withUnknownField)).isEqualTo(message);
    }

    @Test
    void rejectsMalformedAndOversizedDeclaredLengthsBeforeReadingPayload() throws Exception {
        byte[] truncated = CODEC.encode(new AgentMessage.SessionAck(SESSION_ID, new JournalCursor(42)));
        truncated = java.util.Arrays.copyOf(truncated, truncated.length - 1);

        assertProtocolFailure(truncated, AgentProtocolException.Reason.MALFORMED_FRAME);

        byte[] oversized = CODEC.encode(new AgentMessage.SessionAck(SESSION_ID, new JournalCursor(42)));
        ByteBuffer.wrap(oversized).putInt(12, Integer.MAX_VALUE);

        assertProtocolFailure(oversized, AgentProtocolException.Reason.LIMIT_EXCEEDED);
    }

    @Test
    void rejectsUnsupportedProtocolVersion() throws Exception {
        byte[] encoded = CODEC.encode(new AgentMessage.SessionAck(SESSION_ID, new JournalCursor(42)));
        ByteBuffer.wrap(encoded).putShort(6, (short) 2);

        assertProtocolFailure(encoded, AgentProtocolException.Reason.UNSUPPORTED_VERSION);
    }

    @Test
    void rejectsDuplicateRequiredFieldsAndInvalidUtf8() throws Exception {
        byte[] encoded = CODEC.encode(new AgentMessage.SessionAck(SESSION_ID, new JournalCursor(42)));
        byte[] duplicate = appendField(encoded, 1, "another-session".getBytes(StandardCharsets.UTF_8));

        assertProtocolFailure(duplicate, AgentProtocolException.Reason.DUPLICATE_FIELD);

        encoded[22] = (byte) 0xc3;
        encoded[23] = 0x28;
        assertProtocolFailure(encoded, AgentProtocolException.Reason.INVALID_FIELD);
    }

    @Test
    void enforcesConfiguredBinaryLimit() {
        AgentProtocolLimits limits = new AgentProtocolLimits(256, 32, 8, 64, 4);
        AgentProtocolCodec codec = new AgentProtocolCodec(limits);
        AgentMessage.Input message = new AgentMessage.Input(
                COMMAND_ID,
                SESSION_ID,
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                ProtocolBytes.copyOf(new byte[]{1, 2, 3, 4, 5}));

        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> codec.encode(message))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(AgentProtocolException.Reason.LIMIT_EXCEEDED);
    }

    @Test
    void mapEncodingIsCanonical() throws Exception {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("z-last", "2");
        first.put("a-first", "1");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("a-first", "1");
        second.put("z-last", "2");

        AgentMessage.Hello firstMessage = hello(first);
        AgentMessage.Hello secondMessage = hello(second);

        assertThat(CODEC.encode(firstMessage)).containsExactly(CODEC.encode(secondMessage));
    }

    @Test
    void protocolBytesAreDefensiveAndContentComparable() {
        byte[] source = new byte[]{1, 2, 3};
        ProtocolBytes bytes = ProtocolBytes.copyOf(source);
        source[0] = 9;
        byte[] returned = bytes.toByteArray();
        returned[1] = 9;

        assertThat(bytes).isEqualTo(ProtocolBytes.copyOf(new byte[]{1, 2, 3}));
        assertThat(bytes.toByteArray()).containsExactly(1, 2, 3);
        assertThat(bytes.toString()).isEqualTo("ProtocolBytes[size=3]");
    }

    private static Stream<AgentMessage> knownMessages() {
        MachineInfo machine = new MachineInfo("worker-1", "linux", "aarch64");
        SessionEventEnvelope firstEvent = new SessionEventEnvelope(
                new SessionTimestamp(10), 0x0100, 1, 0, ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff}));
        SessionEventEnvelope secondEvent = new SessionEventEnvelope(
                new SessionTimestamp(11), 0x4321, 9, 0x8000_0000L, ProtocolBytes.copyOf(new byte[]{4, 5}));

        return Stream.of(
                hello(Map.of("pty", "true", "landlock", "4")),
                new AgentMessage.Welcome(
                        AgentProtocolVersion.CURRENT,
                        new ConnectionId("connection-01KJKL"),
                        Map.of("heartbeatMillis", "10000")),
                new AgentMessage.Heartbeat(AGENT_ID, INSTANCE_ID, 1_788_250_000_000L),
                new AgentMessage.AgentStatus(
                        AGENT_ID,
                        INSTANCE_ID,
                        "1.0.0",
                        machine,
                        3,
                        Map.of("cpuPercent", "12.5", "memoryAvailableBytes", "4096"),
                        Map.of("pty", "true")),
                new AgentMessage.SessionStatus(
                        SESSION_ID, AgentMessage.SessionState.DEGRADED, "journal tail is incomplete"),
                new AgentMessage.CommandResult(
                        COMMAND_ID,
                        Optional.of(SESSION_ID),
                        AgentMessage.CommandOutcome.SUCCEEDED,
                        "accepted"),
                new AgentMessage.StartSession(
                        COMMAND_ID,
                        SESSION_ID,
                        Optional.of(new WorkspaceId("workspace-01KMNO")),
                        List.of("/bin/bash", "-l"),
                        "/workspace",
                        Map.of("TERM", "xterm-256color"),
                        120,
                        40,
                        "landlock-strict",
                        "native"),
                new AgentMessage.Input(
                        COMMAND_ID,
                        SESSION_ID,
                        UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                        ProtocolBytes.copyOf(new byte[]{'p', 'w', 'd', '\r'})),
                new AgentMessage.Resize(COMMAND_ID, SESSION_ID, 160, 48),
                new AgentMessage.Signal(COMMAND_ID, SESSION_ID, AgentMessage.SignalKind.INTERRUPT, -1),
                new AgentMessage.Terminate(
                        COMMAND_ID, SESSION_ID, AgentMessage.TerminationMode.GRACEFUL, 5_000),
                new AgentMessage.SessionEvents(SESSION_ID, List.of(firstEvent, secondEvent)),
                new AgentMessage.SessionAck(SESSION_ID, new JournalCursor(11)),
                new AgentMessage.ResumeSession(SESSION_ID, JournalCursor.BEFORE_FIRST),
                new AgentMessage.SessionGap(
                        SESSION_ID, new JournalCursor(11), new SessionTimestamp(100)));
    }

    private static AgentMessage.Hello hello(Map<String, String> capabilities) {
        return new AgentMessage.Hello(
                AgentProtocolVersion.CURRENT,
                AGENT_ID,
                INSTANCE_ID,
                "1.0.0",
                new MachineInfo("worker-1", "linux", "aarch64"),
                capabilities);
    }

    private static byte[] appendField(byte[] frame, int tag, byte[] value) {
        int oldPayloadLength = ByteBuffer.wrap(frame).getInt(12);
        byte[] result = java.util.Arrays.copyOf(frame, frame.length + 6 + value.length);
        ByteBuffer.wrap(result).putInt(12, oldPayloadLength + 6 + value.length);
        ByteBuffer field = ByteBuffer.wrap(result, frame.length, 6 + value.length);
        field.putShort((short) tag);
        field.putInt(value.length);
        field.put(value);
        return result;
    }

    private static void assertProtocolFailure(byte[] frame, AgentProtocolException.Reason reason) {
        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> CODEC.decode(frame))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(reason);
    }
}
