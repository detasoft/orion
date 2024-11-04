package pro.deta.orion.comm.v3;

import io.netty.buffer.ByteBuf;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.comm.DtlsApplication;
import pro.deta.orion.comm.common.*;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static pro.deta.orion.comm.common.DtlsExceptionUtil.*;
import static pro.deta.orion.comm.common.FlowControl.*;

@Slf4j
@ToString
public class OrionDTLSAsyncHandler<T> {
    private static final int MAXIMUM_PACKET_SIZE = 1200;

    private final ConcurrentHashMap<DtlsSessionEndpoint<T>, OrionSSLEngine<T>> sslEngines = new ConcurrentHashMap<>();
    private final SSLContext context;
    private final DtlsApplication<T> application;

    @Setter // done in this way to construct first and then set network consumer later at the time of netty initialization
    // !!! release buffer after sending !!!
    private BiConsumer<T, ByteBuf> networkConsumer;
    @Setter // indication to tests: no more activity expected from the endpoint
    private Consumer<DtlsSessionEndpoint<T>> onNeedUnwrap;
    private final ScheduledExecutorService executorService;

    public OrionDTLSAsyncHandler(DtlsApplication<T> application) {
        this(application, OrionDTLSParameters.TEST_ONLY_PARAMETERS.initContext(), Executors.newScheduledThreadPool(1));
    }

    public OrionDTLSAsyncHandler(DtlsApplication<T> application, ThreadFactory threadFactory) {
        this(application, OrionDTLSParameters.TEST_ONLY_PARAMETERS.initContext(), Executors.newScheduledThreadPool(1, threadFactory));
    }

    public OrionDTLSAsyncHandler(DtlsApplication<T> application, SSLContext sslContext, ScheduledExecutorService executorService) {
        this.application = application;
        this.context = sslContext;
        this.executorService = executorService;
        application.setDtlsAsyncHandler(this);
    }

    private OrionSSLEngine<T> lookupOrCreateEngine(DtlsSessionEndpoint<T> endpoint, boolean client) {
        return sslEngines.computeIfAbsent(endpoint, (e) -> {
            SSLEngine sslEngine = _createNewEngine(e.getRemote(), client);
            application.beforeHandshakeStarted(e);
            try {
                sslEngine.beginHandshake();
            } catch (SSLException ex) {
                log.error(ex.getMessage(), ex);
                application.onError(e, ex.getMessage());
            }
            return new OrionSSLEngine<>(sslEngine, e);
        });
    }

    private SSLEngine _createNewEngine(T remote, boolean client) {
        SSLEngine engine;
        if (remote != null && remote instanceof InetSocketAddress inetSocketAddress) {
            engine = context.createSSLEngine(inetSocketAddress.getHostString(), inetSocketAddress.getPort());
        } else
            engine = context.createSSLEngine();
        SSLParameters sslParams = new SSLParameters();
        sslParams.setEnableRetransmissions(true);
        sslParams.setMaximumPacketSize(MAXIMUM_PACKET_SIZE);
        engine.setSSLParameters(sslParams);
        engine.setEnabledProtocols(new String[]{"DTLSv1.2"});
        engine.setUseClientMode(client);
        return engine;
    }

    /**
     * Process data from network.
     *
     * @param packet incoming encrypted/handshake data packet
     */
    public void channelRead(DataPacket<T> packet) {
        OrionSSLEngine<T> sslEngine = lookupOrCreateEngine(packet.getEndpoint(), false);
        sslEngine.addToIncomingNetworkQueue(packet);
        executorService.submit(() -> {
            process(sslEngine);
        });
    }

    public void channelRead(T remote, T local, ByteBuf data) {
        channelRead(new DataPacket<>(new DtlsSessionEndpoint<>(remote, local), data));
    }

    public void channelClientInit(DtlsSessionEndpoint<T> clientEndpoint) {
        OrionSSLEngine<T> sslEngine = lookupOrCreateEngine(clientEndpoint, true);
        executorService.submit(() -> {
            process(sslEngine);
        });
    }

    /**
     * Process data from application.
     */
    public void applicationRead(DtlsSessionEndpoint<T> target, ByteBuf data) {
        OrionSSLEngine<T> sslEngine = lookupOrCreateEngine(target, false);
        sslEngine.addToIncomingApplicationQueue(new DataPacket<>(target, data));
        executorService.submit(() -> {
            process(sslEngine);
        });
    }

    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        for(OrionSSLEngine<T> sslEngine : sslEngines.values()) {
            sb.append(sslEngine.getStatus());
        }
        return sb.toString();
    }

    public void process() {
        for(OrionSSLEngine<T> sslEngine : sslEngines.values()) {
            executorService.submit(() -> {
                process(sslEngine);
            });
        }
    }

    /**
     * 1. incoming transport queue
     * 2. incoming application queue
     * 3. timers?? (not implemented)
     */
    private void process(OrionSSLEngine<T> sslEngine) {
        synchronized (sslEngine) {
            while (true) {
                SSLEngineResult.HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
                try {
                    if (handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                        FlowControl state =
                                runWhileRepeat(() -> doHandshake(sslEngine))
                                        .onError((e) -> {
                                            log.error("Error in endpoint: {}", sslEngine.getRecentEndpoint(), e.t());
                                        });
                        if (state.needReturn())
                            return;
                    }
                    if (sslEngine.hasIncomingNetworkData()) {
                        FlowControl state =
                                runWhileRepeat(() -> doReceive(sslEngine))
                                        .onError((e) -> {
                                            log.error("Error in endpoint: {}", sslEngine.getRecentEndpoint(), e.t());
                                        });
                        if (state.needReturn())
                            return;
                    }

                    if (sslEngine.getIncomingApplicationQueue().hasData()) {
                        FlowControl state =
                                runWhileRepeat(() -> doSend(sslEngine))
                                        .onError((e) -> {
                                            log.error("Error in endpoint: {}", sslEngine.getRecentEndpoint(), e.t());
                                        });
                        if (state.needReturn())
                            return;
                    }
                    if (!sslEngine.hasIncomingNetworkData() && !sslEngine.getIncomingApplicationQueue().hasData() && handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
                        return;
                } finally {
                    log.debug("Processing {} done status {}->{}", sslEngine.getRecentEndpoint(), handshakeStatus, sslEngine.getHandshakeStatus());
                }
            }
        }
    }

    private FlowControl doSend(OrionSSLEngine<T> sslEngine) throws OrionDTLSException {
        DataPacket<T> dp;
        while ((dp = sslEngine.getIncomingApplicationQueue().poll()) != null) {
            internalWrap(sslEngine, dp.getBuf());
        }
        return CONTINUE();
    }

    private FlowControl doReceive(OrionSSLEngine<T> sslEngine) throws OrionDTLSException {
        while (true) {
            DataPacket<T> dp = sslEngine.pollIncomingNetworkQueue();
            if (dp == null)
                break;
            internalUnwrap(sslEngine, dp);
        }
        return CONTINUE();
    }

    private FlowControl doHandshake(OrionSSLEngine<T> sslEngine) throws OrionDTLSException {
        SSLEngineResult.HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
        log.trace("Handshake status: {} started.", handshakeStatus);
        switch (handshakeStatus) {
            case NEED_UNWRAP, NEED_UNWRAP_AGAIN:
                DataPacket<T> dataPacket = sslEngine.pollIncomingNetworkQueue();
                if (dataPacket != null) {
                    internalUnwrap(sslEngine, dataPacket);
                } else if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN) {
                    internalUnwrapAgain(sslEngine);
                }
                break;
            case NEED_WRAP:
                internalWrapEmptyBuffer(sslEngine);
                break;
            case NOT_HANDSHAKING:
                return BREAK();
            case NEED_TASK:
                do {
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    log.debug("NEED_TASK->{}", sslEngine.getHandshakeStatus());
                    sslEngine.logHandshakeStatusWithLastResult();
                } while (sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK);
                break;
        }
        switch (sslEngine.getHandshakeStatus()) {
            case NOT_HANDSHAKING, FINISHED -> {
                if (sslEngine.getLastCommandExecutionResult().getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED)
                    application.afterHandshakeFinished(sslEngine.getRecentEndpoint());
                return CONTINUE();
            }
            case NEED_TASK, NEED_WRAP, NEED_UNWRAP_AGAIN -> {
                return REPEAT();
            }
            case NEED_UNWRAP -> {
                if (sslEngine.hasIncomingNetworkData())
                    return REPEAT();
                else {
                    return BREAK();
                }
            }
        }
        return CONTINUE();
    }

    private void internalWrapEmptyBuffer(OrionSSLEngine<T> sslEngine) throws OrionDTLSException {
        internalWrap(sslEngine, EMPTY_BUFFER);
    }

    private void internalUnwrapAgain(OrionSSLEngine<T> sslEngine) throws OrionDTLSException {
        internalUnwrap(sslEngine, null);
    }

    private void internalWrap(OrionSSLEngine<T> sslEngine, ByteBuf encrypt) throws OrionDTLSException {
        SSLEngineResult result = sslEngine.wrap(encrypt);
        encrypt.release();
        switch (result.getStatus()) {
            case BUFFER_UNDERFLOW -> throwExceptionBufferUnderflow();
            case OK -> {
            }
            case CLOSED -> throwExceptionIfHandshakeFailed("CLOSED: " + result);
            default -> throw new IllegalStateException("Unexpected value: " + result.getStatus());
        }
        ByteBuf encryptBuffer = sslEngine.createEncryptSlice();
        if (encryptBuffer != null) {
            networkConsumer.accept(sslEngine.getRecentEndpoint().getRemote(), encryptBuffer);
        }
    }

    private void internalUnwrap(OrionSSLEngine<T> sslEngine, DataPacket<T> dataPacket) throws OrionDTLSException {
        DtlsSessionEndpoint<T> endpoint = dataPacket != null ? dataPacket.getEndpoint() : sslEngine.getRecentEndpoint();
        try {
            if (dataPacket != null)
                log.debug("RECEIVING PACKET: {}", dataPacket.getPacketNo());
            SSLEngineResult result = sslEngine.unwrap(dataPacket != null ? dataPacket.getBuf() : EMPTY_BUFFER);
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW -> // we need to wait for the next packets
                        throwExceptionBufferUnderflow();
                case OK -> {
                }
                case CLOSED -> application.afterEndpointClosed(endpoint);
                default -> throw new IllegalStateException("Unexpected value: " + result.getStatus());
            }
        } finally {
            if (dataPacket != null)
                dataPacket.release();
        }
        if (sslEngine.getDecryptBuffer().isReadable())
            application.read(endpoint, sslEngine.createDecryptSlice());
    }
}