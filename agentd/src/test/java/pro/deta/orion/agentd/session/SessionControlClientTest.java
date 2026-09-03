package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.ProtocolBytes;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIOException;

class SessionControlClientTest {
    private static final CommandId COMMAND_ID = new CommandId("command-1");

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
            Future<Void> peer = serve(server, request -> {
                long requestId = requestId(request);
                byte[] status = runningStatus(4242, 4343);
                return NativeControlCodec.frame(0x8003, requestId, status);
            });

            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
            ControlResult result = client.send(endpoint("status.sock"), new ControlCommand.Status());

            assertThat(result).isInstanceOf(ControlResult.Status.class);
            assertThat(((ControlResult.Status) result).status().hostPid()).isEqualTo(4242);
            await(peer);
        }
    }

    @Test
    void returnsHostErrorsAsTypedResults() throws Exception {
        try (ServerSocketChannel server = listen("error.sock")) {
            Future<Void> peer = serve(server, request -> {
                ByteBuffer error = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
                error.putInt(4).put("exited".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return NativeControlCodec.frame(0x8002, requestId(request), error.array());
            });
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));

            ControlResult result = client.send(
                    endpoint("error.sock"), new ControlCommand.Resize(COMMAND_ID, 80, 24));

            assertThat(result).isEqualTo(new ControlResult.Rejected(Optional.of(COMMAND_ID), 4, "exited"));
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
            SessionControlClient client = new SessionControlClient(Duration.ofMillis(40));

            ControlResult result = client.send(endpoint("timeout.sock"), new ControlCommand.Status());

            assertFailure(result, ControlResult.FailureKind.AMBIGUOUS_DELIVERY);
            await(peer);
        }
    }

    @Test
    void reconnectsInputWithTheExactSameFrameAndAcceptsDuplicate() throws Exception {
        try (ServerSocketChannel server = listen("retry.sock")) {
            Future<byte[][]> peer = executor.submit(() -> {
                byte[][] requests = new byte[2][];
                try (SocketChannel first = server.accept()) {
                    requests[0] = readFrame(first);
                }
                try (SocketChannel second = server.accept()) {
                    requests[1] = readFrame(second);
                    writeFully(second, NativeControlCodec.frame(
                            0x8001, requestId(requests[1]), littleEndianLong(91)));
                }
                return requests;
            });
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
            ControlCommand.Input input = new ControlCommand.Input(
                    COMMAND_ID,
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                    ProtocolBytes.copyOf(new byte[]{1, 2, 3}));

            ControlResult result = client.send(endpoint("retry.sock"), input);

            assertThat(result).isEqualTo(new ControlResult.Acknowledged(COMMAND_ID, true, 91));
            byte[][] requests = peer.get(2, TimeUnit.SECONDS);
            assertThat(requests[1]).isEqualTo(requests[0]);
        }
    }

    @Test
    void retriesTheExactInputFrameAfterAMalformedResponse() throws Exception {
        try (ServerSocketChannel server = listen("malformed-input.sock")) {
            Future<byte[][]> peer = executor.submit(() -> {
                byte[][] requests = new byte[2][];
                try (SocketChannel first = server.accept()) {
                    requests[0] = readFrame(first);
                    writeFully(first, malformedAcknowledgement(requestId(requests[0])));
                }
                try (SocketChannel second = server.accept()) {
                    requests[1] = readFrame(second);
                    writeFully(second, NativeControlCodec.frame(
                            0x8001, requestId(requests[1]), littleEndianLong(91)));
                }
                return requests;
            });
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
            ControlCommand.Input input = new ControlCommand.Input(
                    COMMAND_ID,
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                    ProtocolBytes.copyOf(new byte[]{1, 2, 3}));

            ControlResult result = client.send(endpoint("malformed-input.sock"), input);

            assertThat(result).isEqualTo(new ControlResult.Acknowledged(COMMAND_ID, true, 91));
            byte[][] requests = peer.get(2, TimeUnit.SECONDS);
            assertThat(requests[1]).isEqualTo(requests[0]);
        }
    }

    @Test
    void reportsAmbiguousInputWhenBothExactFrameAttemptsReceiveMalformedResponses() throws Exception {
        try (ServerSocketChannel server = listen("twice-malformed-input.sock")) {
            Future<byte[][]> peer = executor.submit(() -> {
                byte[][] requests = new byte[2][];
                try (SocketChannel first = server.accept()) {
                    requests[0] = readFrame(first);
                    writeFully(first, malformedAcknowledgement(requestId(requests[0])));
                }
                try (SocketChannel second = server.accept()) {
                    requests[1] = readFrame(second);
                    writeFully(second, malformedAcknowledgement(requestId(requests[1])));
                }
                return requests;
            });
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(2));
            ControlCommand.Input input = new ControlCommand.Input(
                    COMMAND_ID,
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                    ProtocolBytes.copyOf(new byte[]{1, 2, 3}));

            ControlResult result = client.send(endpoint("twice-malformed-input.sock"), input);

            assertFailure(result, ControlResult.FailureKind.AMBIGUOUS_DELIVERY);
            byte[][] requests = peer.get(2, TimeUnit.SECONDS);
            assertThat(requests[1]).isEqualTo(requests[0]);
        }
    }

    @Test
    void malformedResizeResponseIsAmbiguousAndDoesNotReplay() throws Exception {
        try (ServerSocketChannel server = listen("malformed-resize.sock")) {
            AtomicInteger connections = new AtomicInteger();
            Future<Void> peer = executor.submit(() -> {
                try (SocketChannel first = server.accept()) {
                    connections.incrementAndGet();
                    byte[] request = readFrame(first);
                    writeFully(first, malformedAcknowledgement(requestId(request)));
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
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1));

            ControlResult result = client.send(
                    endpoint("malformed-resize.sock"), new ControlCommand.Resize(COMMAND_ID, 81, 25));

            assertFailure(result, ControlResult.FailureKind.AMBIGUOUS_DELIVERY);
            await(peer);
            assertThat(connections).hasValue(1);
        }
    }

    @Test
    void malformedStatusResponseRemainsAFramingFailure() throws Exception {
        try (ServerSocketChannel server = listen("malformed-status.sock")) {
            Future<Void> peer = serve(server, request -> malformedAcknowledgement(requestId(request)));
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1));

            ControlResult result = client.send(endpoint("malformed-status.sock"), new ControlCommand.Status());

            assertFailure(result, ControlResult.FailureKind.FRAMING);
            await(peer);
        }
    }

    @Test
    void doesNotReplayResizeAfterAmbiguousDelivery() throws Exception {
        try (ServerSocketChannel server = listen("no-retry.sock")) {
            AtomicInteger connections = new AtomicInteger();
            Future<Void> peer = executor.submit(() -> {
                try (SocketChannel first = server.accept()) {
                    connections.incrementAndGet();
                    readFrame(first);
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
            SessionControlClient client = new SessionControlClient(Duration.ofSeconds(1));

            ControlResult result = client.send(
                    endpoint("no-retry.sock"), new ControlCommand.Resize(COMMAND_ID, 81, 25));

            assertFailure(result, ControlResult.FailureKind.AMBIGUOUS_DELIVERY);
            await(peer);
            assertThat(connections).hasValue(1);
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

        assertFailure(result, ControlResult.FailureKind.UNSUPPORTED_TRANSPORT);
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
        OperationDeadline deadline = OperationDeadline.after(
                Duration.ofNanos(3), clock::getAndIncrement);
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
        OperationDeadline deadline = OperationDeadline.after(
                Duration.ofNanos(3), clock::getAndIncrement);
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
        ByteBuffer payload = complete.slice();
        readFully(channel, payload);
        complete.position(complete.capacity());
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

    private static long requestId(byte[] frame) {
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

    private static byte[] littleEndianLong(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    private static byte[] malformedAcknowledgement(long requestId) {
        byte[] response = NativeControlCodec.frame(0x8001, requestId, littleEndianLong(91));
        response[28] ^= 1;
        return response;
    }

    private static void assertFailure(ControlResult result, ControlResult.FailureKind kind) {
        assertThat(result).isInstanceOf(ControlResult.Failed.class);
        assertThat(((ControlResult.Failed) result).kind()).isEqualTo(kind);
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
