package pro.deta.orion.agentd.transport;

import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.SessionId;

/** Asynchronous transport for protocol messages whose logical identity survives reconnects. */
public interface AgentTransport extends AutoCloseable {
    CompletionStage<Void> connect();

    CompletionStage<Void> sendControlCbor(byte[] item);

    CompletionStage<Void> sendSessionCbor(SessionId sessionId, byte[] rawCborRecord);

    CompletionStage<Void> openSession(SessionId sessionId, SessionStreamRequest request);

    void onControlMessage(Consumer<AgentMessage> receiver);

    void onSessionMessage(BiConsumer<SessionId, AgentMessage> receiver);

    void onSignal(Consumer<TransportSignal> receiver);

    @Override
    void close();
}
