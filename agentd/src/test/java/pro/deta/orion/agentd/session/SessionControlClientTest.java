package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandSource;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIOException;

class SessionControlClientTest {
    private static final ProtocolBytes COMMAND_ENVELOPE = ProtocolBytes.copyOf(new byte[]{0x11});

    @TempDir
    Path temporaryDirectory;
    private ExecutorService executor;

    @BeforeEach
    void createExecutor() {
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void exchangesStatusOverARealUnixDomainSocket() throws Exception {
        try (ServerSocketChannel server = listen("status.sock")) {
            Future<Void> peer = serve(server, request ->
                    NativeControlCodec.frame(0x8003, sequence(request), runningStatus(4242, 4343)));

            ControlResult result = new SessionControlClient(Duration.ofSeconds(2))
                    .send(endpoint("status.sock"), new ControlCommand.Status());

            assertThat(result).isInstanceOf(ControlResult.Status.class);
            assertThat(((ControlResult.Status) result).status().hostPid()).isEqualTo(4242);
            await(peer);
        }
    }

    @Test
    void claimsServerControlAndReturnsBothRecoveryValues() throws Exception {
        try (ServerSocketChannel server = listen("claim.sock")) {
            Future<Void> peer = serve(server, request -> {
                ByteBuffer payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                payload.putLong(73).putLong(-1);
                return NativeControlCodec.frame(0x8005, sequence(request), payload.array());
            });

            ControlResult result = new SessionControlClient(Duration.ofSeconds(2)).send(
                    endpoint("claim.sock"),
                    new ControlCommand.ClaimServerControl(OptionalLong.of(71)));

            assertThat(result).isEqualTo(new ControlResult.ServerControlClaimed(
                    OptionalLong.of(73), OptionalLong.of(-1)));
            await(peer);
        }
    }

    @Test
    void returnsReceivedRejectionAsAnOperationResult() throws Exception {
        try (ServerSocketChannel server = listen("error.sock")) {
            Future<Void> peer = serve(server, request -> {
                ByteBuffer error = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
                error.putInt(4).put("exited".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return NativeControlCodec.frame(0x8000, sequence(request), error.array());
            });
            ControlCommand.Resize resize = serverResize(3, 80, 24);

            ControlResult result = new SessionControlClient(Duration.ofSeconds(2))
                    .send(endpoint("error.sock"), resize);

            assertThat(result).isEqualTo(new ControlResult.Rejected(OptionalLong.of(3), 4, "exited"));
            await(peer);
        }
    }

    @Test
    void doesNotRetryAnOperationAfterUncertainDelivery() throws Exception {
        try (ServerSocketChannel server = listen("no-retry.sock")) {
            AtomicInteger connections = new AtomicInteger();
            Future<Void> peer = executor.submit(() -> {
                try (SocketChannel channel = server.accept()) {
                    connections.incrementAndGet();
                    readFrame(channel);
                }
                server.configureBlocking(false);
                long deadline = System.nanoTime() + Duration.ofMillis(200).toNanos();
                while (System.nanoTime() < deadline) {
                    try (SocketChannel unexpected = server.accept()) {
                        if (unexpected != null) {
                            connections.incrementAndGet();
                            break;
                        }
                    }
                    Thread.sleep(5);
                }
                return null;
            });
            ControlCommand.Resize resize = new ControlCommand.Resize(
                    3, SessionCommandSource.MANUAL, Optional.empty(), 81, 25);

            ControlResult result = new SessionControlClient(Duration.ofSeconds(1))
                    .send(endpoint("no-retry.sock"), resize);

            assertFailure(result, ControlResult.FailureKind.AMBIGUOUS_DELIVERY, OptionalLong.of(3));
            await(peer);
            assertThat(connections).hasValue(1);
        }
    }

    @Test
    void laterStaleResponseCannotReplaceAmbiguityFromAMalformedReceipt() {
        AtomicInteger exchanges = new AtomicInteger();
        ControlTransport transport = (endpoint, request, deadline) -> {
            if (exchanges.getAndIncrement() == 0) {
                byte[] response = NativeControlCodec.frame(0x8000, sequence(request), new byte[0]);
                response[28] ^= 1;
                return new ControlTransport.Exchange.Response(response);
            }
            byte[] stale = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(4).put("stale".getBytes(java.nio.charset.StandardCharsets.UTF_8)).array();
            return new ControlTransport.Exchange.Response(
                    NativeControlCodec.frame(0x8000, sequence(request), stale));
        };
        SessionControlClient client = new SessionControlClient(
                Duration.ofSeconds(1),
                endpoint -> new ControlTransportFactory.Selection.Available(transport));
        ControlCommand.Resize resize = serverResize(9, 81, 25);

        ControlResult result = client.send(endpoint("unused.sock"), resize);

        assertFailure(result, ControlResult.FailureKind.AMBIGUOUS_DELIVERY, OptionalLong.of(9));
        assertThat(exchanges).hasValue(1);
    }

    @Test
    void malformedStatusResponseRemainsAFramingFailure() throws Exception {
        try (ServerSocketChannel server = listen("malformed-status.sock")) {
            Future<Void> peer = serve(server, request -> {
                byte[] response = NativeControlCodec.frame(0x8003, sequence(request), runningStatus(1, 2));
                response[28] ^= 1;
                return response;
            });

            ControlResult result = new SessionControlClient(Duration.ofSeconds(1))
                    .send(endpoint("malformed-status.sock"), new ControlCommand.Status());

            assertFailure(result, ControlResult.FailureKind.FRAMING, OptionalLong.empty());
            await(peer);
        }
    }

    @Test
    void appliesAWholeOperationResponseTimeoutWithoutAnIoWorkerThread() throws Exception {
        try (ServerSocketChannel server = listen("timeout.sock")) {
            Future<Void> peer = executor.submit(() -> {
                try (SocketChannel channel = server.accept()) {
                    readFrame(channel);
                    Thread.sleep(250);
                }
                return null;
            });

            ControlResult result = new SessionControlClient(Duration.ofMillis(40))
                    .send(endpoint("timeout.sock"), new ControlCommand.Status());

            assertFailure(result, ControlResult.FailureKind.AMBIGUOUS_DELIVERY, OptionalLong.empty());
            await(peer);
        }
    }

    @Test
    void reportsNamedPipesAsUnsupportedUntilTheNativeWindowsHostExists() {
        SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1));
        ControlEndpoint endpoint = new ControlEndpoint(
                ControlEndpoint.Transport.NAMED_PIPE,
                "orion-session",
                Path.of("orion-session"));

        ControlResult result = client.send(endpoint, new ControlCommand.Status());

        assertFailure(result, ControlResult.FailureKind.UNSUPPORTED_TRANSPORT, OptionalLong.empty());
    }

    @Test
    void rejectsOperationTimeoutThatCannotBeRepresentedInNanoseconds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SessionControlClient(Duration.ofSeconds(Long.MAX_VALUE)))
                .withMessageContaining("nanoseconds");
    }

    @Test
    void continuousReadProgressCannotExtendTheWholeOperationDeadline() {
        AtomicInteger clock = new AtomicInteger();
        OperationDeadline deadline = OperationDeadline.after(Duration.ofNanos(3), clock::getAndIncrement);
        OneByteProgressChannel channel = new OneByteProgressChannel();
        ByteBuffer target = ByteBuffer.allocate(8);

        assertThatIOException()
                .isThrownBy(() -> UnixDomainControlTransport.read(
                        channel,
                        target,
                        deadline,
                        () -> {
                            throw new AssertionError("progressing channel must not await readiness");
                        }))
                .withMessageContaining("timed out");
        assertThat(target.position()).isLessThan(target.capacity());
    }

    @Test
    void continuousWriteProgressCannotExtendTheWholeOperationDeadline() {
        AtomicInteger clock = new AtomicInteger();
        OperationDeadline deadline = OperationDeadline.after(Duration.ofNanos(3), clock::getAndIncrement);
        OneByteProgressChannel channel = new OneByteProgressChannel();
        ByteBuffer source = ByteBuffer.allocate(8);

        assertThatIOException()
                .isThrownBy(() -> UnixDomainControlTransport.write(
                        channel,
                        source,
                        deadline,
                        () -> {
                            throw new AssertionError("progressing channel must not await readiness");
                        }))
                .withMessageContaining("timed out");
        assertThat(source.position()).isLessThan(source.capacity());
    }

    @Test
    void finalWrittenByteCompletesBeforeTheDeadlineIsReported() throws Exception {
        AtomicInteger clock = new AtomicInteger();
        OperationDeadline deadline = OperationDeadline.after(Duration.ofNanos(3), clock::get);
        ByteBuffer source = ByteBuffer.allocate(1);

        boolean requestWritten = UnixDomainControlTransport.write(
                new ExpiringFinalWriteChannel(clock),
                source,
                deadline,
                () -> {
                    throw new AssertionError("progressing channel must not await readiness");
                });

        assertThat(source.hasRemaining()).isFalse();
        assertThat(requestWritten).isTrue();
        assertThat(deadline).matches(OperationDeadline::expired);
    }

    private ServerSocketChannel listen(String name) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(temporaryDirectory.resolve(name)));
        return server;
    }

    private ControlEndpoint endpoint(String name) {
        Path address = temporaryDirectory.resolve(name);
        return new ControlEndpoint(ControlEndpoint.Transport.UNIX_DOMAIN_SOCKET, name, address);
    }

    private Future<Void> serve(ServerSocketChannel server, Responder responder) {
        return executor.submit(() -> {
            try (SocketChannel channel = server.accept()) {
                byte[] request = readFrame(channel);
                writeFully(channel, responder.respond(request));
            }
            return null;
        });
    }

    private static byte[] readFrame(SocketChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(NativeControlCodec.HEADER_LENGTH);
        readFully(channel, header);
        int payloadLength = ByteBuffer.wrap(header.array()).order(ByteOrder.LITTLE_ENDIAN).getInt(24);
        ByteBuffer complete = ByteBuffer.allocate(NativeControlCodec.HEADER_LENGTH + payloadLength);
        complete.put(header.array());
        readFully(channel, complete.slice());
        return complete.array();
    }

    private static void readFully(SocketChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) {
                throw new IOException("unexpected end of stream");
            }
        }
    }

    private static void writeFully(SocketChannel channel, byte[] bytes) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(bytes);
        while (source.hasRemaining()) {
            channel.write(source);
        }
    }

    private static long sequence(byte[] frame) {
        return ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getLong(16);
    }

    private static byte[] runningStatus(long hostPid, long childPid) {
        byte[] payload = new byte[64];
        ByteBuffer status = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        status.putShort(0, (short) 2).putShort(2, (short) 3);
        status.putInt(4, 80).putInt(8, 24);
        status.putLong(12, hostPid).putLong(20, childPid);
        status.putInt(44, Integer.MIN_VALUE).putInt(48, -1);
        status.putShort(52, (short) 1).putShort(54, (short) 1);
        return payload;
    }

    private static ControlCommand.Resize serverResize(long sequence, int columns, int rows) {
        return new ControlCommand.Resize(
                sequence,
                SessionCommandSource.SERVER,
                Optional.of(COMMAND_ENVELOPE),
                columns,
                rows);
    }

    private static void assertFailure(
            ControlResult result,
            ControlResult.FailureKind kind,
            OptionalLong operationSequence
    ) {
        assertThat(result).isEqualTo(new ControlResult.Failed(
                operationSequence,
                kind,
                ((ControlResult.Failed) result).detail()));
    }

    private static void await(Future<?> future) throws InterruptedException, ExecutionException {
        future.get();
    }

    @FunctionalInterface
    private interface Responder {
        byte[] respond(byte[] request);
    }

    private static final class OneByteProgressChannel implements ReadableByteChannel, WritableByteChannel {
        private boolean open = true;

        @Override
        public int read(ByteBuffer target) {
            target.put((byte) 1);
            return 1;
        }

        @Override
        public int write(ByteBuffer source) {
            source.get();
            return 1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    private static final class ExpiringFinalWriteChannel implements WritableByteChannel {
        private final AtomicInteger clock;
        private boolean open = true;

        private ExpiringFinalWriteChannel(AtomicInteger clock) {
            this.clock = clock;
        }

        @Override
        public int write(ByteBuffer source) {
            source.get();
            clock.set(3);
            return 1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
