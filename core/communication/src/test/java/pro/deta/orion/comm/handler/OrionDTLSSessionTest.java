package pro.deta.orion.comm.handler;

import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pro.deta.orion.comm.DtlsHandlerVirtualNetworkBridge;
import pro.deta.orion.comm.ProcessorState;
import pro.deta.orion.comm.app.EchoDtlsApplication;
import pro.deta.orion.comm.app.HelloDtlsApplication;
import pro.deta.orion.comm.common.DtlsSessionEndpoint;
import pro.deta.orion.comm.v3.OrionDTLSAsyncHandler;
import pro.deta.orion.test.util.ResourceUtils;
import pro.deta.orion.util.NamedThreadFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class OrionDTLSSessionTest {
    @BeforeAll
    public static void setupLogging() {
        ResourceUtils.configureDefaultLogging();
    }

    @Test
    public void maxMTUAgreementTest() throws UnknownHostException {
        EchoDtlsApplication serverApplication = new EchoDtlsApplication();
        HelloDtlsApplication clientApplication = new HelloDtlsApplication();
        OrionDTLSAsyncHandler<InetSocketAddress> serverHandler = new OrionDTLSAsyncHandler<>(serverApplication, new NamedThreadFactory("server", true));
        OrionDTLSAsyncHandler<InetSocketAddress> clientHandler = new OrionDTLSAsyncHandler<>(clientApplication, new NamedThreadFactory("client", true));

        InetSocketAddress serverSocketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5555);
        InetSocketAddress clientSocketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5555);
        DtlsHandlerVirtualNetworkBridge bridgeServerToClient = new DtlsHandlerVirtualNetworkBridge(serverSocketAddress, serverHandler, clientHandler);
        DtlsHandlerVirtualNetworkBridge bridgeClientToServer = new DtlsHandlerVirtualNetworkBridge(clientSocketAddress, clientHandler, serverHandler);
        clientHandler.channelClientInit(new DtlsSessionEndpoint<>(serverSocketAddress, clientSocketAddress));
    }

    @Test
    public void fakeClientServer() throws UnknownHostException {
        EchoDtlsApplication serverApplication = new EchoDtlsApplication();
        HelloDtlsApplication clientApplication = new HelloDtlsApplication();
        OrionDTLSAsyncHandler<InetSocketAddress> serverHandler = new OrionDTLSAsyncHandler<>(serverApplication, new NamedThreadFactory("server", true));
        OrionDTLSAsyncHandler<InetSocketAddress> clientHandler = new OrionDTLSAsyncHandler<>(clientApplication, new NamedThreadFactory("client", true));

        InetSocketAddress serverSocketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5555);
        InetSocketAddress clientSocketAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5555);
        DtlsHandlerVirtualNetworkBridge bridgeServerToClient = new DtlsHandlerVirtualNetworkBridge(serverSocketAddress, serverHandler, clientHandler);
        DtlsHandlerVirtualNetworkBridge bridgeClientToServer = new DtlsHandlerVirtualNetworkBridge(clientSocketAddress, clientHandler, serverHandler);

        clientHandler.channelClientInit(new DtlsSessionEndpoint<>(serverSocketAddress, clientSocketAddress));

        assertThat(clientApplication.waitFor(ProcessorState.INIT_COMPLETED)).isTrue().describedAs("Client application is not initialized");
        assertThat(serverApplication.waitFor(ProcessorState.INIT_COMPLETED)).isTrue().describedAs("Server application is not initialized");
        assertThat(clientApplication.waitFor(ProcessorState.WRITE_COMPLETED)).isTrue().describedAs("Client application wrote data");
        assertThat(serverApplication.waitFor(ProcessorState.READ_COMPLETED)).isTrue().describedAs("Server application read data");
        assertThat(serverApplication.waitFor(ProcessorState.WRITE_COMPLETED)).isTrue().describedAs("Server application wrote data back");
        assertThat(clientApplication.waitFor(ProcessorState.READ_COMPLETED)).isTrue().describedAs("Client application read data");
        assertThat(clientApplication.getMessage()).isEqualTo(serverApplication.makeResponse(clientApplication.getOriginalMessage()));
    }

    /**
     * Drain elements from the given queue and return them in a new list where items are taken in fixed-size
     * groups and each group is reversed.
     * <p>
     * Behavior details:
     * - The method polls up to the queue's initial size at invocation time; it does not process elements enqueued later.
     * - Items are read in batches of {@code groupSize}; each complete batch is reversed and appended to the result.
     * - If the remaining number of elements is less than {@code groupSize}, that final partial batch is also reversed.
     * - The source {@link ConcurrentLinkedDeque} is consumed (polled) as a side effect.
     * <p>
     * Example:
     * Given queue [1, 2, 3, 4, 5] and {@code groupSize} = 2, the result is [2, 1, 4, 3, 5].
     * Given queue [A, B, C, D, E, F] and {@code groupSize} = 3, the result is [C, B, A, F, E, D].
     * <p>
     * Edge cases:
     * - If {@code groupSize <= 1}, the result preserves the original order (elements are simply drained).
     * - If the queue is empty, an empty list is returned.
     *
     * @param queue     source queue to drain elements from; will be emptied up to its initial size snapshot
     * @param groupSize size of the groups to reverse; should be a positive integer
     * @param <T>       element type
     * @return a list containing the drained elements arranged by reversed groups
     */
    public static <T> List<T> reverseGroups(ConcurrentLinkedDeque<T> queue, int groupSize) {
        List<T> result = new ArrayList<>();
        Deque<T> temp = new ArrayDeque<>(groupSize); // стек для текущей тройки
        int count = 0;

        int size = queue.size(); // фиксируем изначальный размер
        for (int i = 0; i < size; i++) {
            T elem = queue.poll(); // снимаем с головы
            temp.push(elem);          // кладём в стек
            count++;

            if (count == groupSize) {
                while (!temp.isEmpty()) {
                    result.add(temp.pop());
                }
                count = 0;
            }
        }

        while (!temp.isEmpty()) {
            result.add(temp.pop());
        }

        return result;
    }

    @Test
    public void fakeClientServerReversePairsPackets() throws UnknownHostException {
        EchoDtlsApplication serverApplication = new EchoDtlsApplication();
        HelloDtlsApplication clientApplication = new HelloDtlsApplication();
        OrionDTLSAsyncHandler<InetSocketAddress> serverHandler = new OrionDTLSAsyncHandler<>(serverApplication, new NamedThreadFactory("server", true));
        OrionDTLSAsyncHandler<InetSocketAddress> clientHandler = new OrionDTLSAsyncHandler<>(clientApplication, new NamedThreadFactory("client", true));

        InetSocketAddress localServerAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5555);
        InetSocketAddress localClientAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 6666);
        ConcurrentLinkedDeque<BufAddress> clientToServerQueue = new ConcurrentLinkedDeque<>();
        ConcurrentLinkedDeque<BufAddress> serverToClientQueue = new ConcurrentLinkedDeque<>();
        new Thread("network-simulator") {
            public void run() {
                while (true) {
                    while (!clientToServerQueue.isEmpty()) {
                        reverseGroups(clientToServerQueue, 2).forEach((ba) -> serverHandler.channelRead(ba.socketAddress, localServerAddress, ba.data));
                    }
                    log.warn("SERVER STATUS: {}", serverHandler.getStatus());
                    while (!serverToClientQueue.isEmpty()) {
                        reverseGroups(serverToClientQueue, 2).forEach((ba) -> clientHandler.channelRead(ba.socketAddress, localClientAddress, ba.data));
                    }
                    log.warn("CLIENT STATUS: {}", serverHandler.getStatus());
                    LockSupport.parkNanos(10_000_000_0);
                }
            }
        }.start();

        serverHandler.setNetworkConsumer((a,d) -> serverToClientQueue.add(new BufAddress(a, d)));
        clientHandler.setNetworkConsumer((a,d) -> clientToServerQueue.add(new BufAddress(a, d)));


        clientHandler.channelClientInit(new DtlsSessionEndpoint<>(localServerAddress, localClientAddress));
        clientApplication.waitFor(ProcessorState.READ_COMPLETED);

        assertThat(clientApplication.getMessage()).isEqualTo(serverApplication.makeResponse(clientApplication.getOriginalMessage()));
    }

    @RequiredArgsConstructor
    static final class BufAddress {
        private final InetSocketAddress socketAddress;
        private final ByteBuf data;
    }

}
