package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.fetch.FetchTestSupport;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;

class FetchWantedRefsTest {
    @TempDir
    static Path directory;
    private static final ObjectId FIRST = new ObjectId("1".repeat(40));
    private static final ObjectId SECOND = new ObjectId("2".repeat(40));
    private static final RefId MAIN = new RefId("refs/heads/main");
    private static final RefId TAG = new RefId("refs/tags/release");
    private static final RefId HEAD = new RefId("HEAD");

    @Test
    void mergesResolvedTargetsWithoutLosingNamesOrChangingTheParsedRequest() throws Exception {
        var context = context(MAIN.value(), TAG.value());
        context.request().wants().add(FIRST);
        var snapshot = new RefsSnapshot(Map.of(MAIN, SECOND, TAG, SECOND), new Head.Symbolic(MAIN));

        context.resolveWantedRefs(snapshot);

        assertThat(context.wantedObjects()).containsExactly(FIRST, SECOND);
        assertThat(context.wantedRefs()).containsExactly(Map.entry(MAIN, SECOND), Map.entry(TAG, SECOND));
        assertThat(context.request().wants()).containsExactly(FIRST);
        assertThat(context.request().wantRefs()).containsExactly(MAIN.value(), TAG.value());
        assertThat(context.commonObjects()).isEmpty();
        assertThat(context.ready()).isFalse();
        assertThatThrownBy(() -> context.wantedRefs().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> context.wantedObjects().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void symbolicHeadUsesTheSameSnapshotAndKeepsHeadAsTheRequestedName() throws Exception {
        var context = context(HEAD.value(), MAIN.value());
        context.resolveWantedRefs(new RefsSnapshot(Map.of(MAIN, FIRST), new Head.Symbolic(MAIN)));
        assertThat(context.wantedRefs()).containsExactly(Map.entry(HEAD, FIRST), Map.entry(MAIN, FIRST));
        assertThat(context.wantedObjects()).containsExactly(FIRST);
    }

    @Test
    void detachedHeadBecomesAnObjectIdAndDeduplicatesAgainstExplicitWants() throws Exception {
        var context = context(HEAD.value());
        context.request().wants().add(FIRST);
        context.resolveWantedRefs(new RefsSnapshot(Map.of(), new Head.Detached(new CommitId(FIRST.toBytes()))));
        assertThat(context.wantedRefs()).containsExactly(Map.entry(HEAD, FIRST));
        assertThat(context.wantedObjects()).containsExactly(FIRST);
    }

    @Test
    void missingRefDoesNotLeavePartiallyResolvedTargets() {
        var context = context(MAIN.value(), TAG.value());
        var snapshot = new RefsSnapshot(Map.of(MAIN, FIRST), new Head.Symbolic(MAIN));
        assertThatThrownBy(() -> context.resolveWantedRefs(snapshot))
                .isInstanceOf(IOException.class).hasMessageContaining(TAG.value());
        assertThat(context.wantedRefs()).isEmpty();
        assertThatThrownBy(context::wantedObjects).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unbornHeadCannotBeReturnedAsAnObject() {
        var context = context(HEAD.value());
        assertThatThrownBy(() -> context.resolveWantedRefs(
                new RefsSnapshot(Map.of(), new Head.Symbolic(MAIN))))
                .isInstanceOf(IOException.class).hasMessageContaining(HEAD.value());
        assertThat(context.wantedRefs()).isEmpty();
    }

    @Test
    void requestsWithoutWantRefsNeedNoSnapshot() {
        var context = context();
        context.request().wants().add(FIRST);
        assertThat(context.wantedObjects()).containsExactly(FIRST);
        assertThat(context.wantedRefs()).isEmpty();
    }

    @Test
    void returnedResultsRemainStableWhenPreparationUsesANewSnapshot() throws Exception {
        var context = context(MAIN.value());
        context.resolveWantedRefs(new RefsSnapshot(Map.of(MAIN, FIRST), new Head.Symbolic(MAIN)));
        var oldRefs = context.wantedRefs();
        var oldObjects = context.wantedObjects();
        context.resolveWantedRefs(new RefsSnapshot(Map.of(MAIN, SECOND), new Head.Symbolic(MAIN)));
        assertThat(oldRefs).containsExactly(Map.entry(MAIN, FIRST));
        assertThat(oldObjects).containsExactly(FIRST);
        assertThat(context.wantedObjects()).containsExactly(SECOND);
    }

    private static NegotiationContext context(String... refs) {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wantRefs().addAll(List.of(refs));
        return new NegotiationContext(request, FetchTestSupport.storage(directory), capabilities(GitCapability.REF_IN_WANT));
    }
}
