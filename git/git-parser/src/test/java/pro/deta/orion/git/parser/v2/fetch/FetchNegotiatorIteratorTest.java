package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.data.GitTransport;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.capability.GitCapability;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.data.GitTransport.HTTP;
import static pro.deta.orion.git.parser.v2.data.GitTransport.SSH;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationMessage.Control.DONE;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationMessage.Control.END_ROUND;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Control.NAK;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Control.READY;
import static pro.deta.orion.git.parser.v2.fetch.NegotiationResponse.Status.*;

class FetchNegotiatorIteratorTest {
    private static final ObjectId FIRST = new ObjectId("1".repeat(40));
    private static final ObjectId SECOND = new ObjectId("2".repeat(40));
    private static final ObjectId UNKNOWN = new ObjectId("3".repeat(40));
    private static final ObjectId WANT = new ObjectId("4".repeat(40));

    @Test
    void singleAckAcknowledgesOnlyTheFirstCommonObjectAndWaitsForDone() throws Exception {
        var checks = new TestContext(Set.of(FIRST, SECOND));
        var context = checks;
        var iterator = new FetchNegotiatorIterator(context, SSH);
        assertThat(iterator.getContext()).isSameAs(context);
        assertThat(iterator.next(have(UNKNOWN))).isTrue();
        assertThat(iterator.next(END_ROUND)).isTrue();
        assertThat(iterator.getResponsesToSend()).containsExactly(NAK);
        assertThat(iterator.next(have(FIRST))).isTrue();
        assertThat(iterator.getResponsesToSend()).containsExactly(ack(FIRST, PLAIN));
        assertThat(iterator.next(have(SECOND))).isTrue();
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(iterator.next(END_ROUND)).isTrue();
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(iterator.next(DONE)).isFalse();
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(iterator.getContext().doneReceived()).isTrue();
        assertThat(checks.readinessCalls).isZero();
    }

    @Test
    void legacyCloneWithoutCommonObjectsEndsWithNakInEveryAckMode() throws Exception {
        for (FetchRequest.Mode mode : List.of(FetchRequest.Mode.SINGLE_ACK,
                FetchRequest.Mode.MULTI_ACK, FetchRequest.Mode.MULTI_ACK_DETAILED)) {
            var iterator = iterator(mode, new TestContext(Set.of()), SSH);
            assertThat(iterator.next(DONE)).isFalse();
            assertThat(iterator.getResponsesToSend()).containsExactly(NAK);
            assertThat(iterator.getContext().ready()).isFalse();
            assertThatThrownBy(() -> iterator.next(END_ROUND)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void multiAckKeepsCommonObjectsAcrossRoundsAndFinalAckUsesLastConfirmedHave() throws Exception {
        for (FetchRequest.Mode mode : List.of(FetchRequest.Mode.MULTI_ACK, FetchRequest.Mode.MULTI_ACK_DETAILED)) {
            var iterator = iterator(mode, new TestContext(Set.of(FIRST, SECOND)), SSH);
            var suffix = mode == FetchRequest.Mode.MULTI_ACK ? CONTINUE : COMMON;
            iterator.next(have(FIRST));
            assertThat(iterator.getResponsesToSend()).containsExactly(ack(FIRST, suffix));
            assertThat(iterator.next(END_ROUND)).isTrue();
            assertThat(iterator.getResponsesToSend()).containsExactly(NAK);
            iterator.next(have(SECOND));
            iterator.next(have(FIRST));
            iterator.next(have(UNKNOWN));
            assertThat(iterator.getResponsesToSend()).isEmpty();
            assertThat(iterator.getContext().commonObjects()).containsExactly(FIRST, SECOND);
            assertThat(iterator.next(DONE)).isFalse();
            assertThat(iterator.getResponsesToSend()).containsExactly(ack(FIRST, PLAIN));
        }
    }

    @Test
    void readinessSignalsForUnknownHavesNeverMakeThemCommon() throws Exception {
        for (FetchRequest.Mode mode : List.of(FetchRequest.Mode.MULTI_ACK, FetchRequest.Mode.MULTI_ACK_DETAILED)) {
            var checks = new TestContext(Set.of(FIRST));
            var iterator = iterator(mode, checks, SSH);
            iterator.next(have(FIRST));
            checks.ready = true;
            iterator.next(have(UNKNOWN));
            var status = mode == FetchRequest.Mode.MULTI_ACK ? CONTINUE : NegotiationResponse.Status.READY;
            assertThat(iterator.getResponsesToSend()).containsExactly(ack(UNKNOWN, status));
            assertThat(iterator.getContext().commonObjects()).containsExactly(FIRST);
            assertThat(iterator.getContext().lastCommon()).contains(FIRST);
            assertThat(iterator.getContext().ready()).isTrue();
            assertThat(iterator.next(DONE)).isFalse();
            assertThat(iterator.getResponsesToSend()).containsExactly(ack(FIRST, PLAIN));
        }
    }

    @Test
    void detailedAckAtRoundEndSignalsReadyButStillWaitsForDone() throws Exception {
        var checks = new TestContext(Set.of(FIRST));
        checks.ready = true;
        var iterator = iterator(FetchRequest.Mode.MULTI_ACK_DETAILED, checks, SSH);
        iterator.next(have(FIRST));
        assertThat(iterator.next(END_ROUND)).isTrue();
        assertThat(iterator.getResponsesToSend()).containsExactly(ack(FIRST, NegotiationResponse.Status.READY), NAK);
        assertThat(iterator.getContext().ready()).isTrue();
        assertThat(iterator.getContext().doneReceived()).isFalse();
    }

    @Test
    void noDoneFinishesHttpDetailedNegotiationWithItsTerminalAck() throws Exception {
        var request = request(FetchRequest.Mode.MULTI_ACK_DETAILED, GitCapability.NO_DONE);
        var checks = new TestContext(request, Set.of(FIRST));
        checks.ready = true;
        var iterator = new FetchNegotiatorIterator(checks, HTTP);
        iterator.next(have(FIRST));
        assertThat(iterator.next(END_ROUND)).isFalse();
        assertThat(iterator.getResponsesToSend())
                .containsExactly(ack(FIRST, NegotiationResponse.Status.READY), NAK, ack(FIRST, PLAIN));
        assertThat(iterator.getContext().doneReceived()).isFalse();
        assertThat(iterator.getContext().ready()).isTrue();
    }

    @Test
    void httpRoundCanFinishWithoutReadinessOrDone() throws Exception {
        var iterator = iterator(FetchRequest.Mode.MULTI_ACK_DETAILED, new TestContext(Set.of(FIRST)), HTTP);
        iterator.next(have(FIRST));
        assertThat(iterator.next(END_ROUND)).isFalse();
        assertThat(iterator.getResponsesToSend()).containsExactly(NAK);
        assertThat(iterator.getContext().ready()).isFalse();
        assertThat(iterator.getContext().doneReceived()).isFalse();
    }

    @Test
    void v2BatchesUniqueCommonAcksInClaimOrderAndExcludesUnknownObjects() throws Exception {
        var checks = new TestContext(Set.of(FIRST, SECOND));
        var iterator = iterator(FetchRequest.Mode.PROTOCOL_V2, checks, HTTP);
        for (ObjectId id : List.of(SECOND, UNKNOWN, FIRST, SECOND)) {
            assertThat(iterator.next(have(id))).isTrue();
            assertThat(iterator.getResponsesToSend()).isEmpty();
        }
        assertThat(iterator.next(END_ROUND)).isFalse();
        assertThat(iterator.getResponsesToSend()).containsExactly(ack(SECOND, PLAIN), ack(FIRST, PLAIN));
        assertThat(checks).isSameAs(iterator.getContext());
        assertThat(checks.request().wants()).containsExactly(WANT);
        assertThat(checks.request().shallowCommits()).containsExactly(FIRST);
    }

    @Test
    void v2ProducesNakWithoutCommonObjectsAndDoesNotGuessReadiness() throws Exception {
        var checks = new TestContext(Set.of());
        checks.ready = true;
        var iterator = iterator(FetchRequest.Mode.PROTOCOL_V2, checks, HTTP);
        iterator.next(have(UNKNOWN));
        assertThat(iterator.next(END_ROUND)).isFalse();
        assertThat(iterator.getResponsesToSend()).containsExactly(NAK);
        assertThat(checks.readinessCalls).isZero();
    }

    @Test
    void v2ReadyIsSuppressedByWaitForDone() throws Exception {
        for (boolean wait : List.of(false, true)) {
            var request = request(FetchRequest.Mode.PROTOCOL_V2);
            var checks = new TestContext(request, Set.of(FIRST));
            checks.ready = true;
            if (wait) {
                request.capabilities().add(value(GitCapability.WAIT_FOR_DONE));
            }
            var iterator = new FetchNegotiatorIterator(checks, HTTP);
            iterator.next(have(FIRST));
            assertThat(iterator.next(END_ROUND)).isFalse();
            assertThat(iterator.getResponsesToSend()).containsExactlyElementsOf(wait
                    ? List.of(ack(FIRST, PLAIN)) : List.of(ack(FIRST, PLAIN), READY));
            assertThat(iterator.getContext().ready()).isEqualTo(!wait);
            assertThat(checks.readinessCalls).isEqualTo(wait ? 0 : 1);
        }
    }

    @Test
    void v2DoneStillConsumesTheRequestBoundaryButOmitsAllAcknowledgments() throws Exception {
        var checks = new TestContext(Set.of(FIRST));
        var iterator = iterator(FetchRequest.Mode.PROTOCOL_V2, checks, HTTP);
        iterator.next(have(FIRST));
        assertThat(iterator.next(DONE)).isTrue();
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(iterator.next(END_ROUND)).isFalse();
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(iterator.getContext().doneReceived()).isTrue();
        assertThat(checks.readinessCalls).isZero();
        assertThatThrownBy(() -> iterator.next(have(FIRST))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replySnapshotsAreStableAndTheNextStepClearsPreviousReplies() throws Exception {
        var iterator = iterator(FetchRequest.Mode.MULTI_ACK, new TestContext(Set.of(FIRST)), SSH);
        iterator.next(have(FIRST));
        var previous = iterator.getResponsesToSend();
        assertThat(iterator.getResponsesToSend()).isEqualTo(previous);
        assertThatThrownBy(() -> previous.clear()).isInstanceOf(UnsupportedOperationException.class);
        iterator.next(have(UNKNOWN));
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(previous).containsExactly(ack(FIRST, CONTINUE));
    }

    @Test
    void failedLookupPropagatesAndCannotLeaveAnOldAckPending() throws Exception {
        var checks = new TestContext(Set.of(FIRST));
        var iterator = iterator(FetchRequest.Mode.MULTI_ACK, checks, SSH);
        iterator.next(have(FIRST));
        checks.failure = new IOException("lookup failed");
        assertThatThrownBy(() -> iterator.next(have(UNKNOWN))).isSameAs(checks.failure);
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(iterator.getContext().commonObjects()).containsExactly(FIRST);
        assertThatThrownBy(() -> iterator.next(DONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedReadinessDiscardsTheWholeV2ReplyBatch() throws Exception {
        var checks = new TestContext(Set.of(FIRST));
        var iterator = iterator(FetchRequest.Mode.PROTOCOL_V2, checks, HTTP);
        iterator.next(have(FIRST));
        checks.failure = new IOException("graph unavailable");
        assertThatThrownBy(() -> iterator.next(END_ROUND)).isSameAs(checks.failure);
        assertThat(iterator.getResponsesToSend()).isEmpty();
        assertThat(iterator.getContext().ready()).isFalse();
        assertThatThrownBy(() -> iterator.next(DONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDuplicateDoneAndUnsupportedNoDoneCombinations() throws Exception {
        var checks = new TestContext(Set.of());
        var iterator = iterator(FetchRequest.Mode.PROTOCOL_V2, checks, HTTP);
        assertThat(iterator.next(DONE)).isTrue();
        assertThatThrownBy(() -> iterator.next(DONE)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new FetchNegotiatorIterator(
                new TestContext(request(FetchRequest.Mode.MULTI_ACK, GitCapability.NO_DONE), Set.of()), HTTP))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new FetchNegotiatorIterator(
                new TestContext(request(FetchRequest.Mode.MULTI_ACK_DETAILED, GitCapability.NO_DONE), Set.of()), SSH))
                .isInstanceOf(IOException.class);
    }

    @Test
    void transportControlsLegacyRoundBoundariesButV2AlwaysEndsTheRequest() throws Exception {
        for (GitTransport transport : GitTransport.values()) {
            var legacy = iterator(FetchRequest.Mode.SINGLE_ACK, new TestContext(Set.of()), transport);
            assertThat(legacy.next(END_ROUND)).isEqualTo(transport == SSH);
            assertThat(legacy.getResponsesToSend()).containsExactly(NAK);
            assertThat(legacy.getContext().ready()).isFalse();
            assertThat(legacy.getContext().doneReceived()).isFalse();

            var v2 = iterator(FetchRequest.Mode.PROTOCOL_V2, new TestContext(Set.of()), transport);
            assertThat(v2.next(END_ROUND)).isFalse();
            assertThat(v2.getResponsesToSend()).containsExactly(NAK);
        }
    }

    private static FetchRequest request(FetchRequest.Mode mode, GitCapability... capabilities) {
        var request = new FetchRequest();
        request.setMode(mode);
        request.wants().add(WANT);
        request.shallowCommits().add(FIRST);
        for (GitCapability capability : capabilities) {
            request.capabilities().add(value(capability));
        }
        return request;
    }

    private static FetchNegotiatorIterator iterator(FetchRequest.Mode mode, TestContext checks, GitTransport transport)
            throws IOException {
        checks.request().setMode(mode);
        return new FetchNegotiatorIterator(checks, transport);
    }

    private static NegotiationMessage.Have have(ObjectId id) {
        return new NegotiationMessage.Have(id);
    }

    private static NegotiationResponse.Ack ack(ObjectId id, NegotiationResponse.Status status) {
        return new NegotiationResponse.Ack(id, status);
    }

    private static final class TestContext extends NegotiationContext {
        private final Set<ObjectId> existing;
        private boolean ready;
        private int readinessCalls;
        private IOException failure;

        private TestContext(Set<ObjectId> existing) {
            this(FetchNegotiatorIteratorTest.request(FetchRequest.Mode.SINGLE_ACK), existing);
        }

        private TestContext(FetchRequest request, Set<ObjectId> existing) {
            super(request, new GitStorageApi(), capabilities(GitCapability.SHALLOW, GitCapability.MULTI_ACK,
                    GitCapability.MULTI_ACK_DETAILED, GitCapability.NO_DONE, GitCapability.WAIT_FOR_DONE));
            this.existing = existing;
        }

        @Override
        public boolean objectExists(ObjectId id) throws IOException {
            if (failure != null) {
                throw failure;
            }
            return existing.contains(id);
        }

        @Override
        public boolean isReady() throws IOException {
            readinessCalls++;
            if (failure != null) {
                throw failure;
            }
            return ready;
        }
    }
}
