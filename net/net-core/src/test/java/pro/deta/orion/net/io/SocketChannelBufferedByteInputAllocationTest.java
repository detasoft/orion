package pro.deta.orion.net.io;

import io.netty.buffer.UnpooledByteBufAllocator;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SocketChannelBufferedByteInputAllocationTest {
    @Test
    void directReadsDoNotAllocateByteArraysOnTheInputStack() throws Exception {
        try (SocketChannelBufferedByteInputTest.SocketPair sockets = SocketChannelBufferedByteInputTest.SocketPair.open();
                SocketChannelBufferedByteInput input =
                        new SocketChannelBufferedByteInput(sockets.client(), UnpooledByteBufAllocator.DEFAULT, 4)) {
            writeAscii(sockets.server(), "abcdef");

            Path recordingFile = Files.createTempFile("orion-net-core-allocations", ".jfr");
            try (Recording recording = byteArrayAllocationRecording()) {
                recording.start();
                int sum = 0;
                for (int index = 0; index < 6; index++) {
                    sum += input.readUnsignedByte();
                }
                recording.stop();
                recording.dump(recordingFile);

                assertThat(sum).isEqualTo('a' + 'b' + 'c' + 'd' + 'e' + 'f');
                assertThat(byteArrayAllocationsOnInputStack(recordingFile)).isEmpty();
            } finally {
                Files.deleteIfExists(recordingFile);
            }
        }
    }

    private static Recording byteArrayAllocationRecording() {
        Recording recording = new Recording();
        recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace().withThreshold(Duration.ZERO);
        recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace().withThreshold(Duration.ZERO);
        return recording;
    }

    private static List<String> byteArrayAllocationsOnInputStack(Path recordingFile) throws Exception {
        List<String> allocations = new ArrayList<>();
        try (RecordingFile events = new RecordingFile(recordingFile)) {
            while (events.hasMoreEvents()) {
                RecordedEvent event = events.readEvent();
                if (isByteArrayAllocation(event) && hasInputStackFrame(event)) {
                    allocations.add(event.toString());
                }
            }
        }
        return allocations;
    }

    private static boolean isByteArrayAllocation(RecordedEvent event) {
        String className = event.getClass("objectClass").getName();
        return "byte[]".equals(className) || "[B".equals(className);
    }

    private static boolean hasInputStackFrame(RecordedEvent event) {
        if (event.getStackTrace() == null) {
            return false;
        }
        for (RecordedFrame frame : event.getStackTrace().getFrames()) {
            String className = frame.getMethod().getType().getName();
            if (SocketChannelBufferedByteInput.class.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static void writeAscii(SocketChannel channel, String value) throws Exception {
        ByteBuffer buffer = StandardCharsets.US_ASCII.encode(value);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
