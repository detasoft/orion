package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Processes decoded negotiation messages without owning storage or byte streams. NegotiationContext supplies
 * object-presence and graph-readiness checks using its borrowed storage. Each next(message) replaces the reply
 * batch; true asks for another message, false ends this exchange. The terminal batch must still be sent. Reply snapshots are
 * immutable and non-draining. Calls after completion or a failed check throw IllegalStateException.
 *
 * <p>Legacy SINGLE_ACK acknowledges only the first common object; multi-ACK modes acknowledge individual
 * haves and finish rounds with NAK. Readiness replies for unknown haves never make those IDs common.
 * Detailed readiness does not replace DONE unless stateless HTTP negotiated no-done. Stateless legacy ends
 * at END_ROUND even when no pack can yet be sent; stateful legacy retains common objects across rounds.
 *
 * <p>V2 buffers acknowledgments until END_ROUND, then ends the request. DONE suppresses that entire section
 * but does not skip the request's remaining messages. wait-for-done suppresses early readiness checks and
 * READY. Context ready and doneReceived remain separate facts, not proof of successful pack production.
 * A context can be returned with both false. Parsed request population must finish before iteration starts.
 */
public final class FetchNegotiatorIterator {
    private final NegotiationContext context;
    private final boolean stateless;
    private final List<NegotiationResponse> responsesToSend = new ArrayList<>();
    private boolean finished;

    public FetchNegotiatorIterator(NegotiationContext context, boolean stateless) {
        this.context = Objects.requireNonNull(context, "context");
        this.stateless = stateless;
        if (context.hasRequest(GitCapability.NO_DONE)
                && (!stateless || context.request().mode() != FetchRequest.Mode.MULTI_ACK_DETAILED)) {
            throw new IllegalArgumentException("no-done requires stateless multi_ack_detailed negotiation");
        }
    }

    public boolean next(NegotiationMessage message) throws IOException {
        if (finished) {
            throw new IllegalStateException("Negotiation exchange has finished");
        }
        Objects.requireNonNull(message, "message");
        responsesToSend.clear();
        try {
            switch (message) {
                case NegotiationMessage.Have have -> acceptHave(have.objectId());
                case NegotiationMessage.Control.DONE -> acceptDone();
                case NegotiationMessage.Control.END_ROUND -> endRound();
            }
        } catch (IOException | RuntimeException failure) {
            responsesToSend.clear();
            finished = true;
            throw failure;
        }
        return !finished;
    }

    private void acceptHave(ObjectId objectId) throws IOException {
        FetchRequest.Mode mode = context.request().mode();
        boolean firstCommon = context.lastCommon().isEmpty();
        if (context.hasCommon(objectId) || context.objectExists(objectId)) {
            context.addCommon(objectId);
            switch (mode) {
                case SINGLE_ACK -> {
                    if (firstCommon) {
                        ack(objectId, NegotiationResponse.Status.PLAIN);
                    }
                }
                case MULTI_ACK -> ack(objectId, NegotiationResponse.Status.CONTINUE);
                case MULTI_ACK_DETAILED -> ack(objectId, NegotiationResponse.Status.COMMON);
                case PROTOCOL_V2 -> { }
            }
        } else if ((mode == FetchRequest.Mode.MULTI_ACK || mode == FetchRequest.Mode.MULTI_ACK_DETAILED)
                && checkReady()) {
            ack(objectId, mode == FetchRequest.Mode.MULTI_ACK
                    ? NegotiationResponse.Status.CONTINUE : NegotiationResponse.Status.READY);
        }
    }

    private void acceptDone() throws IOException {
        if (context.doneReceived()) {
            throw new IOException("Duplicate negotiation done");
        }
        context.markDone();
        if (context.request().mode() == FetchRequest.Mode.PROTOCOL_V2) {
            return;
        }
        if (context.lastCommon().isEmpty()) {
            responsesToSend.add(NegotiationResponse.Control.NAK);
        } else if (context.request().mode() != FetchRequest.Mode.SINGLE_ACK) {
            ack(context.lastCommon().orElseThrow(), NegotiationResponse.Status.PLAIN);
        }
        finished = true;
    }

    private void endRound() throws IOException {
        FetchRequest.Mode mode = context.request().mode();
        if (mode == FetchRequest.Mode.PROTOCOL_V2) {
            if (!context.doneReceived()) {
                if (context.lastCommon().isEmpty()) {
                    responsesToSend.add(NegotiationResponse.Control.NAK);
                } else {
                    for (ObjectId common : context.commonObjects()) {
                        ack(common, NegotiationResponse.Status.PLAIN);
                    }
                    if (!context.hasRequest(GitCapability.WAIT_FOR_DONE) && checkReady()) {
                        responsesToSend.add(NegotiationResponse.Control.READY);
                    }
                }
            }
            finished = true;
            return;
        }
        if (mode == FetchRequest.Mode.MULTI_ACK_DETAILED && checkReady()) {
            ack(context.lastCommon().orElseThrow(), NegotiationResponse.Status.READY);
        }
        if (mode != FetchRequest.Mode.SINGLE_ACK || context.lastCommon().isEmpty()) {
            responsesToSend.add(NegotiationResponse.Control.NAK);
        }
        if (context.hasRequest(GitCapability.NO_DONE) && context.ready()) {
            ack(context.lastCommon().orElseThrow(), NegotiationResponse.Status.PLAIN);
        }
        finished = stateless;
    }

    private boolean checkReady() throws IOException {
        if (!context.ready() && context.lastCommon().isPresent() && context.isReady()) {
            context.markReady();
        }
        return context.ready();
    }

    private void ack(ObjectId objectId, NegotiationResponse.Status status) {
        responsesToSend.add(new NegotiationResponse.Ack(objectId, status));
    }

    public NegotiationContext getContext() {
        return context;
    }

    public List<NegotiationResponse> getResponsesToSend() {
        return List.copyOf(responsesToSend);
    }
}
