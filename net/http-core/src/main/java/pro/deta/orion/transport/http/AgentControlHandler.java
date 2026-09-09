package pro.deta.orion.transport.http;

import pro.deta.orion.agent.protocol.AgentMessage;

import java.util.concurrent.CompletionStage;

/** Application boundary for one transient Agent control stream. */
public interface AgentControlHandler {
    Session open(Connection connection);

    interface Connection {
        CompletionStage<Void> send(AgentMessage message);

        void handshakeComplete();

        void close();
    }

    interface Session {
        void onMessage(AgentMessage message);

        void onClosed(Throwable failure);
    }
}
