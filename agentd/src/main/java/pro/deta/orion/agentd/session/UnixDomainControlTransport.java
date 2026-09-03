package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.TimeUnit;

public final class UnixDomainControlTransport implements ControlTransport {
    @Override
    public Exchange exchange(ControlEndpoint endpoint, byte[] request, OperationDeadline deadline) {
        boolean requestWritten = false;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             Selector selector = Selector.open()) {
            check(deadline);
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_CONNECT);
            if (!channel.connect(UnixDomainSocketAddress.of(endpoint.address()))) {
                await(channel, selector, SelectionKey.OP_CONNECT, deadline);
                check(deadline);
                if (!channel.finishConnect()) {
                    throw new IOException("Unix control connection did not finish");
                }
            }
            check(deadline);

            requestWritten = write(
                    channel,
                    ByteBuffer.wrap(request),
                    deadline,
                    () -> await(channel, selector, SelectionKey.OP_WRITE, deadline));
            ByteBuffer header = ByteBuffer.allocate(NativeControlCodec.HEADER_LENGTH);
            read(
                    channel,
                    header,
                    deadline,
                    () -> await(channel, selector, SelectionKey.OP_READ, deadline));
            int payloadLength = ByteBuffer.wrap(header.array()).order(ByteOrder.LITTLE_ENDIAN).getInt(24);
            if (payloadLength < 0 || payloadLength > NativeControlCodec.MAX_PAYLOAD_LENGTH) {
                return new Exchange.Failed(
                        ControlResult.FailureKind.FRAMING, true, "response payload length is invalid");
            }
            ByteBuffer payload = ByteBuffer.allocate(payloadLength);
            read(
                    channel,
                    payload,
                    deadline,
                    () -> await(channel, selector, SelectionKey.OP_READ, deadline));
            check(deadline);
            byte[] response = new byte[NativeControlCodec.HEADER_LENGTH + payloadLength];
            System.arraycopy(header.array(), 0, response, 0, header.capacity());
            System.arraycopy(payload.array(), 0, response, header.capacity(), payload.capacity());
            return new Exchange.Response(response);
        } catch (DeadlineExceeded error) {
            return new Exchange.Failed(ControlResult.FailureKind.TIMEOUT, requestWritten, error.getMessage());
        } catch (IOException | UnsupportedOperationException error) {
            return new Exchange.Failed(
                    ControlResult.FailureKind.CONNECTION,
                    requestWritten,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    static boolean write(
            WritableByteChannel channel,
            ByteBuffer source,
            OperationDeadline deadline,
            ReadinessWaiter waiter
    ) throws IOException {
        while (source.hasRemaining()) {
            check(deadline);
            if (channel.write(source) == 0) {
                waiter.await();
            }
            if (source.hasRemaining()) {
                check(deadline);
            }
        }
        return true;
    }

    static void read(
            ReadableByteChannel channel,
            ByteBuffer target,
            OperationDeadline deadline,
            ReadinessWaiter waiter
    ) throws IOException {
        while (target.hasRemaining()) {
            check(deadline);
            int length = channel.read(target);
            check(deadline);
            if (length < 0) {
                throw new IOException("control peer closed before a complete response");
            }
            if (length == 0) {
                waiter.await();
            }
        }
    }

    private static void await(
            SocketChannel channel,
            Selector selector,
            int interest,
            OperationDeadline deadline
    ) throws IOException {
        SelectionKey key = channel.keyFor(selector);
        key.interestOps(interest);
        while (true) {
            long remaining = remaining(deadline);
            long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
            if (TimeUnit.MILLISECONDS.toNanos(timeoutMillis) < remaining) {
                timeoutMillis++;
            }
            if (selector.select(timeoutMillis) > 0) {
                selector.selectedKeys().clear();
                check(deadline);
                return;
            }
        }
    }

    private static long remaining(OperationDeadline deadline) throws DeadlineExceeded {
        long remaining = deadline.remainingNanos();
        if (remaining == 0) {
            throw new DeadlineExceeded();
        }
        return remaining;
    }

    private static void check(OperationDeadline deadline) throws DeadlineExceeded {
        remaining(deadline);
    }

    @FunctionalInterface
    interface ReadinessWaiter {
        void await() throws IOException;
    }

    private static final class DeadlineExceeded extends IOException {
        private DeadlineExceeded() {
            super("native control operation timed out");
        }
    }
}
