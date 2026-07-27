package pro.deta.orion.git.client.machine;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.client.GitProtocolService;

public interface GitClientMachine<R> extends AutoCloseable {
    GitProtocolService service();

    GitClientAction<R> action();

    void written();

    /**
     * Feeds the next inbound chunk to this machine.
     *
     * @return {@code true} if the caller should release the buffer;
     *         {@code false} if the machine takes ownership
     */
    boolean accept(ByteBuf input);

    void endOfInput();

    @Override
    void close();
}
