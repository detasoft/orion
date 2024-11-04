package pro.deta.orion.comm.v3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.WrappedByteBuf;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.comm.common.*;
import pro.deta.orion.comm.util.RecentTimestampedValueBuffer;
import pro.deta.orion.util.OrionUtils;

import javax.net.ssl.*;
import java.nio.ByteBuffer;

import static pro.deta.orion.comm.common.DtlsExceptionUtil.*;
import static pro.deta.orion.util.LogUtils.getStackTraceAsString;

@Slf4j
public class OrionSSLEngine<T> {
    private final SSLEngine sslEngine;
    @Getter
    private final ByteBuf decryptBuffer;
    @Getter
    private final ByteBuf encryptBuffer;
    @Getter
    private final OrionDataQueue<T> incomingNetworkQueue = new OrionDataQueue<>();
    @Getter
    private final OrionDataQueue<T> incomingApplicationQueue = new OrionDataQueue<>();
    @Getter
    private final RecentTimestampedValueBuffer<DtlsSessionEndpoint<T>> endpointTracking;
    private final RecentTimestampedValueBuffer<LastCommandExecution> commandExecutionResult;
    private final DtlsSessionState sessionState = new DtlsSessionState();

    public OrionSSLEngine(SSLEngine sslEngine, DtlsSessionEndpoint<T> endpoint) {
        this.sslEngine = sslEngine;
        decryptBuffer = Unpooled.buffer(sslEngine.getSession().getApplicationBufferSize() * 3);
        encryptBuffer = Unpooled.buffer(sslEngine.getSession().getPacketBufferSize() * 3);
        endpointTracking = new RecentTimestampedValueBuffer<>(10, endpoint);
        commandExecutionResult = new RecentTimestampedValueBuffer<>(10, new LastCommandExecution("noop", new SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_WRAP, 0, 0, -1)));
    }

    public ByteBuf createEncryptSlice() {
        return createProtectedSlice(encryptBuffer);
    }

    public ByteBuf createDecryptSlice() {
        return createProtectedSlice(decryptBuffer);
    }

    public boolean hasIncomingNetworkData() {
        return incomingNetworkQueue.hasData();
    }

    public DataPacket<T> pollIncomingNetworkQueue() {
        return incomingNetworkQueue.poll();
    }

    private ByteBuf createProtectedSlice(ByteBuf sourceBuf) {
        if (sourceBuf.isReadable()) {
            int length = sourceBuf.readableBytes();
            ByteBuf readRetainedSlice = sourceBuf.readRetainedSlice(length);
            if (OrionUtils.JVM_MODE.isDebug()) {
                return new WrappedByteBuf(readRetainedSlice) {
                    @Override
                    public boolean release() {
                        return super.release();
                    }
                };
            } else {
                return readRetainedSlice;
            }
        } else
            return null;
    }

    public SSLEngineResult unwrap(ByteBuf src) throws OrionDTLSException {
        SSLEngineResult result = commandInternal(SSLEngineCommand.UNWRAP, src, decryptBuffer);
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN || sslEngine.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
            log.warn("More data needed to unwrap from {}", endpointTracking.getRecent().value());
        }
        return result;
    }

    public SSLEngineResult getLastCommandExecutionResult() {
        LastCommandExecution recent = this.commandExecutionResult.getLast();
        if (recent != null)
            return recent.result;
        else
            return null;
    }

    public DtlsSessionEndpoint<T> getRecentEndpoint() {
        return endpointTracking.getRecent().value();
    }

    @RequiredArgsConstructor
    @ToString
    private static class LastCommandExecution {
        private final String command;
        private final SSLEngineResult result;
    }

    public SSLEngineResult wrap(ByteBuf src) throws OrionDTLSException {
        return commandInternal(SSLEngineCommand.WRAP, src, encryptBuffer);
    }

    private enum SSLEngineCommand {
        WRAP, UNWRAP
    }

    private SSLEngineResult commandInternal(final SSLEngineCommand cmd, ByteBuf src, ByteBuf dst) throws OrionDTLSException {
        int srcIdx = src.readerIndex();
        int dstIdx = dst.writerIndex();
        int writableBytes = dst.writableBytes();
        ByteBuffer in = src.internalNioBuffer(srcIdx, src.readableBytes());
        ByteBuffer out = dst.internalNioBuffer(dstIdx, writableBytes);

        SSLEngineResult result;
        in.position(srcIdx).limit(srcIdx + src.readableBytes());

        while (true) {
            try {
                out.position(dstIdx).limit(dstIdx + dst.writableBytes());
                result = switch (cmd) {
                    case WRAP -> sslEngine.wrap(in, out);
                    case UNWRAP -> sslEngine.unwrap(in, out);
                };
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    dst.discardSomeReadBytes();
                    continue;
                }
                break;
            } catch (SSLException e) {
                throwSslException(e);
            }
        }
        src.readerIndex(srcIdx + in.position());
        dst.writerIndex(out.position());
        return commandExecutionResult.putLast(new LastCommandExecution(cmd.name(), result)).result;
    }

    private void throwIfOverflow(SSLEngineResult result) throws OrionDTLSException {
        if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW)
            throwExceptionBufferOverflow();
    }

    public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
        return sslEngine.getHandshakeStatus();
    }

    public Runnable getDelegatedTask() {
        return sslEngine.getDelegatedTask();
    }

    public void addToIncomingNetworkQueue(DataPacket<T> packet) {
        incomingNetworkQueue.add(packet);
    }

    public void addToIncomingApplicationQueue(DataPacket<T> packet) {
        incomingApplicationQueue.add(packet);
    }

    public void logHandshakeStatusWithLastResult() {
        SSLEngineResult.HandshakeStatus lastStatus = getLastCommandExecutionResult().getHandshakeStatus();
        SSLEngineResult.HandshakeStatus handshakeStatus = getHandshakeStatus();
        if (lastStatus != SSLEngineResult.HandshakeStatus.NEED_TASK && lastStatus != handshakeStatus) {
            log.warn("Handshake status and lastResult differ: {} != {}\n{}", handshakeStatus, lastStatus, getStackTraceAsString());
        }
    }

    public String getStatus() {
        final StringBuilder sb = new StringBuilder("OrionSSLEngine{");
        sb.append("endpoint=").append(endpointTracking.getRecent().value());
        sb.append(", clientMode=").append(sslEngine.getUseClientMode());
        sb.append(", handshakeStatus=").append(getHandshakeStatus());
        sb.append(", lastResult=").append(commandExecutionResult.getRecent());
        sb.append(", hasNetwork=").append(incomingNetworkQueue.hasData());
        sb.append(", hasApplication=").append(incomingApplicationQueue.hasData());
        sb.append('}');
        return sb.toString();
    }
}