package pro.deta.orion.agent.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProtocolFixtureTest {
    private static final AgentProtocolLimits LIMITS = AgentProtocolLimits.defaults();
    private static final AgentProtocolCodec AGENT_CODEC = new AgentProtocolCodec(LIMITS);
    private static final SessionEventCodec EVENT_CODEC = new SessionEventCodec(LIMITS);

    @Test
    void helloMatchesVersionOneFixture() throws Exception {
        AgentMessage.Hello hello = new AgentMessage.Hello(
                AgentProtocolVersion.CURRENT,
                JournalFormatVersion.CURRENT,
                new AgentId("agent-1"),
                new AgentInstanceId(UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f")),
                "1.0.0",
                new MachineInfo("host", "linux", "amd64"),
                Map.of("pty", "true"));
        byte[] fixture = fixture("agent-hello-v1.hex");

        assertThat(AGENT_CODEC.encode(hello)).containsExactly(fixture);
        assertThat(AGENT_CODEC.decode(fixture)).isEqualTo(hello);
        assertThat(hello.authentication()).isEqualTo(Optional.empty());
    }

    @Test
    void requiredJournalEventsMatchSharedVersionOneFixture() throws Exception {
        List<byte[]> records = List.of(
                EVENT_CODEC.encode(
                        new EventId(1),
                        new SessionEventPayload.PtyOutput(
                                ProtocolBytes.copyOf(new byte[]{0, 0x1b, (byte) 0xff}))),
                EVENT_CODEC.encode(
                        new EventId(2),
                        new SessionEventPayload.PtyResize(180, 50)),
                EVENT_CODEC.encode(
                        new EventId(3),
                        new SessionEventPayload.PtyInput(
                                new CommandId("00010203-0405-0607-0809-0a0b0c0d0e0f"),
                                ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff}))),
                EVENT_CODEC.encode(
                        new EventId(4),
                        new SessionEventPayload.ProcessExited(0)));
        byte[] fixture = fixture("session-events-v1.hex");

        assertThat(concatenate(records)).containsExactly(fixture);
        SessionEventDecoder decoder = new SessionEventDecoder(LIMITS);
        assertThat(decoder.accept(ByteBuffer.wrap(fixture)).outcomes()).hasSize(4);
    }

    @Test
    void unknownJournalRecordMatchesForwardCompatibilityFixture() throws Exception {
        byte[] fixture = fixture("session-event-unknown-tail-v1.hex");

        SessionEventRecord event = EVENT_CODEC.decode(fixture);

        assertThat(event.eventType()).isEqualTo(0x7ffe);
        assertThat(event.trailingFieldCount()).isOne();
        assertThat(event.encodedRecord().toByteArray()).containsExactly(fixture);
    }

    private static byte[] fixture(String name) throws IOException {
        ClassLoader classLoader = AgentProtocolFixtureTest.class.getClassLoader();
        try (InputStream input = Objects.requireNonNull(
                classLoader.getResourceAsStream("protocol/" + name),
                "missing fixture " + name)) {
            String hex = new String(input.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s", "");
            return HexFormat.of().parseHex(hex);
        }
    }

    private static byte[] concatenate(List<byte[]> items) {
        List<Byte> bytes = new ArrayList<>();
        for (byte[] item : items) {
            for (byte value : item) {
                bytes.add(value);
            }
        }
        byte[] result = new byte[bytes.size()];
        for (int index = 0; index < bytes.size(); index++) {
            result[index] = bytes.get(index);
        }
        return result;
    }
}
