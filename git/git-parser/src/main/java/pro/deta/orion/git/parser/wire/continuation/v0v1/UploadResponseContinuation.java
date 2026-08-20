package pro.deta.orion.git.parser.wire.continuation.v0v1;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.GitMinimalWireMachine;
import pro.deta.orion.git.parser.wire.GitNativeClientOutput;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.continuation.exchange.LegacyUploadNegotiation;

import java.util.Objects;

final class UploadResponseContinuation implements Continuation<ByteBuf> {
    private final GitMinimalWireMachine.Context context;
    private final LegacyUploadNegotiation negotiation;
    private final NativeFetchRequest fetchRequest;
    private GitNativeClientOutput.LegacySideBandResponse response;
    private GitNativeClientOutput.LegacyPackResponse packResponse;

    UploadResponseContinuation(
            GitMinimalWireMachine.Context context,
            LegacyUploadNegotiation negotiation) {
        this.context = Objects.requireNonNull(context, "context");
        this.negotiation = Objects.requireNonNull(
                negotiation,
                "negotiation");
        this.fetchRequest = negotiation.nativeFetchRequest();
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            GitNativeClientOutput.SendResult result;
            if (negotiation.negotiated(GitCapability.SIDE_BAND_64K)) {
                if (response == null) {
                    response = context.clientOutput.beginLegacySideBand64k(
                            producer(),
                            GitNativeClientOutput.SideBandChannel.DATA);
                }
                result = response.advance();
            } else {
                if (packResponse == null) {
                    packResponse = context.clientOutput.beginLegacyPack(
                            producer());
                }
                result = packResponse.advance();
            }
            return switch (result) {
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
                    "Failed to write legacy upload-pack response",
                    error);
        }
    }

    private NativePackProducer producer() {
        return context.repositoryService.legacyUploadPack(
                negotiation.request().initialRequest(),
                fetchRequest);
    }

    @Override
    public void close() {
        if (response != null) {
            response.close();
            response = null;
        }
        if (packResponse != null) {
            packResponse.close();
            packResponse = null;
        }
    }
}
