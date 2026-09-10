package pro.deta.orion.agent.server.transport;

import org.eclipse.jetty.http2.api.Session;
import pro.deta.orion.agent.protocol.AgentId;

import java.util.Optional;

@FunctionalInterface
public interface EstablishedAgentContextProvider {
    Optional<AgentId> agentIdFor(Session session);
}
