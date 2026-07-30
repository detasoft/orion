package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;

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

    void accept(FetchArgument argument) {
        switch (argument) {
            case ObjectArgument object -> {
                Set<GitObjectId> destination =
                        object.kind() == ObjectArgumentKind.WANT
                                ? wants
                                : haves;
                destination.add(object.objectId());
            }
            case SimpleArgument simple -> {
                switch (simple) {
                    case DONE -> done = true;
                    case THIN_PACK -> thinPack = true;
                    case OFS_DELTA -> ofsDelta = true;
                    case INCLUDE_TAG -> includeTag = true;
                    case NO_PROGRESS -> {
                    }
                }
            }
        }
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

    sealed interface FetchArgument
            permits ObjectArgument, SimpleArgument {
    }

    record ObjectArgument(
            ObjectArgumentKind kind,
            GitObjectId objectId)
            implements FetchArgument {
    }

    enum ObjectArgumentKind {
        WANT,
        HAVE
    }

    enum SimpleArgument implements FetchArgument {
        DONE,
        THIN_PACK,
        OFS_DELTA,
        NO_PROGRESS,
        INCLUDE_TAG
    }

    static Continuation<ByteBuf> failed() {
        return Continuation.completedError(
                INVALID_PROTOCOL_V2_FETCH_REQUEST.getMessage(),
                new GitGeneralException(
                        INVALID_PROTOCOL_V2_FETCH_REQUEST));
    }
}
