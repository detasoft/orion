package pro.deta.orion.agentd.transport;

import org.eclipse.jetty.http2.frames.HeadersFrame;
import pro.deta.orion.agent.protocol.SessionId;

/** Caller-owned session stream request contract; transport does not prescribe a server route. */
@FunctionalInterface
public interface SessionStreamRequest {
    HeadersFrame headers(SessionId sessionId);
}
