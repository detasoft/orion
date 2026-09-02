package pro.deta.orion.agent.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentProtocolCodecTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(LIMITS);
    private static final AgentId AGENT_ID = new AgentId("agent-01KABC");
    private static final AgentInstanceId INSTANCE_ID = new AgentInstanceId(
            UUID.fromString("12345678-1234-5678-90ab-cdef01234567"));
    private static final SessionId SESSION_ID = new SessionId("session-01KDEF");
    private static final CommandId COMMAND_ID = new CommandId("command-01KGHI");

    @ParameterizedTest
    @MethodSource("knownMessages")
    void roundTripsEveryKnownMessage(AgentMessage message) throws Exception {
        assertThat(CODEC.decode(CODEC.encode(message))).isEqualTo(message);
    }

    @Test
    void ignoresFutureFieldsInKnownMessageAndNestedStructures() throws Exception {
        byte[] hello = CODEC.encode(hello(Map.of("pty", "true")));
        byte[] futureTail = Hex.parse("d82a9f01ff");
        byte[] withTail = java.util.Arrays.copyOf(hello, hello.length + futureTail.length);
        withTail[0] = (byte) ((hello[0] & 0xff) + 1);
        System.arraycopy(futureTail, 0, withTail, hello.length, futureTail.length);

        assertThat(CODEC.decode(withTail)).isEqualTo(hello(Map.of("pty", "true")));
    }

    @Test
    void preservesUnknownMessageItemExactly() throws Exception {
        byte[] encoded = Hex.parse("83197ffe4200ff66667574757265");

        AgentMessage decoded = CODEC.decode(encoded);

        assertThat(decoded).isEqualTo(new AgentMessage.Unknown(0x7ffe, ProtocolBytes.copyOf(encoded)));
        assertThat(CODEC.encode(decoded)).containsExactly(encoded);
    }

    @Test
    void leavesUnknownMessagePayloadOpaqueToKnownFieldLimits() throws Exception {
        byte[] encoded = Hex.parse("82197ffe450102030405");
        AgentProtocolCodec codec = new AgentProtocolCodec(
                new AgentProtocolLimits(64, 8, 4, 4, 8));

        AgentMessage decoded = codec.decode(encoded);

        assertThat(decoded).isEqualTo(new AgentMessage.Unknown(0x7ffe, ProtocolBytes.copyOf(encoded)));
        assertThat(codec.encode(decoded)).containsExactly(encoded);
    }

    @Test
    void skipsKnownMessageTailWithoutApplyingVersionOneFieldLimits() throws Exception {
        byte[] encoded = Hex.parse("82198002450102030405");
        AgentProtocolCodec codec = new AgentProtocolCodec(
                new AgentProtocolLimits(64, 8, 4, 4, 8));

        assertThat(codec.decode(encoded)).isEqualTo(new AgentMessage.RequestSessionList());
    }

    @Test
    void incrementallyDecodesMessagesAcrossEveryByteBoundary() throws Exception {
        List<AgentMessage> expected = List.of(
                hello(Map.of("pty", "true")),
                new AgentMessage.SessionOpen(
                        SESSION_ID,
                        Optional.of(new EventId(3)),
                        Optional.of(new EventId(9)),
                        AgentMessage.SessionState.RUNNING),
                new AgentMessage.SessionSync(SESSION_ID, Optional.of(new EventId(7))));
        byte[] sequence = concatenate(expected);
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(LIMITS);
        List<AgentMessage> actual = new ArrayList<>();

        for (byte value : sequence) {
            actual.addAll(decoder.accept(new byte[]{value}));
        }

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(decoder.pendingBytes()).isZero();
    }

    @Test
    void waitsForPartialItemAndRejectsMalformedCbor() throws Exception {
        AgentProtocolDecoder decoder = new AgentProtocolDecoder(LIMITS);
        byte[] encoded = CODEC.encode(new AgentMessage.RequestSessionList());

        assertThat(decoder.accept(java.util.Arrays.copyOf(encoded, encoded.length - 1))).isEmpty();
        assertThat(decoder.pendingBytes()).isEqualTo(encoded.length - 1);
        assertThat(decoder.accept(new byte[]{encoded[encoded.length - 1]}))
                .containsExactly(new AgentMessage.RequestSessionList());

        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> decoder.accept(new byte[]{(byte) 0xff}))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(AgentProtocolException.Reason.MALFORMED_CBOR);
    }

    @Test
    void rejectsUnsupportedNegotiatedVersions() throws Exception {
        byte[] encoded = CODEC.encode(hello(Map.of()));
        encoded[2] = 2;

        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> CODEC.decode(encoded))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(AgentProtocolException.Reason.UNSUPPORTED_VERSION);
    }

    @Test
    void enforcesConfiguredBinaryAndCollectionLimits() {
        AgentProtocolLimits limits = new AgentProtocolLimits(256, 2, 64, 4, 16);
        AgentProtocolCodec codec = new AgentProtocolCodec(limits);
        AgentMessage.Input input = new AgentMessage.Input(
                COMMAND_ID,
                SESSION_ID,
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                ProtocolBytes.copyOf(new byte[]{1, 2, 3, 4, 5}));

        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> codec.encode(input))
                .extracting(AgentProtocolException::reason)
                .isEqualTo(AgentProtocolException.Reason.LIMIT_EXCEEDED);
        assertThatExceptionOfType(AgentProtocolException.class)
                .isThrownBy(() -> codec.encode(new AgentMessage.SessionList(List.of(
                        session("a"), session("b"), session("c")))))
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

        assertThat(CODEC.encode(hello(first))).containsExactly(CODEC.encode(hello(second)));
    }

    @Test
    void freezesMessageTypeAllocationAndDirections() {
        assertThat(AgentMessageType.HELLO.code()).isEqualTo(0x0001);
        assertThat(AgentMessageType.SESSION_LIST.code()).isEqualTo(0x0006);
        assertThat(AgentMessageType.SESSION_OPEN.code()).isEqualTo(0x0010);
        assertThat(AgentMessageType.WELCOME.code()).isEqualTo(0x8001);
        assertThat(AgentMessageType.REQUEST_SESSION_LIST.code()).isEqualTo(0x8002);
        assertThat(AgentMessageType.START_SESSION.code()).isEqualTo(0x8100);
        assertThat(AgentMessageType.SESSION_SYNC.code()).isEqualTo(0x8110);
        assertThat(AgentMessageType.SESSION_OPEN.direction())
                .isEqualTo(AgentMessageType.Direction.AGENT_TO_SERVER);
        assertThat(AgentMessageType.SESSION_SYNC.direction())
                .isEqualTo(AgentMessageType.Direction.SERVER_TO_AGENT);
    }

    @Test
    void eventIdSupportsTheFullUnsignedRange() {
        EventId maximum = EventId.fromUnsigned(new java.math.BigInteger("18446744073709551615"));

        assertThat(maximum.value()).isEqualTo(-1L);
        assertThat(maximum.toUnsignedBigInteger()).isEqualTo(new java.math.BigInteger("18446744073709551615"));
        assertThat(maximum).isGreaterThan(new EventId(Long.MAX_VALUE));
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
        SessionDescriptor running = new SessionDescriptor(
                SESSION_ID,
                AgentMessage.SessionState.RUNNING,
                Optional.of(new EventId(4)),
                Optional.of(new EventId(12)),
                "connected");

        return Stream.of(
                hello(Map.of("pty", "true", "landlock", "4")),
                new AgentMessage.Welcome(
                        AgentProtocolVersion.CURRENT,
                        JournalFormatVersion.CURRENT,
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
                new AgentMessage.SessionStatus(running),
                new AgentMessage.CommandResult(
                        COMMAND_ID,
                        Optional.of(SESSION_ID),
                        AgentMessage.CommandOutcome.SUCCEEDED,
                        "accepted"),
                new AgentMessage.SessionList(List.of(running)),
                new AgentMessage.RequestSessionList(),
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
                new AgentMessage.Signal(COMMAND_ID, SESSION_ID, AgentMessage.SignalKind.PLATFORM, 9),
                new AgentMessage.Terminate(
                        COMMAND_ID, SESSION_ID, AgentMessage.TerminationMode.GRACEFUL, 5_000),
                new AgentMessage.SessionOpen(
                        SESSION_ID,
                        Optional.of(new EventId(4)),
                        Optional.of(new EventId(12)),
                        AgentMessage.SessionState.RUNNING),
                new AgentMessage.SessionOpen(
                        new SessionId("empty-session"),
                        Optional.empty(),
                        Optional.empty(),
                        AgentMessage.SessionState.STARTING),
                new AgentMessage.SessionSync(SESSION_ID, Optional.of(new EventId(11))),
                new AgentMessage.SessionSync(new SessionId("new-session"), Optional.empty()));
    }

    private static AgentMessage.Hello hello(Map<String, String> capabilities) {
        return new AgentMessage.Hello(
                AgentProtocolVersion.CURRENT,
                JournalFormatVersion.CURRENT,
                AGENT_ID,
                INSTANCE_ID,
                "1.0.0",
                new MachineInfo("worker-1", "linux", "aarch64"),
                capabilities);
    }

    private static SessionDescriptor session(String id) {
        return new SessionDescriptor(
                new SessionId(id),
                AgentMessage.SessionState.RUNNING,
                Optional.empty(),
                Optional.empty(),
                "");
    }

    private static byte[] concatenate(List<AgentMessage> messages) throws AgentProtocolException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        for (AgentMessage message : messages) {
            output.writeBytes(CODEC.encode(message));
        }
        return output.toByteArray();
    }
}
