package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitNativeClientOutputTest {
    private static final String MAIN_ID =
            "1111111111111111111111111111111111111111";
    private static final String TAG_ID =
            "2222222222222222222222222222222222222222";
    private static final String PEELED_TAG_ID =
            "3333333333333333333333333333333333333333";

    @Test
    void sendsTypedLegacyAdvertisementAsPktLines() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(
                        GitCapability.MULTI_ACK,
                        GitCapability.agent("orion-native")),
                List.of(
                        GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"),
                        GitAdvertisedRef.direct(TAG_ID, "refs/tags/v1")
                                .withPeeledObjectId(PEELED_TAG_ID)));

        assertThat(output.sendAdvertisement(advertisement))
                .isInstanceOf(
                        GitNativeClientOutput.SendResult.Completed.class);

        try {
            assertThat(outbound.toString(StandardCharsets.UTF_8)).isEqualTo(
                    "005a" + MAIN_ID
                            + " refs/heads/main\0multi_ack agent=orion-native\n"
                            + "003a" + TAG_ID + " refs/tags/v1\n"
                            + "003d" + PEELED_TAG_ID + " refs/tags/v1^{}\n"
                            + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void leavesBufferUnchangedWhenAdvertisementDoesNotFit() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        int writerIndex = outbound.writerIndex();
        try {
            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Streaming.class);
            assertThat(outbound.writerIndex()).isGreaterThan(writerIndex);
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamingTaskSendsBufferedPrefixAndRemainingAdvertisement() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        outbound.setByte(outbound.writerIndex() - 1, 'x');
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                chunk -> {
                    try {
                        byte[] bytes = new byte[chunk.readableBytes()];
                        chunk.readBytes(bytes);
                        sent.add(bytes);
                    } finally {
                        chunk.release();
                    }
                });
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        streaming.task().run();

        try {
            assertThat(sent).hasSize(2);
            ByteArrayOutputStream allSent = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                allSent.writeBytes(chunk);
            }
            byte[] bytes = allSent.toByteArray();
            assertThat(bytes[outbound.capacity() - 3]).isZero();
            assertThat(bytes[outbound.capacity() - 2]).isEqualTo((byte) 'x');
            assertThat(new String(
                    bytes,
                    outbound.capacity() - 1,
                    bytes.length - outbound.capacity() + 1,
                    StandardCharsets.UTF_8))
                    .isEqualTo(
                            "003e" + MAIN_ID
                                    + " refs/heads/main\0\n0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void rejectsConcurrentOperationUntilStreamingTaskCompletes() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ByteBuf::release);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        try {
            assertThatThrownBy(
                    () -> output.sendAdvertisement(advertisement))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already in progress");

            streaming.task().run();

            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
        } finally {
            outbound.release();
        }
    }

    @Test
    void submittedChunksRemainStableAfterOutputBufferReuse() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        outbound.setByte(outbound.writerIndex() - 1, 'x');
        List<ByteBuf> submitted = new ArrayList<>();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                submitted::add);
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        streaming.task().run();

        try {
            assertThat(submitted).hasSize(2);
            assertThat(submitted.getFirst().getByte(
                    submitted.getFirst().writerIndex() - 2))
                    .isEqualTo((byte) 'x');
            assertThat(submitted.getFirst().getByte(
                    submitted.getFirst().writerIndex() - 1))
                    .isEqualTo((byte) '0');
            assertThat(submitted.getLast().toString(
                    StandardCharsets.UTF_8))
                    .endsWith("0000");
        } finally {
            for (ByteBuf chunk : submitted) {
                chunk.release();
            }
            outbound.release();
        }
    }

    @Test
    void streamsAdvertisementAcrossMultipleFullBuffers() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                chunk -> {
                    try {
                        byte[] bytes = new byte[chunk.readableBytes()];
                        chunk.readBytes(bytes);
                        sent.add(bytes);
                    } finally {
                        chunk.release();
                    }
                });
        List<GitAdvertisedRef> refs = new ArrayList<>();
        for (int index = 0; index < 3_000; index++) {
            refs.add(GitAdvertisedRef.direct(
                    "%040x".formatted(index + 1),
                    "refs/heads/branch-%04d".formatted(index)));
        }
        GitV1Advertisement advertisement =
                new GitV1Advertisement(List.of(), refs);

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);
        streaming.task().run();

        try {
            assertThat(sent).hasSizeGreaterThan(2);
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                actual.writeBytes(chunk);
            }
            assertThat(actual.toByteArray())
                    .containsExactly(expectedAdvertisement(refs));
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamingTaskPropagatesSendFailureAndReleasesOperation() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity() - 1);
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ignored -> {
                    throw new IllegalStateException("send failed");
                });
        GitV1Advertisement advertisement = new GitV1Advertisement(
                List.of(),
                List.of(GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main")));

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAdvertisement(advertisement);

        try {
            assertThatThrownBy(streaming.task()::run)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("send failed");
            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
        } finally {
            outbound.release();
        }
    }

    @Test
    void isAvailableToWireContinuationsThroughContext() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            GitMinimalWireMachine.Context context =
                    GitMinimalWireMachine.testContext(
                            UnpooledByteBufAllocator.DEFAULT,
                            output);

            assertThat(context.clientOutput).isSameAs(output);
        } finally {
            outbound.release();
        }
    }

    private static byte[] expectedAdvertisement(
            List<GitAdvertisedRef> refs) {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int index = 0; index < refs.size(); index++) {
            GitAdvertisedRef ref = refs.get(index);
            String suffix = index == 0 ? "\0\n" : "\n";
            byte[] payload = (ref.objectId()
                    + " "
                    + ref.name()
                    + suffix).getBytes(StandardCharsets.UTF_8);
            expected.writeBytes(
                    "%04x".formatted(payload.length + 4)
                            .getBytes(StandardCharsets.US_ASCII));
            expected.writeBytes(payload);
        }
        expected.writeBytes(
                "0000".getBytes(StandardCharsets.US_ASCII));
        return expected.toByteArray();
    }
}
