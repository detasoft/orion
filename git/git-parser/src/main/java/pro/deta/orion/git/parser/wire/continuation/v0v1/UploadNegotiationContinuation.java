package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadNegotiation;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadRequest;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.git.parser.wire.error.GitWireError;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

final class UploadNegotiationContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final LegacyUploadRequest request;
    private final Set<GitObjectId> haves = new LinkedHashSet<>();

    UploadNegotiationContinuation(
            GitMinimalWireMachine.Context context,
            LegacyUploadRequest request) {
        this.context = Objects.requireNonNull(context, "context");
        this.request = Objects.requireNonNull(request, "request");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.transition(
                new ControlHeaderContinuation(this::next));
    }

    Continuation<ByteBuf> next(ControlState control) {
        return switch (control.type()) {
            case DATA -> control.payloadLength() == 0
                    ? failed(GitWireError.Kind.EMPTY_LEGACY_UPLOAD_NEGOTIATION_PACKET)
                    : new UploadNegotiationPayloadContinuation(
                            this,
                            control.payloadLength());
            case FLUSH -> new UploadNegotiationResponseContinuation(
                    context,
                    this);
            case DELIMITER, RESPONSE_END -> failed(
                    GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_CONTROL);
        };
    }

    Continuation<ByteBuf> accept(byte[] rawPayload) {
        int length = rawPayload.length;
        if (length > 0 && rawPayload[length - 1] == '\n') {
            length--;
        }
        String line = new String(
                rawPayload,
                0,
                length,
                StandardCharsets.US_ASCII);
        if (line.equals("done")) {
            return responseBoundary();
        }
        if (!line.startsWith("have ")) {
            return failed(
                    GitWireError.Kind.UNSUPPORTED_LEGACY_UPLOAD_NEGOTIATION_COMMAND);
        }

        String objectId = line.substring("have ".length());
        if (!isObjectId(objectId)) {
            return failed(
                    GitWireError.Kind.INVALID_LEGACY_UPLOAD_HAVE_OBJECT_ID);
        }
        haves.add(GitObjectId.of(objectId));
        return new ControlHeaderContinuation(this::next);
    }

    @TestOnly
    LegacyUploadRequest request() {
        return request;
    }

    UploadResponseContinuation responseBoundary() {
        return new UploadResponseContinuation(
                context,
                new LegacyUploadNegotiation(request, haves));
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
