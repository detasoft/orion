package pro.deta.orion.git.parser.wire.continuation;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.lifecycle.state.TestOnly;

public final class InitialRequestDispatchContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;

    public InitialRequestDispatchContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data) {
        this.context = context;
        this.data = data;
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            return switch (data.getService()) {
                case UPLOAD_PACK -> dispatchUploadPack();
                case RECEIVE_PACK -> dispatchReceivePack();
            };
        } catch (IllegalArgumentException error) {
            return unsupportedVersion(error, data.getService());
        }
    }

    @TestOnly
    InitialRequestData initialRequestData() {
        return data;
    }

    private ContinuationFlow<ByteBuf> dispatchUploadPack() {
        InitialRequestData.ProtocolVersion version =
                data.getProtocolVersion().orElse(null);
        if (version == null || version == InitialRequestData.ProtocolVersion.V1) {
            return ContinuationFlow.transition(
                    new pro.deta.orion.git.parser.wire.continuation.v0v1
                            .UploadPackContinuation(context, data));
        }
        if (version == InitialRequestData.ProtocolVersion.V2) {
            return ContinuationFlow.transition(
                    new pro.deta.orion.git.parser.wire.continuation.v2
                            .UploadPackContinuation(context, data));
        }
        throw new IllegalStateException("Unhandled Git protocol version: " + version);
    }

    private ContinuationFlow<ByteBuf> dispatchReceivePack() {
        InitialRequestData.ProtocolVersion version =
                data.getProtocolVersion().orElse(null);
        if (version == null || version == InitialRequestData.ProtocolVersion.V1) {
            return ContinuationFlow.transition(
                    new pro.deta.orion.git.parser.wire.continuation.v0v1
                            .ReceivePackContinuation(context, data));
        }
        return unsupportedVersion(
                new IllegalArgumentException(
                        "Unsupported Git protocol version '"
                                + version.wireValue()
                                + "'"),
                InitialRequestService.RECEIVE_PACK);
    }

    private static ContinuationFlow<ByteBuf> unsupportedVersion(
            IllegalArgumentException error,
            InitialRequestService service) {
        return ContinuationFlow.transition(Continuation.completedError(
                "Unsupported Git protocol version",
                new IllegalArgumentException(
                        error.getMessage()
                                + " for "
                                + service,
                        error)));
    }
}
