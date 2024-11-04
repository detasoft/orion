package pro.deta.orion.util.stream;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static pro.deta.orion.util.stream.ByteToAsciiConversion.LINE_SEPARATOR;

@Getter
@Slf4j
public class TeeIOStream implements IOEStreamProvider {
    private final TeeInputStream inputStream;
    private final TeeOutputStream outputStream;
    private final TeeOutputStream errorStream;
    private final StringBuilder ioStateStringBuilder;

    private final SwitchingBuffer state = new SwitchingBuffer();

    public TeeIOStream(InputStream inputStream, OutputStream outputStream, OutputStream errorStream, StringBuilder ioStateStringBuilder) {
        this.inputStream = wrapInTee(inputStream);
        this.outputStream = wrapInTee(Direction.S, outputStream);
        this.errorStream = wrapInTee(Direction.E, errorStream);
        this.ioStateStringBuilder = ioStateStringBuilder;
    }

    private TeeInputStream wrapInTee(InputStream inputStream) {
        return new TeeInputStream(inputStream, wrapInDirectionalOs(Direction.C));
    }

    private TeeOutputStream wrapInTee(Direction d, OutputStream outputStream) {
        return new TeeOutputStream(outputStream, wrapInDirectionalOs(d));
    }

    private OutputStream wrapInDirectionalOs(Direction d) {
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

    private static final class SwitchingBuffer {
        private final List<DirectionalByteArrayOutputStream> state = new ArrayList<>();

        private SwitchingBuffer() {
            state.add(new DirectionalByteArrayOutputStream(Direction.C));
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

    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public OutputStream getErrorStream() {
        return errorStream;
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
            if (ByteToAsciiConversion.isAsciiPrintable(b) && b != '\\') { // reverse slash excluded
                sb.append(Character.valueOf((char) b));
            } else
                sb.append(String.format("\\%02X", b));
        }
        return sb;
    }

    public static List<DirectionalByteArrayOutputStream> restoreState(String state) {
        Scanner scanner = new Scanner(state);
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
        ByteBuffer resultBytes = ByteBuffer.allocate(line.length() - 2); // 100% enough to store data
        HexFormat hexFormat = HexFormat.of();
        byte[] byteEncoded = new byte[2]; // tmp buffer for parsing \AB formatted
        while (bb.position() < bb.limit()) {
            if (bb.position() == 0) {
                direction = Direction.valueOf(new String(Character.toChars(bb.get())));
                bb.get(); // increase position for the following ':'
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
