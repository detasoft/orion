package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.ControlPacketHandler;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadRequest;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.error.GitWireError;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.EMPTY_LEGACY_UPLOAD_CAPABILITY;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.EMPTY_LEGACY_UPLOAD_PACKET;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_LEGACY_UPLOAD_OBJECT_ID;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.LATE_LEGACY_UPLOAD_CAPABILITIES;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.MISSING_LEGACY_UPLOAD_WANT;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_COMMAND;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_CONTROL;

final class UploadRequestContinuation
        implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final GitV1Advertisement serverAdvertisement;
    private final Set<GitObjectId> wants = new LinkedHashSet<>();
    private final Set<String> capabilities = new LinkedHashSet<>();

    UploadRequestContinuation(
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
            case DATA -> control.payloadLength() == 0 ? failed(EMPTY_LEGACY_UPLOAD_PACKET)
                    : new UploadWantPayloadContinuation(this, control.payloadLength());
            case FLUSH -> completeRequest();
            case DELIMITER, RESPONSE_END ->
                    failed(UNSUPPORTED_LEGACY_UPLOAD_CONTROL);
        };
    }

    GitWireError.Kind acceptWant(byte[] rawPayload) {
        int length = rawPayload.length;
        if (length > 0 && rawPayload[length - 1] == '\n') {
            length--;
        }
        String line = new String(
                rawPayload,
                0,
                length,
                StandardCharsets.US_ASCII);
        if (!line.startsWith("want ")) {
            return UNSUPPORTED_LEGACY_UPLOAD_COMMAND;
        }

        String arguments = line.substring("want ".length());
        String[] tokens = arguments.split(" ", -1);
        if (tokens.length == 0 || !isObjectId(tokens[0])) {
            return INVALID_LEGACY_UPLOAD_OBJECT_ID;
        }
        boolean firstWant = wants.isEmpty();
        if (!firstWant && tokens.length > 1) {
            return LATE_LEGACY_UPLOAD_CAPABILITIES;
        }

        if (firstWant) {
            for (int index = 1; index < tokens.length; index++) {
                if (tokens[index].isEmpty()) {
                    return EMPTY_LEGACY_UPLOAD_CAPABILITY;
                }
            }
        }
        wants.add(GitObjectId.of(tokens[0]));
        if (firstWant) {
            for (int index = 1; index < tokens.length; index++) {
                capabilities.add(tokens[index]);
            }
        }
        return null;
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

    private Continuation<ByteBuf> completeRequest() {
        if (wants.isEmpty()) {
            return failed(MISSING_LEGACY_UPLOAD_WANT);
        }
        LegacyUploadRequest request = new LegacyUploadRequest(
                data,
                wants,
                capabilities,
                serverAdvertisement);
        return new UploadNegotiationContinuation(context, request);
    }

    static Continuation<ByteBuf> failed(GitWireError.Kind kind) {
        return Continuation.completedError(
                kind.getMessage(),
                new GitGeneralException(kind));
    }
}
