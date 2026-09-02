package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentHandshakeTest {
    private static final MachineInfo MACHINE = new MachineInfo("runner-1", "Linux", "aarch64");

    @Test
    void createsAuthenticatedHelloFromServerLaunchContext() {
        AgentLaunchContext context = context();
        AgentMessage.Hello hello = new AgentHandshake().initialHello(
                context, "2.4.1", MACHINE, Map.of("pty", "true"));

        assertThat(hello.protocolVersion()).isEqualTo(AgentProtocolVersion.CURRENT);
        assertThat(hello.journalFormatVersion()).isEqualTo(JournalFormatVersion.CURRENT);
        assertThat(hello.agentId()).isEqualTo(context.agentId());
        assertThat(hello.instanceId()).isEqualTo(context.instanceId());
        assertThat(hello.agentVersion()).isEqualTo("2.4.1");
        assertThat(hello.machine()).isEqualTo(MACHINE);
        assertThat(hello.capabilities()).containsEntry("pty", "true");
        assertThat(hello.authentication()).hasValueSatisfying(authentication -> {
            assertThat(authentication.generation()).isEqualTo(context.generation());
            assertThat(authentication.launchId()).isEqualTo(context.launchId());
            assertThat(authentication.kind()).isEqualTo(AgentAuthentication.Kind.LAUNCH_PERMIT);
            assertThat(authentication.credential().toByteArray()).containsOnly(7);
        });
    }

    @Test
    void acceptsAuthenticatedWelcomeAndClearsReplacedToken() throws Exception {
        AgentHandshake handshake = new AgentHandshake();
        AgentConnection first = handshake.accept(welcome("connection-1", (byte) 11));
        ReconnectToken oldToken = first.reconnectToken();

        AgentConnection replacement = handshake.accept(welcome("connection-2", (byte) 12));

        assertThat(handshake.connection()).contains(replacement);
        assertThat(oldToken.copyBytes()).containsOnly(0);
        assertThat(replacement.reconnectToken().copyBytes()).containsOnly(12);
    }

    @Test
    void rejectsMissingTokenAndUnsupportedVersionWithoutChangingConnection() throws Exception {
        AgentHandshake handshake = new AgentHandshake();
        AgentConnection accepted = handshake.accept(welcome("connection-1", (byte) 11));
        AgentMessage.Welcome missing = new AgentMessage.Welcome(
                AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                new ConnectionId("missing-token"), Map.of());
        AgentMessage.Welcome unsupported = new AgentMessage.Welcome(
                new AgentProtocolVersion(2), JournalFormatVersion.CURRENT,
                new ConnectionId("unsupported"), Map.of(),
                Optional.of(ProtocolBytes.copyOf(new byte[32])));

        assertThatExceptionOfType(HandshakeException.class).isThrownBy(() -> handshake.accept(missing));
        assertThatExceptionOfType(HandshakeException.class).isThrownBy(() -> handshake.accept(unsupported));
        assertThat(handshake.connection()).contains(accepted);
    }

    static AgentLaunchContext context() {
        byte[] permit = new byte[32];
        java.util.Arrays.fill(permit, (byte) 7);
        return new AgentLaunchContext(
                new AgentId("agent-1"), new AgentGeneration(7),
                new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
                new AgentInstanceId(UUID.fromString("8c83ea09-081d-49fd-9f21-43cf93f8039a")),
                new LaunchPermit(permit));
    }

    static AgentMessage.Welcome welcome(String connectionId, byte tokenByte) {
        byte[] token = new byte[32];
        java.util.Arrays.fill(token, tokenByte);
        return new AgentMessage.Welcome(
                AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                new ConnectionId(connectionId), Map.of("heartbeatMillis", "5000"),
                Optional.of(ProtocolBytes.copyOf(token)));
    }
}
