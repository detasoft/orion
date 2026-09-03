package pro.deta.orion.git.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitBlockingClientsTest {
    private static final String OLD_ID =
            "1111111111111111111111111111111111111111";
    private static final String NEW_ID =
            "2222222222222222222222222222222222222222";
    private static final URI REMOTE = URI.create("ssh://example.test/repository.git");

    @Test
    void fetchesLargeSideBandPackOnVirtualThreadFromFragmentedInput()
            throws Exception {
        byte[] firstPackPart = repeated((byte) 'a', 65_000);
        byte[] secondPackPart = repeated((byte) 'b', 5_000);
        byte[] response = concat(
                advertisement("side-band-64k multi_ack_detailed"),
                packet("NAK\n"),
                sideBandPacket(1, firstPackPart),
                sideBandPacket(2, "counting objects\n".getBytes(
                        StandardCharsets.UTF_8)),
                sideBandPacket(1, secondPackPart),
                flush());
        RecordingTransport transport = new RecordingTransport(response);
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        List<String> progress = new ArrayList<>();
        GitUploadPackRequest request = new GitUploadPackRequest(
                List.of(OLD_ID),
                List.of(NEW_ID),
                new OutputStreamBufferedByteOutput(pack),
                progress::add);

        GitClientResult<GitUploadPackResult> result =
                new GitUploadPackClient(transport).fetch(
                        REMOTE, GitClientOptions.defaults(), request);

        GitUploadPackResult value = success(result);
        assertThat(value.packBytes()).isEqualTo(70_000);
        assertThat(pack.toByteArray()).isEqualTo(concat(
                firstPackPart, secondPackPart));
        assertThat(progress).containsExactly("counting objects\n");
        assertThat(value.advertisement().refs())
                .extracting(GitRemoteAdvertisement.Ref::name)
                .containsExactly("refs/heads/main");
        assertThat(transport.service).isEqualTo(GitClientService.UPLOAD_PACK);
        assertThat(transport.openedOnVirtualThread).isTrue();
        assertThat(transport.session.closed).isTrue();
        assertThat(transport.session.output.ascii())
                .contains("want " + OLD_ID
                        + " side-band-64k multi_ack_detailed\n")
                .contains("have " + NEW_ID + "\n")
                .contains("done\n");
    }

    @Test
    void fetchesRawPackWhenRemoteDoesNotAdvertiseSideBand() {
        byte[] packBytes = "PACKraw-payload".getBytes(StandardCharsets.US_ASCII);
        RecordingTransport transport = new RecordingTransport(concat(
                advertisement(""),
                packet("NAK\n"),
                packBytes));
        ByteArrayOutputStream pack = new ByteArrayOutputStream();
        GitUploadPackRequest request = new GitUploadPackRequest(
                List.of(OLD_ID),
                List.of(),
                new OutputStreamBufferedByteOutput(pack),
                ignored -> { });

        GitClientResult<GitUploadPackResult> result =
                new GitUploadPackClient(transport).fetch(
                        REMOTE, GitClientOptions.defaults(), request);

        assertThat(success(result).packBytes()).isEqualTo(packBytes.length);
        assertThat(pack.toByteArray()).isEqualTo(packBytes);
        assertThat(transport.session.output.ascii())
                .contains("want " + OLD_ID + "\n")
                .doesNotContain("side-band");
    }

    @Test
    void acceptsShallowBoundaryInAdvertisement() {
        RecordingTransport transport = new RecordingTransport(concat(
                packet("shallow " + NEW_ID + "\n"),
                advertisement("side-band")));

        GitClientResult<GitRemoteAdvertisement> result =
                new GitUploadPackClient(transport).discover(
                        REMOTE, GitClientOptions.defaults());

        assertThat(success(result).capabilities()).containsExactly("side-band");
        assertThat(success(result).refs())
                .extracting(GitRemoteAdvertisement.Ref::name)
                .containsExactly("refs/heads/main");
    }

    @Test
    void reportsEarlyEndOfUploadPackAndClosesTransport() {
        RecordingTransport transport = new RecordingTransport(concat(
                advertisement("side-band-64k"),
                packet("NAK\n")));
        GitUploadPackRequest request = GitUploadPackRequest.of(
                OLD_ID,
                new RecordingOutput());

        GitClientResult<GitUploadPackResult> result =
                new GitUploadPackClient(transport).fetch(
                        REMOTE, GitClientOptions.defaults(), request);

        assertThat(failure(result).kind()).isEqualTo(
                GitClientFailure.Kind.UNEXPECTED_END_OF_STREAM);
        assertThat(failure(result).phase()).isEqualTo(
                GitClientFailure.Phase.NEGOTIATION);
        assertThat(transport.session.closed).isTrue();
    }

    @Test
    void preservesAuthenticationFailureFromTransport() {
        GitClientTransport transport = (service, remoteUri, options) -> {
            throw new GitClientTransportException(
                    GitClientFailure.Kind.AUTHENTICATION_FAILED,
                    false,
                    "Remote authentication failed");
        };

        GitClientResult<GitRemoteAdvertisement> result =
                new GitUploadPackClient(transport).discover(
                        REMOTE, GitClientOptions.defaults());

        assertThat(failure(result).kind()).isEqualTo(
                GitClientFailure.Kind.AUTHENTICATION_FAILED);
        assertThat(failure(result).retryable()).isFalse();
    }

    @Test
    void discoversEmptyRepositoryAdvertisement() {
        byte[] emptyAdvertisement = concat(
                packet(GitClientValidation.NULL_ID
                        + " capabilities^{}\0side-band-64k\n"),
                flush());
        RecordingTransport transport = new RecordingTransport(
                emptyAdvertisement);

        GitClientResult<GitRemoteAdvertisement> result =
                new GitUploadPackClient(transport).discover(
                        REMOTE, GitClientOptions.defaults());

        assertThat(success(result).refs()).isEmpty();
        assertThat(transport.session.closed).isTrue();
    }

    @Test
    void pushesPackAndReturnsRemoteRefRejection() throws Exception {
        byte[] report = concat(
                packet("unpack ok\n"),
                packet("ng refs/heads/main non-fast-forward\n"),
                flush());
        byte[] response = concat(
                advertisement("report-status side-band-64k"),
                sideBandPacket(1, report),
                flush());
        RecordingTransport transport = new RecordingTransport(response);
        byte[] pack = "PACKpayload".getBytes(StandardCharsets.US_ASCII);
        GitReceivePackRequest request = new GitReceivePackRequest(
                List.of(new GitReceivePackRequest.Command(
                        OLD_ID, NEW_ID, "refs/heads/main")),
                output -> output.write(pack));

        GitClientResult<GitReceivePackResult> result =
                new GitReceivePackClient(transport).push(
                        REMOTE, GitClientOptions.defaults(), request);

        GitReceivePackResult value = success(result);
        assertThat(value.accepted()).isFalse();
        assertThat(value.unpackStatus()).isEqualTo("ok");
        assertThat(value.refs()).containsExactly(
                new GitReceivePackResult.RefStatus(
                        "refs/heads/main", false, "non-fast-forward"));
        assertThat(transport.service).isEqualTo(GitClientService.RECEIVE_PACK);
        assertThat(transport.session.output.bytes()).endsWith(pack);
        assertThat(transport.session.output.ascii())
                .contains("\0report-status side-band-64k\n");
        assertThat(transport.session.closed).isTrue();
    }

    @Test
    void requestsAtomicReceivePackWhenRequired() {
        byte[] response = concat(
                advertisement("report-status atomic"),
                packet("unpack ok\n"),
                packet("ok refs/heads/main\n"),
                flush());
        RecordingTransport transport = new RecordingTransport(response);
        GitReceivePackRequest request = new GitReceivePackRequest(
                List.of(new GitReceivePackRequest.Command(
                        OLD_ID, NEW_ID, "refs/heads/main")),
                output -> { },
                true);

        GitClientResult<GitReceivePackResult> result =
                new GitReceivePackClient(transport).push(
                        REMOTE, GitClientOptions.defaults(), request);

        assertThat(success(result).accepted()).isTrue();
        assertThat(transport.session.output.ascii())
                .contains("\0report-status atomic\n");
    }

    @Test
    void rejectsPushStatusWithoutAStatusForEverySentCommand() {
        assertMalformedPushStatus(packet("unpack ok\n"));
        assertMalformedPushStatus(concat(
                packet("unpack ok\n"),
                packet("ok refs/heads/other\n")));
        assertMalformedPushStatus(concat(
                packet("unpack ok\n"),
                packet("ok refs/heads/main\n"),
                packet("ok refs/heads/main\n")));
    }

    @Test
    void preservesUncheckedProgressCallbackFailureAndClosesSession() {
        RecordingTransport transport = new RecordingTransport(concat(
                advertisement("side-band-64k"),
                packet("NAK\n"),
                sideBandPacket(2, "progress\n".getBytes(StandardCharsets.UTF_8)),
                flush()));
        GitUploadPackRequest request = new GitUploadPackRequest(
                List.of(OLD_ID),
                List.of(),
                new RecordingOutput(),
                ignored -> {
                    throw new IllegalStateException("progress callback failed");
                });

        assertThatThrownBy(() -> new GitUploadPackClient(transport).fetch(
                REMOTE, GitClientOptions.defaults(), request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("progress callback failed");
        assertThat(transport.session.closed).isTrue();
    }

    @Test
    void reportsReadTransportErrorAsTransportFailure() {
        FailingReadTransport transport = new FailingReadTransport();

        GitClientResult<GitRemoteAdvertisement> result =
                new GitUploadPackClient(transport).discover(
                        REMOTE, GitClientOptions.defaults());

        assertThat(failure(result).kind()).isEqualTo(
                GitClientFailure.Kind.TRANSPORT_UNAVAILABLE);
        assertThat(failure(result).phase()).isEqualTo(
                GitClientFailure.Phase.ADVERTISEMENT);
        assertThat(transport.session.closed).isTrue();
    }

    @Test
    void rejectsPushPackThatExceedsConfiguredLimit() {
        RecordingTransport transport = new RecordingTransport(
                advertisement("report-status"));
        GitReceivePackRequest request = new GitReceivePackRequest(
                List.of(new GitReceivePackRequest.Command(
                        OLD_ID, NEW_ID, "refs/heads/main")),
                output -> output.write(repeated((byte) 'p', 11)));
        GitClientOptions options = new GitClientOptions(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                10);

        GitClientResult<GitReceivePackResult> result =
                new GitReceivePackClient(transport).push(
                        REMOTE, options, request);

        assertThat(failure(result).kind()).isEqualTo(
                GitClientFailure.Kind.PACK_SIZE_LIMIT_EXCEEDED);
        assertThat(failure(result).phase()).isEqualTo(
                GitClientFailure.Phase.PACK_TRANSFER);
        assertThat(transport.session.closed).isTrue();
    }

    @Test
    void timeoutClosesBlockedTransportSession() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        GitClientOptions options = new GitClientOptions(
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMillis(50),
                1024);

        GitClientResult<GitRemoteAdvertisement> result =
                new GitUploadPackClient(transport).discover(REMOTE, options);

        assertThat(failure(result).kind())
                .isEqualTo(GitClientFailure.Kind.TIMEOUT);
        assertThat(transport.opened.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(transport.session.closed).isTrue();
    }

    @Test
    void timeoutClosesSessionOpenedAfterCancellation() throws Exception {
        LateOpeningTransport transport = new LateOpeningTransport();
        GitClientOptions options = new GitClientOptions(
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMillis(50),
                1024);

        GitClientResult<GitRemoteAdvertisement> result =
                new GitUploadPackClient(transport).discover(REMOTE, options);

        assertThat(failure(result).kind()).isEqualTo(GitClientFailure.Kind.TIMEOUT);
        assertThat(transport.openStarted.await(1, TimeUnit.SECONDS)).isTrue();
        transport.allowOpen.countDown();
        assertThat(transport.session.closed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(transport.session.usedByProtocol).isFalse();
    }

    private static void assertMalformedPushStatus(byte[] report) {
        RecordingTransport transport = new RecordingTransport(concat(
                advertisement("report-status"), report, flush()));
        GitReceivePackRequest request = new GitReceivePackRequest(
                List.of(new GitReceivePackRequest.Command(
                        OLD_ID, NEW_ID, "refs/heads/main")),
                output -> { });

        GitClientResult<GitReceivePackResult> result =
                new GitReceivePackClient(transport).push(
                        REMOTE, GitClientOptions.defaults(), request);

        assertThat(failure(result).kind()).isEqualTo(
                GitClientFailure.Kind.MALFORMED_RESPONSE);
        assertThat(failure(result).phase()).isEqualTo(
                GitClientFailure.Phase.REPORT_STATUS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(GitClientResult<T> result) {
        assertThat(result).isInstanceOf(GitClientResult.Success.class);
        return ((GitClientResult.Success<T>) result).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> GitClientFailure failure(GitClientResult<T> result) {
        assertThat(result).isInstanceOf(GitClientResult.Failed.class);
        return ((GitClientResult.Failed<T>) result).failure();
    }

    private static byte[] advertisement(String capabilities) {
        return concat(
                packet(OLD_ID + " refs/heads/main\0" + capabilities + "\n"),
                flush());
    }

    private static byte[] sideBandPacket(int channel, byte[] data) {
        byte[] payload = new byte[data.length + 1];
        payload[0] = (byte) channel;
        System.arraycopy(data, 0, payload, 1, data.length);
        return packet(payload);
    }

    private static byte[] packet(String payload) {
        return packet(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] packet(byte[] payload) {
        byte[] header = "%04x".formatted(payload.length + 4)
                .getBytes(StandardCharsets.US_ASCII);
        return concat(header, payload);
    }

    private static byte[] flush() {
        return "0000".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] repeated(byte value, int count) {
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static byte[] concat(byte[]... chunks) {
        int size = 0;
        for (byte[] chunk : chunks) {
            size += chunk.length;
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private static final class RecordingTransport implements GitClientTransport {
        private final RecordingSession session;
        private GitClientService service;
        private boolean openedOnVirtualThread;

        private RecordingTransport(byte[] input) {
            session = new RecordingSession(new FragmentedInput(input));
        }

        @Override
        public GitClientTransportSession open(
                GitClientService service,
                URI remoteUri,
                GitClientOptions options) {
            this.service = service;
            openedOnVirtualThread = Thread.currentThread().isVirtual();
            return session;
        }
    }

    private static final class BlockingTransport implements GitClientTransport {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final BlockingSession session = new BlockingSession();

        @Override
        public GitClientTransportSession open(
                GitClientService service,
                URI remoteUri,
                GitClientOptions options) {
            opened.countDown();
            return session;
        }
    }

    private static final class LateOpeningTransport implements GitClientTransport {
        private final CountDownLatch openStarted = new CountDownLatch(1);
        private final CountDownLatch allowOpen = new CountDownLatch(1);
        private final LateOpeningSession session = new LateOpeningSession();

        @Override
        public GitClientTransportSession open(
                GitClientService service,
                URI remoteUri,
                GitClientOptions options) {
            openStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    allowOpen.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return session;
        }
    }

    private static final class FailingReadTransport implements GitClientTransport {
        private final RecordingSession session = new RecordingSession(
                new BufferedByteInput() {
                    @Override
                    public int available() {
                        return 0;
                    }

                    @Override
                    public int readUnsignedByte() throws IOException {
                        throw new IOException("connection reset");
                    }

                    @Override
                    public ByteBuf readCopy(int length, ByteBufAllocator allocator) {
                        throw new AssertionError("not reached");
                    }

                    @Override
                    public int readInto(ByteBuf target, int maxLength) {
                        return 0;
                    }
                });

        @Override
        public GitClientTransportSession open(
                GitClientService service,
                URI remoteUri,
                GitClientOptions options) {
            return session;
        }
    }

    private static final class RecordingSession
            implements GitClientTransportSession {
        private final BufferedByteInput input;
        private final RecordingOutput output = new RecordingOutput();
        private volatile boolean closed;

        private RecordingSession(BufferedByteInput input) {
            this.input = input;
        }

        @Override
        public BufferedByteInput input() {
            return input;
        }

        @Override
        public BufferedByteOutput output() {
            return output;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class BlockingSession
            implements GitClientTransportSession {
        private final CountDownLatch close = new CountDownLatch(1);
        private volatile boolean closed;

        @Override
        public BufferedByteInput input() {
            return new BufferedByteInput() {
                @Override
                public int available() {
                    return 0;
                }

                @Override
                public int readUnsignedByte() throws IOException {
                    try {
                        close.await();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", error);
                    }
                    throw new EOFException();
                }

                @Override
                public ByteBuf readCopy(
                        int length,
                        ByteBufAllocator allocator) throws EOFException {
                    throw new EOFException();
                }

                @Override
                public int readInto(ByteBuf target, int maxLength) {
                    return 0;
                }
            };
        }

        @Override
        public BufferedByteOutput output() {
            return new RecordingOutput();
        }

        @Override
        public void close() {
            closed = true;
            close.countDown();
        }
    }

    private static final class LateOpeningSession
            implements GitClientTransportSession {
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean usedByProtocol;

        @Override
        public BufferedByteInput input() {
            usedByProtocol = true;
            throw new AssertionError("session must close before use");
        }

        @Override
        public BufferedByteOutput output() {
            usedByProtocol = true;
            throw new AssertionError("session must close before use");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class FragmentedInput implements BufferedByteInput {
        private final byte[] bytes;
        private int offset;

        private FragmentedInput(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int available() {
            return bytes.length - offset;
        }

        @Override
        public int readUnsignedByte() throws EOFException {
            if (offset == bytes.length) {
                throw new EOFException();
            }
            return bytes[offset++] & 0xff;
        }

        @Override
        public ByteBuf readCopy(int length, ByteBufAllocator allocator)
                throws EOFException {
            if (length > available()) {
                throw new EOFException();
            }
            ByteBuf result = allocator.buffer(length, length);
            for (int index = 0; index < length; index++) {
                result.writeByte(readUnsignedByte());
            }
            return result;
        }

        @Override
        public int readInto(ByteBuf target, int maxLength) {
            int length = Math.min(Math.min(3, maxLength), available());
            target.writeBytes(bytes, offset, length);
            offset += length;
            return length;
        }
    }

    private static final class RecordingOutput implements BufferedByteOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(ByteBuf buffer) {
            byte[] chunk = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), chunk);
            bytes.writeBytes(chunk);
        }

        @Override
        public void flush() {
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }

        private String ascii() {
            return bytes.toString(StandardCharsets.US_ASCII);
        }
    }
}
