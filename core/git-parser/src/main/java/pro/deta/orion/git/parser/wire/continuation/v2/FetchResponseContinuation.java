package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUri;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class FetchResponseContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final InitialRequestData data;
    private final NativeFetchRequest request;
    private final boolean sidebandAll;
    private GitNativeClientOutput.ProtocolV2PackfileResponse response;

    FetchResponseContinuation(
            GitMinimalWireMachine.Context context,
            InitialRequestData data,
            NativeFetchRequest request,
            boolean sidebandAll) {
        this.context = Objects.requireNonNull(context, "context");
        this.data = Objects.requireNonNull(data, "data");
        this.request = Objects.requireNonNull(request, "request");
        this.sidebandAll = sidebandAll;
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            if (response == null) {
                NativeFetchResponse fetch =
                        context.repositoryService.protocolV2Fetch(
                                data,
                                request);
                response = context.clientOutput.beginProtocolV2Packfile(
                        fetch.packProducer(),
                        fetch.shallowBoundaries(),
                        fetch.wantedRefs(),
                        packfileUrisForClient(fetch),
                        sidebandAll);
            }
            return switch (response.advance()) {
                case GitNativeClientOutput.SendResult.Completed ignored -> {
                    close();
                    yield ContinuationFlow.transition(
                            Continuation.completedSuccess(this));
                }
                case GitNativeClientOutput.SendResult.Streaming streaming ->
                        ContinuationFlow.yield(streaming.task());
                case GitNativeClientOutput.SendResult.Failed failed -> {
                    close();
                    yield ContinuationFlow.completedError(
                            failed.message(),
                            failed.cause());
                }
            };
        } catch (RuntimeException error) {
            close();
            return ContinuationFlow.completedError(
                    "Failed to write protocol v2 fetch response",
                    error);
        }
    }

    @Override
    public void close() {
        if (response != null) {
            response.close();
            response = null;
        }
    }

    @TestOnly
    NativeFetchRequest request() {
        return request;
    }

    @TestOnly
    boolean sidebandAll() {
        return sidebandAll;
    }

    private List<NativePackfileUri> packfileUrisForClient(
            NativeFetchResponse fetch) {
        if (request.packfileUriProtocols().isEmpty()) {
            return List.of();
        }
        List<NativePackfileUri> allowed = new ArrayList<>();
        for (NativePackfileUri packfileUri : fetch.packfileUris()) {
            if (request.packfileUriProtocols()
                    .contains(packfileUri.protocol())) {
                allowed.add(packfileUri);
            }
        }
        return List.copyOf(allowed);
    }
}
