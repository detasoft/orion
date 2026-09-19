package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.fetch.FetchTestSupport;
import pro.deta.orion.git.parser.v2.id.ObjectId;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;

class NegotiationContextTest {
    @TempDir
    static Path directory;
    private static final ObjectId FIRST = new ObjectId("1".repeat(40));
    private static final ObjectId SECOND = new ObjectId("2".repeat(40));

    @Test
    void clientClaimsDoNotBecomeCommonWithoutConfirmation() {
        FetchRequest request = request(List.of(new NegotiationMessage.Have(FIRST),
                NegotiationMessage.Control.DONE, NegotiationMessage.Control.END_ROUND));
        NegotiationContext context = new NegotiationContext(request, FetchTestSupport.storage(directory), capabilities());
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
        NegotiationContext context = new NegotiationContext(request, FetchTestSupport.storage(directory), capabilities());
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
        assertThat(new NegotiationContext(request, FetchTestSupport.storage(directory), capabilities()).commonObjects()).isEmpty();
    }

    private static FetchRequest request(List<NegotiationMessage> messages) {
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wants().add(SECOND);
        request.shallowCommits().add(FIRST);
        request.initialMessages().addAll(messages);
        return request;
    }
}
