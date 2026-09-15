package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.server.connection.AgentControlHandler;

import java.util.Map;
import java.util.UUID;

/** Supplies identity only to tests of transport buffering and shutdown without application authentication. */
public final class HttpTestConnectionContext {
    private HttpTestConnectionContext() {
    }

    public static AuthenticatedConnectionContext forConnection(AgentControlHandler.Connection connection) {
        return new AuthenticatedConnectionContext(new AgentLabel("transport-test"), new AgentGeneration(1),
                new AgentLaunchId(UUID.randomUUID()), new AgentInstanceId(UUID.randomUUID()), "1.0",
                new MachineInfo("host", "test", "test"), Map.of(), new ConnectionId(UUID.randomUUID().toString()),
                connection, () -> AuthenticatedConnectionContext.RenewalResult.RENEWED,
                (version, machine, capabilities, observedAt) ->
                        AuthenticatedConnectionContext.ObservationResult.RECORDED, () -> () -> { });
    }
}
