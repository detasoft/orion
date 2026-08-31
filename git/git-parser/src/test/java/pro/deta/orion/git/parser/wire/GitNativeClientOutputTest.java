package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitNativeClientOutputTest {
    private static final String MAIN_ID =
            "1111111111111111111111111111111111111111";
    private static final String TAG_ID =
            "2222222222222222222222222222222222222222";
    private static final String PEELED_TAG_ID =
            "3333333333333333333333333333333333333333";

    @Test
    void sendsProtocolV2UploadPackAdvertisement() throws Exception {
        ByteBuf outbound = outputBuffer();
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            output.sendV2UploadPackAdvertisement(
                    GitWireConfiguration.allSupported().protocolV2());

            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "000eversion 2\n"
                                    + "0013ls-refs=unborn\n"
                                    + "004efetch=shallow wait-for-done filter "
                                    + "ref-in-want sideband-all packfile-uris\n"
                                    + "0012server-option\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void writesLargeResponseSynchronouslyToBufferedByteOutput()
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitNativeClientOutput output = new GitNativeClientOutput(
                UnpooledByteBufAllocator.DEFAULT,
                new OutputStreamBufferedByteOutput(bytes));
        List<GitLsRefsResponse.Ref> refs = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) {
            refs.add(new GitLsRefsResponse.DirectRef(
                    "%040x".formatted(index),
                    "refs/heads/branch-" + index,
                    Optional.empty(),
                    Optional.empty()));
        }

        try {
            output.sendLsRefs(new GitLsRefsResponse(refs));

            byte[] wire = bytes.toByteArray();
            assertThat(wire.length)
                    .isGreaterThan(GitNativeClientOutput.BUFFER_CAPACITY);
            assertThat(new String(
                    wire,
                    wire.length - 4,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0000");
        } finally {
            output.close();
        }
    }

    @Test
    void sendsLegacyAdvertisement() throws Exception {
        ByteBuf outbound = outputBuffer();
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(GitCapability.MULTI_ACK),
                List.of(
                        new GitAdvertisedRef(
                                MAIN_ID,
                                "refs/heads/main",
                                Optional.empty()),
                        new GitAdvertisedRef(
                                TAG_ID,
                                "refs/tags/v1",
                                Optional.of(PEELED_TAG_ID))));

        try {
            output.sendAdvertisement(advertisement);

            assertThat(outbound.toString(StandardCharsets.UTF_8))
                    .isEqualTo(
                            "0047" + MAIN_ID
                                    + " refs/heads/main\0multi_ack\n"
                                    + "003a" + TAG_ID
                                    + " refs/tags/v1\n"
                                    + "003d" + PEELED_TAG_ID
                                    + " refs/tags/v1^{}\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsInvalidLsRefsObjectId() {
        ByteBuf outbound = outputBuffer();
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThatThrownBy(() -> output.sendLsRefs(
                    new GitLsRefsResponse(List.of(
                            new GitLsRefsResponse.DirectRef(
                                    "not-an-object-id",
                                    "refs/heads/main",
                                    Optional.empty(),
                                    Optional.empty())))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(
                            "Git object ID must contain 40 hexadecimal digits");
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsBlankGitErrorMessage() {
        ByteBuf outbound = outputBuffer();
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThatThrownBy(() -> output.sendError(" "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("message must not be blank");
        } finally {
            outbound.release();
        }
    }

    @Test
    void reportsFullDirectBufferAsBlockingOutputFailure() {
        ByteBuf outbound = outputBuffer();
        outbound.writerIndex(outbound.capacity());
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThatThrownBy(output::sendNak)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Native client output buffer is full");
        } finally {
            outbound.release();
        }
    }

    @Test
    void writesLegacySideBandResponse() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitNativeClientOutput output = new GitNativeClientOutput(
                UnpooledByteBufAllocator.DEFAULT,
                new OutputStreamBufferedByteOutput(bytes));
        GitNativeClientOutput.LegacySideBandResponse response =
                output.beginLegacySideBand64k(
                        producer("PACK-data"),
                        GitNativeClientOutput.SideBandChannel.DATA);
        ByteBuf progress = Unpooled.copiedBuffer(
                "counting\n",
                StandardCharsets.US_ASCII);

        try {
            response.progress(progress);
            response.advance();

            assertThat(new String(bytes.toByteArray(), StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0008NAK\n"
                                    + "000e\u0002counting\n"
                                    + "000e\u0001PACK-data"
                                    + "0000");
        } finally {
            progress.release();
            response.close();
            output.close();
        }
    }

    @Test
    void closesProducerWhenConcurrentPackResponseIsRejected() {
        ByteBuf outbound = outputBuffer();
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        TrackingProducer rejected = new TrackingProducer("PACK");
        GitNativeClientOutput.LegacyPackResponse active =
                output.beginLegacyPack(producer("active"));

        try {
            assertThatThrownBy(() -> output.beginLegacyPack(rejected))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Client output operation is already in progress");
            assertThat(rejected.closed).isTrue();
        } finally {
            active.close();
            outbound.release();
        }
    }

    @Test
    void rejectsProducerThatMakesNoProgress() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitNativeClientOutput output = new GitNativeClientOutput(
                UnpooledByteBufAllocator.DEFAULT,
                new OutputStreamBufferedByteOutput(bytes));
        GitNativeClientOutput.LegacyPackResponse response =
                output.beginLegacyPack(new NativePackProducer() {
                    @Override
                    public Result produce(ByteBuf destination) {
                        return Result.MORE;
                    }

                    @Override
                    public void close() {
                    }
                });

        try {
            assertThatThrownBy(response::advance)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Native pack producer made no progress");
        } finally {
            response.close();
            output.close();
        }
    }

    @Test
    void allowsOutputAfterCompletedPackResponse() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GitNativeClientOutput output = new GitNativeClientOutput(
                UnpooledByteBufAllocator.DEFAULT,
                new OutputStreamBufferedByteOutput(bytes));
        GitNativeClientOutput.LegacyPackResponse response =
                output.beginLegacyPack(producer("PACK"));

        try {
            response.advance();

            assertThatCode(output::sendNak).doesNotThrowAnyException();
            assertThat(new String(bytes.toByteArray(), StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\nPACK0008NAK\n");
        } finally {
            output.close();
        }
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }

    private static NativePackProducer producer(String value) {
        return new TrackingProducer(value);
    }

    private static final class TrackingProducer implements NativePackProducer {
        private final byte[] value;
        private int offset;
        private boolean closed;

        private TrackingProducer(String value) {
            this.value = value.getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public Result produce(ByteBuf destination) {
            int length = Math.min(
                    destination.writableBytes(),
                    value.length - offset);
            destination.writeBytes(value, offset, length);
            offset += length;
            return offset == value.length ? Result.COMPLETED : Result.MORE;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
