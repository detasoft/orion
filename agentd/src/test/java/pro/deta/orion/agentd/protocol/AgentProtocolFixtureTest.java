package pro.deta.orion.agentd.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProtocolFixtureTest {
    private static final AgentProtocolCodec CODEC = new AgentProtocolCodec(AgentProtocolLimits.defaults());

    @Test
    void sessionAcknowledgementMatchesVersionOneFixture() throws Exception {
        AgentMessage.SessionAck message = new AgentMessage.SessionAck(
                new SessionId("session-1"),
                new JournalCursor(123_456_789));

        byte[] fixture = fixture("session-ack-v1.hex");

        assertThat(CODEC.encode(message)).containsExactly(fixture);
        assertThat(CODEC.decode(fixture)).isEqualTo(message);
    }

    @Test
    void opaqueSessionEventMatchesVersionOneFixture() throws Exception {
        AgentMessage.SessionEvents message = new AgentMessage.SessionEvents(
                new SessionId("s-1"),
                List.of(new SessionEventEnvelope(
                        new SessionTimestamp(42),
                        0x4321,
                        0x0017,
                        0x89ab_cdefL,
                        ProtocolBytes.copyOf(new byte[]{0, (byte) 0xff, 0x41}))));

        byte[] fixture = fixture("session-events-opaque-v1.hex");

        assertThat(CODEC.encode(message)).containsExactly(fixture);
        AgentMessage decoded = CODEC.decode(fixture);
        assertThat(decoded).isEqualTo(message);
        AgentMessage.SessionEvents events = (AgentMessage.SessionEvents) decoded;
        assertThat(events.events().getFirst().payload().toByteArray()).containsExactly(0, (byte) 0xff, 0x41);
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
}
