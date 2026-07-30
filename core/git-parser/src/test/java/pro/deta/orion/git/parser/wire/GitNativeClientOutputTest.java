package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void mapsEverySendResultToAContinuationTransition() {
        Continuation<ByteBuf> next = input -> ContinuationFlow.await();
        Runnable task = () -> {
        };
        IllegalStateException failure =
                new IllegalStateException("failed");

        assertThat(new GitNativeClientOutput.SendResult.Completed()
                .transitionTo(next))
                .isEqualTo(ContinuationFlow.transition(next));
        assertThat(new GitNativeClientOutput.SendResult.Streaming(task)
                .transitionTo(next))
                .isEqualTo(
                        ContinuationFlow.transitionAndYield(next, task));
        assertThat(new GitNativeClientOutput.SendResult.Failed(
                "output failed",
                failure).transitionTo(next))
                .isInstanceOfSatisfying(
                        ContinuationFlow.Transition.class,
                        transition -> assertThat(transition.next())
                                .isInstanceOfSatisfying(
                                        Continuation.CompletedError.class,
                                        error -> {
                                            assertThat(error.message())
                                                    .isEqualTo(
                                                            "output failed");
                                            assertThat(error.throwable())
                                                    .isSameAs(failure);
                                        }));
    }

    @Test
    void sendsProtocolV2UploadPackAdvertisement() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendV2UploadPackAdvertisement())
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "000eversion 2\n"
                                    + "000cls-refs\n"
                                    + "0012fetch=shallow\n"
                                    + "0012server-option\n"
                                    + "0000");
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsNakAsPktLine() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendNak())
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            assertThat(outbound.toString(StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsEveryAckStatusAsPktLine() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);
        Map<GitNativeClientOutput.AckStatus, String> expected =
                new LinkedHashMap<>();
        expected.put(
                GitNativeClientOutput.AckStatus.FINAL,
                "0031ACK " + MAIN_ID + "\n");
        expected.put(
                GitNativeClientOutput.AckStatus.CONTINUE,
                "003aACK " + MAIN_ID + " continue\n");
        expected.put(
                GitNativeClientOutput.AckStatus.COMMON,
                "0038ACK " + MAIN_ID + " common\n");
        expected.put(
                GitNativeClientOutput.AckStatus.READY,
                "0037ACK " + MAIN_ID + " ready\n");

        try {
            for (Map.Entry<GitNativeClientOutput.AckStatus, String> entry
                    : expected.entrySet()) {
                assertThat(output.sendAck(
                        GitObjectId.of(MAIN_ID),
                        entry.getKey()))
                        .isInstanceOf(
                                GitNativeClientOutput.SendResult.Completed.class);
                assertThat(outbound.toString(StandardCharsets.US_ASCII))
                        .isEqualTo(entry.getValue());
                outbound.clear();
            }
        } finally {
            outbound.release();
        }
    }

    @Test
    void sendsLegacyPackOnEveryTypedSideBandChannel() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);

        try {
            for (GitNativeClientOutput.SideBandChannel channel
                    : GitNativeClientOutput.SideBandChannel.values()) {
                outbound.clear();
                List<byte[]> sent = new ArrayList<>();
                GitNativeClientOutput output =
                        collectingOutput(outbound, sent);

                complete(output.beginLegacySideBand64k(
                        producer(new byte[] {'P', 'A', 'C', 'K'}),
                        channel));
                ByteBuf response = Unpooled.wrappedBuffer(
                        sent.toArray(byte[][]::new));
                assertThat(response.readCharSequence(
                        8,
                        StandardCharsets.US_ASCII))
                        .hasToString("0008NAK\n");
                assertThat(response.readCharSequence(
                        4,
                        StandardCharsets.US_ASCII))
                        .hasToString("0009");
                assertThat(response.readByte())
                        .isEqualTo(channel.wireValue());
                byte[] pack = new byte[4];
                response.readBytes(pack);
                assertThat(pack)
                        .containsExactly('P', 'A', 'C', 'K');
                assertThat(response.readCharSequence(
                        4,
                        StandardCharsets.US_ASCII))
                        .hasToString("0000");
                response.release();
            }
        } finally {
            outbound.release();
        }
    }

    @Test
    void fragmentsLegacySideBand64kPackAtPktLineLimit() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        byte[] pack = new byte[65_516];
        pack[0] = 'P';
        pack[1] = 'A';
        pack[2] = 'C';
        pack[3] = 'K';

        GitNativeClientOutput.LegacySideBandResponse sideBandResponse =
                output.beginLegacySideBand64k(
                        producer(pack),
                        GitNativeClientOutput.SideBandChannel.DATA);

        try {
            int taskCount = 0;
            while (true) {
                GitNativeClientOutput.SendResult result =
                        sideBandResponse.advance();
                if (result instanceof
                        GitNativeClientOutput.SendResult.Completed) {
                    break;
                }
                GitNativeClientOutput.SendResult.Streaming streaming =
                        (GitNativeClientOutput.SendResult.Streaming) result;
                int submissionsBefore = sent.size();
                streaming.task().run();
                taskCount++;
                assertThat(sent)
                        .hasSize(submissionsBefore + 1);
            }
            assertThat(taskCount).isGreaterThan(1);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                response.writeBytes(chunk);
            }
            byte[] bytes = response.toByteArray();
            assertThat(new String(
                    bytes,
                    0,
                    8,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
            assertThat(new String(
                    bytes,
                    8,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("fff0");
            assertThat(bytes[12]).isEqualTo((byte) 1);
            assertThat(new String(
                    bytes,
                    65_528,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0006");
            assertThat(bytes[65_532]).isEqualTo((byte) 1);
            assertThat(new String(
                    bytes,
                    bytes.length - 4,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0000");
        } finally {
            outbound.release();
        }
    }

    private static void complete(
            GitNativeClientOutput.LegacySideBandResponse response) {
        while (true) {
            GitNativeClientOutput.SendResult result =
                    response.advance();
            if (result
                    instanceof GitNativeClientOutput.SendResult.Completed) {
                return;
            }
            assertThat(result)
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Streaming.class);
            ((GitNativeClientOutput.SendResult.Streaming) result)
                    .task()
                    .run();
        }
    }

    private static NativePackProducer producer(byte[] bytes) {
        return new NativePackProducer() {
            private int offset;

            @Override
            public Result produce(ByteBuf destination) {
                int length = Math.min(
                        destination.writableBytes(),
                        bytes.length - offset);
                destination.writeBytes(bytes, offset, length);
                offset += length;
                return offset == bytes.length
                        ? Result.COMPLETED
                        : Result.MORE;
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void streamsAckAfterExistingPartiallyFilledOutput() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        int initialWriterIndex = outbound.capacity() - 10;
        outbound.writerIndex(initialWriterIndex);
        outbound.setByte(initialWriterIndex - 1, 'x');
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendAck(
                                GitObjectId.of(MAIN_ID),
                                GitNativeClientOutput.AckStatus.READY);

        try {
            assertThat(output.sendNak())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));

            streaming.task().run();

            ByteArrayOutputStream allSent = new ByteArrayOutputStream();
            for (byte[] chunk : sent) {
                allSent.writeBytes(chunk);
            }
            byte[] bytes = allSent.toByteArray();
            assertThat(bytes[initialWriterIndex - 1])
                    .isEqualTo((byte) 'x');
            assertThat(new String(
                    bytes,
                    initialWriterIndex,
                    bytes.length - initialWriterIndex,
                    StandardCharsets.US_ASCII))
                    .isEqualTo(
                            "0037ACK " + MAIN_ID + " ready\n");
            assertThat(output.sendNak())
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
        } finally {
            outbound.release();
        }
    }

    @Test
    void returnsFailedWhenAckCannotBeSerialized() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        GitNativeClientOutput output = new GitNativeClientOutput(outbound);

        try {
            assertThat(output.sendAck(
                    GitObjectId.of("x".repeat(65 * 1024)),
                    GitNativeClientOutput.AckStatus.FINAL))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> {
                                assertThat(failed.message())
                                        .contains("serialize");
                                assertThat(failed.cause())
                                        .isInstanceOf(
                                                IllegalArgumentException.class);
                            });
            assertThat(outbound.writerIndex()).isZero();
        } finally {
            outbound.release();
        }
    }

    @Test
    void streamsNakWhenOutputIsAlreadyFull() {
        ByteBuf outbound = Unpooled.buffer(64 * 1024, 64 * 1024);
        outbound.writerIndex(outbound.capacity());
        outbound.setByte(outbound.writerIndex() - 1, 'x');
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);

        GitNativeClientOutput.SendResult.Streaming streaming =
                (GitNativeClientOutput.SendResult.Streaming)
                        output.sendNak();
        streaming.task().run();

        try {
            assertThat(sent).hasSize(2);
            assertThat(sent.getFirst()).hasSize(outbound.capacity());
            assertThat(sent.getFirst()[outbound.capacity() - 1])
                    .isEqualTo((byte) 'x');
            assertThat(new String(
                    sent.getLast(),
                    StandardCharsets.US_ASCII))
                    .isEqualTo("0008NAK\n");
        } finally {
            outbound.release();
        }
    }

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
            assertThat(output.sendAdvertisement(advertisement))
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.message())
                                    .contains("already in progress"));

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

    private static GitNativeClientOutput collectingOutput(
            ByteBuf outbound,
            List<byte[]> sent) {
        return new GitNativeClientOutput(
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
    }
}
