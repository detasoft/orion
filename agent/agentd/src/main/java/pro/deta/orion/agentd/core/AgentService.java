package pro.deta.orion.agentd.core;

public interface AgentService extends AutoCloseable {
    void start() throws Exception;

    @Override
    void close() throws Exception;
}
