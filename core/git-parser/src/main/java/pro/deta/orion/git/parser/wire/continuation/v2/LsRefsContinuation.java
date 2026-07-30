package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.LsRefsRequest;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST;

final class LsRefsContinuation implements Continuation<ByteBuf> {
    static final int MAX_REF_PREFIX_COUNT = 256;
    static final int MAX_REF_PREFIX_CHARS = 65_536;

    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final List<String> refPrefixes = new ArrayList<>();
    private int refPrefixChars;
    private boolean peel;
    private boolean symrefs;
    private boolean unborn;

    LsRefsContinuation(
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
            case DATA -> control.payloadLength() == 0
                    ? failed()
                    : new LsRefsArgumentPayloadContinuation(
                            this,
                            control.payloadLength());
            case FLUSH -> completeRequest();
            case DELIMITER, RESPONSE_END -> failed();
        };
    }

    boolean accept(Argument argument) {
        return switch (argument) {
            case SimpleArgument simple -> {
                switch (simple) {
                    case PEEL -> peel = true;
                    case SYMREFS -> symrefs = true;
                    case UNBORN -> unborn = true;
                }
                yield true;
            }
            case RefPrefix prefix -> {
                int prefixChars = prefix.value().length();
                if (refPrefixes.size() >= MAX_REF_PREFIX_COUNT
                        || prefixChars
                        > MAX_REF_PREFIX_CHARS - refPrefixChars) {
                    yield false;
                }
                refPrefixes.add(prefix.value());
                refPrefixChars += prefixChars;
                yield true;
            }
            case Unknown ignored -> true;
        };
    }

    private Continuation<ByteBuf> completeRequest() {
        try {
            LsRefsRequest request = new LsRefsRequest(
                    peel,
                    symrefs,
                    unborn,
                    refPrefixes);
            GitNativeClientOutput.SendResult result =
                    context.clientOutput.sendLsRefs(
                            context.repositoryService.lsRefs(data, request));
            return input -> result.transitionTo(
                    new UploadCommandContinuation(context, data));
        } catch (RuntimeException error) {
            return Continuation.completedError(
                    "Failed to serve protocol v2 ls-refs",
                    error);
        }
    }

    static Continuation<ByteBuf> failed() {
        return Continuation.completedError(
                INVALID_PROTOCOL_V2_REQUEST.getMessage(),
                new GitGeneralException(INVALID_PROTOCOL_V2_REQUEST));
    }

    sealed interface Argument
            permits SimpleArgument, RefPrefix, Unknown {
    }

    enum SimpleArgument implements Argument {
        PEEL,
        SYMREFS,
        UNBORN
    }

    record RefPrefix(String value) implements Argument {
        RefPrefix {
            Objects.requireNonNull(value, "value");
        }
    }

    enum Unknown implements Argument {
        VALUE
    }

}
