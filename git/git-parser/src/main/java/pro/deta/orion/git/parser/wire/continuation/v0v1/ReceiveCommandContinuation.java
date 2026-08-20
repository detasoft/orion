package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.error.GitWireError;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.DUPLICATE_LEGACY_RECEIVE_REF;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.EMPTY_LEGACY_RECEIVE_CAPABILITY;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.EMPTY_LEGACY_RECEIVE_COMMAND;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_RECEIVE_COMMAND;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_RECEIVE_OBJECT_ID;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.LATE_LEGACY_RECEIVE_CAPABILITIES;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.MISSING_LEGACY_RECEIVE_COMMAND;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.UNSUPPORTED_LEGACY_RECEIVE_CONTROL;

final class ReceiveCommandContinuation implements Continuation<ByteBuf> {
    private static final String NULL_ID = "0".repeat(40);

    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final GitV1Advertisement serverAdvertisement;
    private final List<LegacyReceiveCommand> commands =
            new ArrayList<>();
    private final Set<String> capabilities = new LinkedHashSet<>();
    private final Set<String> refNames = new LinkedHashSet<>();

    ReceiveCommandContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data,
            GitV1Advertisement serverAdvertisement) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
        this.serverAdvertisement = Objects.requireNonNull(
                serverAdvertisement,
                "serverAdvertisement");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.transition(
                new ControlHeaderContinuation(this::next));
    }

    Continuation<ByteBuf> next(ControlState control) {
        return switch (control.type()) {
            case DATA -> control.payloadLength() == 0
                    ? failed(EMPTY_LEGACY_RECEIVE_COMMAND)
                    : new ReceiveCommandPayloadContinuation(
                            this,
                            control.payloadLength());
            case FLUSH -> completeCommands();
            case DELIMITER, RESPONSE_END ->
                    failed(UNSUPPORTED_LEGACY_RECEIVE_CONTROL);
        };
    }

    GitWireError.Kind acceptCommand(byte[] rawPayload) {
        int length = rawPayload.length;
        if (length > 0 && rawPayload[length - 1] == '\n') {
            length--;
        }
        if (length == 0) {
            return EMPTY_LEGACY_RECEIVE_COMMAND;
        }
        for (int index = 0; index < length; index++) {
            int value = rawPayload[index] & 0xff;
            if (value == 0) {
                continue;
            }
            if (value < 32 || value >= 127) {
                return INVALID_LEGACY_RECEIVE_COMMAND;
            }
        }

        int separator = -1;
        for (int index = 0; index < length; index++) {
            if (rawPayload[index] == 0) {
                if (separator >= 0) {
                    return INVALID_LEGACY_RECEIVE_COMMAND;
                }
                separator = index;
            }
        }
        if (!commands.isEmpty() && separator >= 0) {
            return LATE_LEGACY_RECEIVE_CAPABILITIES;
        }

        int commandLength = separator >= 0 ? separator : length;
        String commandLine = new String(
                rawPayload,
                0,
                commandLength,
                StandardCharsets.US_ASCII);
        String[] tokens = commandLine.split(" ", -1);
        if (tokens.length != 3
                || tokens[2].isEmpty()) {
            return INVALID_LEGACY_RECEIVE_COMMAND;
        }
        if (!isObjectId(tokens[0])
                || !isObjectId(tokens[1])) {
            return INVALID_LEGACY_RECEIVE_OBJECT_ID;
        }
        if (NULL_ID.equalsIgnoreCase(tokens[0])
                && NULL_ID.equalsIgnoreCase(tokens[1])) {
            return INVALID_LEGACY_RECEIVE_COMMAND;
        }
        if (!refNames.add(tokens[2])) {
            return DUPLICATE_LEGACY_RECEIVE_REF;
        }

        Set<String> parsedCapabilities = new LinkedHashSet<>();
        if (separator >= 0) {
            String capabilityLine = new String(
                    rawPayload,
                    separator + 1,
                    length - separator - 1,
                    StandardCharsets.US_ASCII);
            if (capabilityLine.isEmpty()) {
                return EMPTY_LEGACY_RECEIVE_CAPABILITY;
            }
            String[] capabilityTokens =
                    capabilityLine.split(" ", -1);
            for (String capability : capabilityTokens) {
                if (capability.isEmpty()) {
                    return EMPTY_LEGACY_RECEIVE_CAPABILITY;
                }
                parsedCapabilities.add(capability);
            }
        }

        try {
            commands.add(new LegacyReceiveCommand(
                    GitObjectId.of(tokens[0].toLowerCase()),
                    GitObjectId.of(tokens[1].toLowerCase()),
                    tokens[2]));
            capabilities.addAll(parsedCapabilities);
            return null;
        } catch (IllegalArgumentException error) {
            refNames.remove(tokens[2]);
            return INVALID_LEGACY_RECEIVE_COMMAND;
        }
    }

    private Continuation<ByteBuf> completeCommands() {
        if (commands.isEmpty()) {
            return failed(MISSING_LEGACY_RECEIVE_COMMAND);
        }
        LegacyReceiveCommandSection section =
                new LegacyReceiveCommandSection(
                        data,
                        commands,
                        capabilities,
                        serverAdvertisement);
        return new ReceivePackBoundaryContinuation(context, section);
    }

    private static boolean isObjectId(String value) {
        if (value.length() != 40) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            boolean hexadecimal = digit >= '0' && digit <= '9'
                    || digit >= 'a' && digit <= 'f'
                    || digit >= 'A' && digit <= 'F';
            if (!hexadecimal) {
                return false;
            }
        }
        return true;
    }

    static Continuation<ByteBuf> failed(GitWireError.Kind kind) {
        return Continuation.completedError(
                kind.getMessage(),
                new GitGeneralException(kind));
    }
}
