package pro.deta.orion.agentd.transport;

import pro.deta.orion.agent.protocol.SessionId;

/** Connection and stream events emitted without blocking Jetty callback threads. */
public record TransportSignal(Kind kind, SessionId sessionId, Throwable failure) {
    public enum Kind { CONNECTED, DISCONNECTED, GO_AWAY, STREAM_RESET, CLOSED }

    public TransportSignal(Kind kind, Throwable failure) {
        this(kind, null, failure);
    }

    public TransportSignal {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
    }
}
