package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NegotiationContextTest {
    private static final ObjectId FIRST = new ObjectId("1".repeat(40));
    private static final ObjectId SECOND = new ObjectId("2".repeat(40));

    @Test
    void clientClaimsDoNotBecomeCommonWithoutConfirmation() {
        FetchRequest request = request(List.of(new NegotiationMessage.Have(FIRST),
                NegotiationMessage.Control.DONE, NegotiationMessage.Control.END_ROUND));
        NegotiationContext context = new NegotiationContext(request);
        assertThat(context.commonObjects()).isEmpty();
        assertThat(context.lastCommon()).isEmpty();
        assertThat(context.doneReceived()).isFalse();
        assertThat(context.ready()).isFalse();
        assertThat(context.request().shallowCommits()).containsExactly(FIRST);
        context.markDone();
        assertThat(context.doneReceived()).isTrue();
        assertThat(context.ready()).isFalse();
    }

    @Test
    void accumulatesConfirmedObjectsAcrossRoundsWithoutLeakingMutableState() {
        FetchRequest request = request(List.of());
        NegotiationContext context = new NegotiationContext(request);
        context.addCommon(FIRST);
        Set<ObjectId> firstRound = context.commonObjects();
        context.addCommon(SECOND);
        context.addCommon(FIRST);
        context.markReady();
        assertThat(context.commonObjects()).containsExactlyInAnyOrder(FIRST, SECOND);
        assertThat(context.lastCommon()).contains(FIRST);
        assertThat(firstRound).containsExactly(FIRST);
        assertThatThrownBy(() -> firstRound.add(SECOND)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.ready()).isTrue();
        assertThat(context.doneReceived()).isFalse();
        assertThat(new NegotiationContext(request).commonObjects()).isEmpty();
    }

    private static FetchRequest request(List<NegotiationMessage> messages) {
        return new FetchRequest(Set.of(SECOND), Set.of(FIRST), FetchRequest.Mode.PROTOCOL_V2,
                Set.of(), messages, Set.of(), OptionalInt.empty(), OptionalLong.empty(), Set.of(),
                Optional.empty(), Set.of());
    }
}
