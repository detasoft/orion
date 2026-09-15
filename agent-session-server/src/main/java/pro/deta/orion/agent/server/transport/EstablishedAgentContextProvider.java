package pro.deta.orion.agent.server.transport;

import org.eclipse.jetty.http2.api.Session;
import pro.deta.orion.agent.server.auth.AuthenticatedConnectionContext;

import java.util.Optional;

@FunctionalInterface
public interface EstablishedAgentContextProvider {
    Optional<AuthenticatedConnectionContext> contextFor(Session session);
}
