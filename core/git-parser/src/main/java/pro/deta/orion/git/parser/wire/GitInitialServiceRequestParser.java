package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.pkt.GitPktLineReader;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

/**
 * JGit-free parser for the first socket-style Git service request. It consumes
 * exactly one data pkt-line from the provided ByteBuf and leaves later readable
 * bytes for the selected upload-pack or receive-pack implementation.
 */
public final class GitInitialServiceRequestParser {
    private static final long INITIAL_PACKET_INDEX = 0;

    private GitInitialServiceRequestParser() {
    }

    public static GitInitialServiceRequest read(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        int startReaderIndex = input.readerIndex();
        GitPktLineReader.Header header = GitPktLineReader.readHeader(
                input,
                INITIAL_PACKET_INDEX,
                startReaderIndex,
                "Incomplete initial service request header");
        int payloadLength = payloadLength(header);
        if (input.readableBytes() < header.packetLength()) {
            throw GitWireException.of(
                    GitWireError.Kind.INCOMPLETE_PAYLOAD,
                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                    INITIAL_PACKET_INDEX,
                    header.byteOffset() + PKT_LINE_HEADER_SIZE,
                    "Incomplete initial service request payload");
        }
        GitInitialServiceRequest request = parsePayload(
                input,
                header.headerIndex() + PKT_LINE_HEADER_SIZE,
                header.headerIndex() + PKT_LINE_HEADER_SIZE + payloadLength,
                startReaderIndex);
        input.readerIndex(header.headerIndex() + PKT_LINE_HEADER_SIZE + payloadLength);
        return request;
    }

    private static int payloadLength(GitPktLineReader.Header header) {
        int packetLength = header.packetLength();
        if (packetLength == 3) {
            throw GitWireException.of(
                    GitWireError.Kind.RESERVED_LENGTH,
                    GitWireError.Phase.CONTROL_HEADER,
                    INITIAL_PACKET_INDEX,
                    header.byteOffset(),
                    "Pkt-line length 0003 is reserved");
        }
        if (packetLength < PKT_LINE_HEADER_SIZE) {
            throw semanticError(header.byteOffset(), "Initial service request must be a data pkt-line");
        }
        if (packetLength > GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH) {
            throw GitWireException.of(
                    GitWireError.Kind.LENGTH_EXCEEDS_LIMIT,
                    GitWireError.Phase.CONTROL_HEADER,
                    INITIAL_PACKET_INDEX,
                    header.byteOffset(),
                    "Initial service request exceeds Git pkt-line limit");
        }
        return packetLength - PKT_LINE_HEADER_SIZE;
    }

    private static GitInitialServiceRequest parsePayload(
            ByteBuf input,
            int payloadStart,
            int payloadEnd,
            int startReaderIndex) {
        if (isBlank(input, payloadStart, payloadEnd)) {
            throw semanticError(payloadStart - startReaderIndex, "Malformed initial service request: empty command");
        }

        int commandFieldEnd = findByte(input, payloadStart, payloadEnd, 0);
        if (commandFieldEnd < 0) {
            commandFieldEnd = payloadEnd;
        }
        int commandStart = trimStart(input, payloadStart, commandFieldEnd);
        int commandEnd = trimEnd(input, commandStart, commandFieldEnd);
        int serviceEnd = findWhitespace(input, commandStart, commandEnd);
        if (serviceEnd < 0) {
            throw semanticError(commandStart - startReaderIndex, "Malformed initial service request");
        }
        int pathStart = trimStart(input, serviceEnd, commandEnd);
        if (pathStart >= commandEnd) {
            throw semanticError(serviceEnd - startReaderIndex, "Malformed initial service request");
        }

        String serviceName = string(input, commandStart, serviceEnd);
        String repositoryPath = string(input, pathStart, commandEnd);

        Map<String, String> parameters = new LinkedHashMap<>();
        return new GitInitialServiceRequest(
                parseService(serviceName, commandStart - startReaderIndex),
                repositoryPath,
                parseParameters(input, commandFieldEnd + 1, payloadEnd, parameters));
    }

    private static GitInitialServiceRequest.Service parseService(String serviceName, long byteOffset) {
        for (GitInitialServiceRequest.Service service : GitInitialServiceRequest.Service.values()) {
            if (service.wireName().equals(serviceName)) {
                return service;
            }
        }
        throw semanticError(byteOffset, "Unsupported Git service: " + serviceName);
    }

    private static GitWireException semanticError(long byteOffset, String message) {
        return GitWireException.of(
                GitWireError.Kind.INVALID_INITIAL_SERVICE_REQUEST,
                GitWireError.Phase.STRUCTURED_PAYLOAD,
                INITIAL_PACKET_INDEX,
                byteOffset,
                message);
    }

    private static Map<String, String> parseParameters(
            ByteBuf input,
            int parametersStart,
            int payloadEnd,
            Map<String, String> parameters) {
        int fieldStart = parametersStart;
        while (fieldStart < payloadEnd) {
            int fieldEnd = findByte(input, fieldStart, payloadEnd, 0);
            if (fieldEnd < 0) {
                fieldEnd = payloadEnd;
            }
            addParameter(parameters, input, fieldStart, fieldEnd);
            fieldStart = fieldEnd + 1;
        }
        return parameters;
    }

    private static void addParameter(Map<String, String> parameters, ByteBuf input, int fieldStart, int fieldEnd) {
        if (isBlank(input, fieldStart, fieldEnd)) {
            return;
        }
        int separator = findByte(input, fieldStart, fieldEnd, '=');
        if (separator < 0) {
            parameters.put(string(input, fieldStart, fieldEnd), "");
            return;
        }
        if (!isBlank(input, fieldStart, separator)) {
            parameters.put(string(input, fieldStart, separator), string(input, separator + 1, fieldEnd));
        }
    }

    private static int findByte(ByteBuf input, int start, int end, int value) {
        for (int index = start; index < end; index++) {
            if (input.getUnsignedByte(index) == value) {
                return index;
            }
        }
        return -1;
    }

    private static int findWhitespace(ByteBuf input, int start, int end) {
        for (int index = start; index < end; index++) {
            if (isWhitespace(input, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int trimStart(ByteBuf input, int start, int end) {
        int index = start;
        while (index < end && isWhitespace(input, index)) {
            index++;
        }
        return index;
    }

    private static int trimEnd(ByteBuf input, int start, int end) {
        int index = end;
        while (index > start && isWhitespace(input, index - 1)) {
            index--;
        }
        return index;
    }

    private static boolean isBlank(ByteBuf input, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!isWhitespace(input, index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(ByteBuf input, int index) {
        int value = input.getUnsignedByte(index);
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == 0x0b;
    }

    private static String string(ByteBuf input, int start, int end) {
        return input.toString(start, end - start, StandardCharsets.UTF_8);
    }
}
