package pro.deta.orion.agentd.core;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import pro.deta.orion.agent.protocol.AgentAuthentication;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolVersion;
import pro.deta.orion.agent.protocol.JournalFormatVersion;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.ProtocolBytes;

public final class AgentHandshake implements AutoCloseable {
    private AgentConnection connection;

    public AgentMessage.Hello initialHello(
            AgentLaunchContext context,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities
    ) {
        Objects.requireNonNull(context, "context");
        byte[] permit = context.permit().copyBytes();
        try {
            AgentAuthentication authentication = new AgentAuthentication(
                    context.generation(), context.launchId(), AgentAuthentication.Kind.LAUNCH_PERMIT,
                    ProtocolBytes.copyOf(permit));
            return new AgentMessage.Hello(
                    AgentProtocolVersion.CURRENT,
                    JournalFormatVersion.CURRENT,
                    context.agentId(),
                    context.instanceId(),
                    agentVersion,
                    machine,
                    capabilities,
                    Optional.of(authentication));
        } finally {
            Arrays.fill(permit, (byte) 0);
        }
    }

    public synchronized AgentConnection accept(AgentMessage.Welcome welcome) throws HandshakeException {
        Objects.requireNonNull(welcome, "welcome");
        if (!AgentProtocolVersion.CURRENT.equals(welcome.protocolVersion())) {
            throw new HandshakeException("Server selected an unsupported Agent protocol version");
        }
        if (!JournalFormatVersion.CURRENT.equals(welcome.journalFormatVersion())) {
            throw new HandshakeException("Server selected an unsupported journal format version");
        }
        ProtocolBytes encoded = welcome.reconnectToken().orElseThrow(
                () -> new HandshakeException("Server WELCOME is missing reconnect authentication"));
        byte[] token = encoded.toByteArray();
        AgentConnection replacement;
        try {
            replacement = new AgentConnection(
                    welcome.connectionId(), welcome.configuration(), new ReconnectToken(token));
        } finally {
            Arrays.fill(token, (byte) 0);
        }
        AgentConnection previous = connection;
        connection = replacement;
        if (previous != null) {
            previous.close();
        }
        return replacement;
    }

    public synchronized Optional<AgentConnection> connection() {
        return Optional.ofNullable(connection);
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
