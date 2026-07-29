package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InitialRequestPayloadContinuation implements Continuation<ByteBuf> {
    private static final int INITIAL_VALUE_CAPACITY = 64;
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestParser requestParser;

    public InitialRequestPayloadContinuation(
            GitMinimalWireMachine.Context context,
            int payloadLength) {
        this.context = context;
        this.requestParser = new InitialRequestParser(payloadLength);
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            while (requestParser.notDone()) {
                if (input.isReadable()) {
                    requestParser.nextByte(input);
                } else {
                    return ContinuationFlow.await();
                }
            }
            InitialRequestData data = requestParser.completeRequest();
            return ContinuationFlow.transition(new StructuredPayloadContinuation(
                    context,
                    data));
        } catch (Throwable error) {
            return ContinuationFlow.transition(Continuation.completedError(
                    "Failed to read initial Git service request",
                    error));
        }
    }

    private static class InitialRequestParser {
        private final Map<String, String> parameters = new LinkedHashMap<>();
        private int remainingBytes;

        private byte[] valueBuffer = new byte[INITIAL_VALUE_CAPACITY];
        private int valueLength;
        private Phase phase = Phase.SERVICE;
        private InitialRequestService service;
        private String repositoryPath;
        private String host;
        private String parameterName;

        private InitialRequestParser(int payloadLength) {
            this.remainingBytes = payloadLength;
        }
        private void nextByte(ByteBuf bb) {
            int value = bb.readUnsignedByte();
            remainingBytes--;
            switch (phase) {
                case SERVICE -> {
                    if (value == ' ') {
                        completeService();
                    } else {
                        requireNonNul(value, "service");
                        append(value);
                    }
                }
                case REPOSITORY_PATH -> {
                    if (value == 0) {
                        completeRepositoryPath();
                    } else {
                        append(value);
                    }
                }
                case HOST -> {
                    if (value == 0) {
                        if (valueLength == 0) {
                            phase = Phase.PARAMETER_NAME;
                        } else {
                            completeHost();
                        }
                    } else {
                        append(value);
                    }
                }
                case EXTRA_SEPARATOR -> {
                    if (value != 0) {
                        throw malformed("Expected NUL before extra parameters");
                    }
                    phase = Phase.PARAMETER_NAME;
                }
                case PARAMETER_NAME -> {
                    if (value == '=') {
                        completeParameterName();
                    } else if (value == 0) {
                        completeFlagParameter();
                    } else {
                        append(value);
                    }
                }
                case PARAMETER_VALUE -> {
                    if (value == 0) {
                        completeParameterValue();
                    } else {
                        append(value);
                    }
                }
            }
        }

        private void completeService() {
            String service = takeValue();
            if (service.isEmpty()) {
                throw malformed("Git service is empty");
            }
            this.service = InitialRequestService.fromWireName(service);
            phase = Phase.REPOSITORY_PATH;
        }

        private void completeRepositoryPath() {
            String repositoryPath = takeValue();
            if (repositoryPath.isEmpty()) {
                throw malformed("Git repository path is empty");
            }
            this.repositoryPath = repositoryPath;
            phase = Phase.HOST;
        }

        private void completeHost() {
            String hostParameter = takeValue();
            if (!hostParameter.startsWith("host=")) {
                throw malformed("Expected host parameter");
            }
            String host = hostParameter.substring("host=".length());
            if (host.isEmpty()) {
                throw malformed("Git host is empty");
            }
            this.host = host;
            phase = Phase.EXTRA_SEPARATOR;
        }

        private void completeParameterName() {
            parameterName = takeValue();
            if (parameterName.isEmpty()) {
                throw malformed("Git extra parameter name is empty");
            }
            phase = Phase.PARAMETER_VALUE;
        }

        private void completeFlagParameter() {
            if (valueLength == 0) {
                return;
            }
            parameters.put(takeValue(), "");
        }

        private void completeParameterValue() {
            parameters.put(parameterName, takeValue());
            parameterName = null;
            phase = Phase.PARAMETER_NAME;
        }

        private InitialRequestData completeRequest() {
            if (service == null || repositoryPath == null) {
                throw malformed("Incomplete initial Git service request");
            }
            if (phase == Phase.SERVICE
                    || phase == Phase.REPOSITORY_PATH
                    || phase == Phase.PARAMETER_VALUE
                    || valueLength > 0) {
                throw malformed("Initial Git service request ends inside a field");
            }
            return new InitialRequestData(
                    service,
                    repositoryPath,
                    host,
                    parameters);
        }

        private void append(int value) {
            if (valueLength == valueBuffer.length) {
                valueBuffer = Arrays.copyOf(valueBuffer, valueBuffer.length << 1);
            }
            valueBuffer[valueLength++] = (byte) value;
        }

        private String takeValue() {
            String value = new String(
                    valueBuffer,
                    0,
                    valueLength,
                    StandardCharsets.UTF_8);
            valueLength = 0;
            return value;
        }

        public boolean notDone() {
            return remainingBytes > 0;
        }
    }


    private static void requireNonNul(int value, String name) {
        if (value == 0) {
            throw malformed("Unexpected NUL in Git " + name);
        }
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message);
    }

    private enum Phase {
        SERVICE,
        REPOSITORY_PATH,
        HOST,
        EXTRA_SEPARATOR,
        PARAMETER_NAME,
        PARAMETER_VALUE
    }
}
