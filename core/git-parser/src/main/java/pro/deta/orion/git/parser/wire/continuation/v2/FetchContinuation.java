package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_PROTOCOL_V2_FETCH_REQUEST;

final class FetchContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final Set<GitObjectId> wants = new LinkedHashSet<>();
    private final Set<GitObjectId> haves = new LinkedHashSet<>();
    private boolean done;
    private boolean thinPack;
    private boolean ofsDelta;
    private boolean includeTag;

    FetchContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        return ContinuationFlow.transition(
                new ControlHeaderContinuation(this::next));
    }

    Continuation<ByteBuf> next(ControlState control) {
        return switch (control.type()) {
            case DATA -> control.payloadLength() == 0 || done
                    ? failed()
                    : new FetchPayloadContinuation(
                            this,
                            control.payloadLength());
            case FLUSH -> completeRequest();
            case DELIMITER, RESPONSE_END -> failed();
        };
    }

    boolean accept(byte[] rawPayload) {
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
            done = true;
            return true;
        }
        if (line.equals(GitCapability.THIN_PACK.name())) {
            thinPack = true;
            return true;
        }
        if (line.equals(GitCapability.OFS_DELTA.name())) {
            ofsDelta = true;
            return true;
        }
        if (line.equals(GitCapability.NO_PROGRESS.name())) {
            return true;
        }
        if (line.equals(GitCapability.INCLUDE_TAG.name())) {
            includeTag = true;
            return true;
        }
        if (line.startsWith("want ")) {
            return addObjectId(wants, line.substring("want ".length()));
        }
        if (line.startsWith("have ")) {
            return addObjectId(haves, line.substring("have ".length()));
        }
        return false;
    }

    private Continuation<ByteBuf> completeRequest() {
        if (wants.isEmpty() || !done) {
            return failed();
        }
        NativeFetchRequest request = new NativeFetchRequest(
                wants,
                haves,
                done,
                thinPack,
                ofsDelta,
                includeTag);
        return new FetchResponseContinuation(
                context,
                data,
                request);
    }

    private static boolean addObjectId(
            Set<GitObjectId> destination,
            String value) {
        if (!isObjectId(value)) {
            return false;
        }
        destination.add(GitObjectId.of(value));
        return true;
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

    static Continuation<ByteBuf> failed() {
        return Continuation.completedError(
                INVALID_PROTOCOL_V2_FETCH_REQUEST.getMessage(),
                new GitGeneralException(
                        INVALID_PROTOCOL_V2_FETCH_REQUEST));
    }
}
