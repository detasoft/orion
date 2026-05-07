package pro.deta.orion.util.stream;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static pro.deta.orion.util.stream.ByteToAsciiConversion.LINE_SEPARATOR;

/**
 * Wraps a client/server/error stream triple and records the byte-level conversation.
 *
 * <p>The recording is not just a flat byte array: every contiguous run is tagged with the direction
 * it came from. That makes the serialized form useful as a replay script for protocol tests.</p>
 */
@Slf4j
public class RecordingStandardStreams extends StandardStreams {
    private final StringBuilder ioStateStringBuilder;
    private final SwitchingBuffer state;

    public RecordingStandardStreams(InputStream inputStream, OutputStream outputStream, OutputStream errorStream, StringBuilder ioStateStringBuilder) {
        this(inputStream, outputStream, errorStream, ioStateStringBuilder, new SwitchingBuffer());
    }

    /**
     * Builds a stream object from a previously serialized interaction. The real streams are null streams
     * because this mode is only used to expose the parsed directional state for replay/assertion helpers.
     */
    public RecordingStandardStreams(String serializedState) {
        this(
                InputStream.nullInputStream(),
                OutputStream.nullOutputStream(),
                OutputStream.nullOutputStream(),
                new StringBuilder(),
                new SwitchingBuffer(parseSerializedState(serializedState))
        );
    }

    private RecordingStandardStreams(InputStream inputStream, OutputStream outputStream, OutputStream errorStream, StringBuilder ioStateStringBuilder, SwitchingBuffer state) {
        this(wrapStreams(inputStream, outputStream, errorStream, state), ioStateStringBuilder);
    }

    private RecordingStandardStreams(WrappedStreams streams, StringBuilder ioStateStringBuilder) {
        super(streams.inputStream(), streams.outputStream(), streams.errorStream());
        this.ioStateStringBuilder = ioStateStringBuilder;
        this.state = streams.state();
    }

    private static WrappedStreams wrapStreams(InputStream inputStream, OutputStream outputStream, OutputStream errorStream, SwitchingBuffer state) {
        return new WrappedStreams(
                wrapInTee(inputStream, state),
                wrapInTee(Direction.S, outputStream, state),
                wrapInTee(Direction.E, errorStream, state),
                state);
    }

    private static TeeInputStream wrapInTee(InputStream inputStream, SwitchingBuffer state) {
        return new TeeInputStream(inputStream, wrapInDirectionalOs(Direction.C, state));
    }

    private static TeeOutputStream wrapInTee(Direction d, OutputStream outputStream, SwitchingBuffer state) {
        return new TeeOutputStream(outputStream, wrapInDirectionalOs(d, state));
    }

    private static OutputStream wrapInDirectionalOs(Direction d, SwitchingBuffer state) {
        return new OutputStream() {
            @Override
            public void write(int b) {
                state.writeInDirection(d, b);
            }

            @Override
            public void close() throws IOException {
                super.close();
                log.debug(d.name() + "Stream closed");
            }
        };
    }

    private record WrappedStreams(TeeInputStream inputStream, TeeOutputStream outputStream, TeeOutputStream errorStream,
                                  SwitchingBuffer state) {
    }

    /**
     * Keeps the transcript as ordered direction chunks instead of individual bytes.
     *
     * <p>Protocol tests usually need to know when bytes came from the client, server, or stderr. A direction
     * change starts a new buffer; repeated writes in the same direction append to the current buffer.
     * Writes can be triggered by different pipe participants, so the public write path is synchronized to keep
     * chunk boundaries deterministic.</p>
     */
    private static final class SwitchingBuffer {
        private final List<DirectionalByteArrayOutputStream> state = new ArrayList<>();

        private SwitchingBuffer() {
            state.add(new DirectionalByteArrayOutputStream(Direction.C));
        }

        private SwitchingBuffer(List<DirectionalByteArrayOutputStream> state) {
            this.state.addAll(state);
        }

        DirectionalByteArrayOutputStream getLast(Direction d) {
            DirectionalByteArrayOutputStream last = state.getLast();
            if (last.getDirection() != d) {
                log.trace("{}", serializeDBAOS(new StringBuilder(), last));
                last = switchBuffer(d);
            }
            return last;
        }

        private DirectionalByteArrayOutputStream switchBuffer(Direction direction) {
            DirectionalByteArrayOutputStream buf = new DirectionalByteArrayOutputStream(direction);
            state.add(buf);
            return buf;
        }

        public List<DirectionalByteArrayOutputStream> getStates() {
            return state;
        }

        private synchronized void writeInDirection(Direction direction, int b) {
            getLast(direction).write(b);
        }
    }

    public List<DirectionalByteArrayOutputStream> getStates() {
        return state.getStates();
    }

    public StringBuilder serializeIntoAscii(StringBuilder sb) {
        for (DirectionalByteArrayOutputStream directionalByteArrayOutputStream: state.getStates()) {
            serializeDBAOS(sb, directionalByteArrayOutputStream);
            sb.append("\n");
        }
        return sb;
    }

    private static StringBuilder serializeDBAOS(StringBuilder sb, DirectionalByteArrayOutputStream directionalByteArrayOutputStream) {
        if (directionalByteArrayOutputStream.size() == 0)
            return sb;

        sb.append(directionalByteArrayOutputStream.getDirection().name().charAt(0));
        sb.append(":");
        for (byte b: directionalByteArrayOutputStream.toByteArray()) {
            if (ByteToAsciiConversion.isAsciiPrintable(b) && b != '\\') {
                sb.append(Character.valueOf((char) b));
            } else
                sb.append(String.format("\\%02X", b));
        }
        return sb;
    }

    private static List<DirectionalByteArrayOutputStream> parseSerializedState(String serializedState) {
        Scanner scanner = new Scanner(serializedState);
        scanner.useDelimiter(LINE_SEPARATOR);
        List<DirectionalByteArrayOutputStream> result = new ArrayList<>();
        while (scanner.hasNext()) {
            String line = scanner.nextLine();
            result.add(parseDirectionalBuffer(line));
        }
        return result;
    }

    private static DirectionalByteArrayOutputStream parseDirectionalBuffer(String line) {
        Direction direction = null;
        ByteBuffer bb = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
        ByteBuffer resultBytes = ByteBuffer.allocate(line.length() - 2);
        HexFormat hexFormat = HexFormat.of();
        byte[] byteEncoded = new byte[2];
        while (bb.position() < bb.limit()) {
            if (bb.position() == 0) {
                direction = Direction.valueOf(new String(Character.toChars(bb.get())));
                bb.get();
            } else {
                byte b = bb.get();
                if ((char) b == '\\') {
                    bb.get(byteEncoded);
                    resultBytes.put(hexFormat.parseHex(new String(byteEncoded)));
                } else
                    resultBytes.put(b);
            }
        }
        byte[] arr = StreamUtils.getByteArray(resultBytes);
        return new DirectionalByteArrayOutputStream(direction, arr);
    }

    @Override
    public void close() {
        serializeIntoAscii(ioStateStringBuilder);
        log.trace("Stream interaction:\n{}", ioStateStringBuilder);
    }
}
