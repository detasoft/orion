package pro.deta.orion.agentd.core;

import java.util.Map;
import java.util.Objects;

import pro.deta.orion.agent.protocol.ConnectionId;

public final class AgentConnection implements AutoCloseable {
    private final ConnectionId connectionId;
    private final Map<String, String> configuration;
    private final ReconnectToken reconnectToken;

    public AgentConnection(
            ConnectionId connectionId, Map<String, String> configuration, ReconnectToken reconnectToken) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.configuration = Map.copyOf(configuration);
        this.reconnectToken = Objects.requireNonNull(reconnectToken, "reconnectToken");
    }

    public ConnectionId connectionId() {
        return connectionId;
    }

    public Map<String, String> configuration() {
        return configuration;
    }

    public ReconnectToken reconnectToken() {
        return reconnectToken;
    }

    @Override
    public void close() {
        reconnectToken.close();
    }
}
