package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.GitTransport;
import pro.deta.orion.git.parser.v2.data.FetchRequest;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.storage.InMemoryGitStorage;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FetchReadinessTest {
    private static final ObjectId ROOT = id(1);
    private static final ObjectId LEFT = id(2);
    private static final ObjectId RIGHT = id(3);
    private static final ObjectId TIP = id(4);
    private static final ObjectId TAG = id(5);
    private final InMemoryGitStorage storage = new InMemoryGitStorage();

    @Test
    void requiresBothWantsAndConfirmedCommonObjects() throws Exception {
        var context = context(TIP);
        assertThat(context.isReady()).isFalse();
        context.request().wants().clear();
        context.addCommon(ROOT);
        assertThat(context.isReady()).isFalse();
        assertThat(storage.lookups).isEmpty();
    }

    @Test
    void stopsAtCommonAncestorWithoutReadingItsParentsOrMutatingNegotiation() throws Exception {
        commit(TIP, LEFT);
        commit(LEFT, ROOT);
        var context = context(TIP);
        context.addCommon(ROOT);
        assertThat(context.isReady()).isTrue();
        assertThat(storage.lookups).containsExactly(TIP, LEFT);
        assertThat(context.commonObjects()).containsExactly(ROOT);
        assertThat(context.ready()).isFalse();
        assertThat(context.doneReceived()).isFalse();
    }

    @Test
    void everyIndependentWantNeedsACommonPath() throws Exception {
        commit(LEFT, ROOT);
        commit(RIGHT);
        var context = context(LEFT, RIGHT);
        context.addCommon(ROOT);
        assertThat(context.isReady()).isFalse();
        context.addCommon(RIGHT);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void anotherBranchDoesNotEstablishAnExplicitCommonAncestor() throws Exception {
        commit(ROOT);
        commit(LEFT, ROOT);
        commit(RIGHT, ROOT);
        var context = context(LEFT);
        context.addCommon(RIGHT);
        assertThat(context.isReady()).isFalse();
        context.addCommon(ROOT);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void mergeCanReachCommonThroughItsSecondParent() throws Exception {
        commit(TIP, LEFT, RIGHT);
        commit(LEFT);
        var context = context(TIP);
        context.addCommon(RIGHT);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void doesNotTraverseAnUnconfirmedShallowBoundary() throws Exception {
        commit(TIP, LEFT);
        commit(LEFT, ROOT);
        var context = context(TIP);
        context.request().shallowCommits().add(LEFT);
        context.addCommon(ROOT);
        assertThat(context.isReady()).isFalse();
        assertThat(storage.lookups).doesNotContain(ROOT);
        context.addCommon(LEFT);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void includesResolvedWantRefsAndPeelsTags() throws Exception {
        commit(TIP, ROOT);
        put(TAG, ObjectType.TAG, "object " + TIP.toHex() + "\ntype commit\ntag release\n\nmessage");
        var context = context();
        var ref = new RefId("refs/tags/release");
        context.request().wantRefs().add(ref.value());
        context.resolveWantedRefs(new RefsSnapshot(Map.of(ref, TAG), new Head.Symbolic(ref)));
        context.addCommon(ROOT);
        assertThat(context.isReady()).isTrue();
        assertThat(storage.lookups).containsExactly(TAG, TIP);
    }

    @Test
    void treesAndBlobsDoNotRequireCommitHistory() throws Exception {
        put(LEFT, ObjectType.TREE, "");
        put(RIGHT, ObjectType.BLOB, "parent not-a-header");
        var context = context(LEFT, RIGHT);
        context.addCommon(ROOT);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void missingOrCorruptHistoryIsAnErrorRatherThanNotReady() {
        var context = context(TIP);
        context.addCommon(ROOT);
        assertThatThrownBy(context::isReady).isInstanceOf(IOException.class).hasMessageContaining(TIP.toHex());
        put(TIP, ObjectType.COMMIT, "tree " + id(99).toHex() + "\nparent invalid\n\nmessage");
        assertThatThrownBy(context::isReady).isInstanceOf(IOException.class);
        storage.failure = new IOException("backend failure");
        assertThatThrownBy(context::isReady).isSameAs(storage.failure);
    }

    @Test
    void malformedCyclesCannotLoopForeverOrProduceReadiness() throws Exception {
        commit(LEFT, RIGHT);
        commit(RIGHT, LEFT);
        var context = context(LEFT);
        context.addCommon(ROOT);
        assertThat(context.isReady()).isFalse();
        assertThat(storage.lookups).containsExactly(LEFT, RIGHT);
    }

    @Test
    void readsParentsFromARefDeltaCommit() throws Exception {
        String base = "tree " + id(99).toHex() + "\n\n";
        String target = "tree " + id(99).toHex() + "\nparent " + ROOT.toHex() + "\n\nmessage";
        put(RIGHT, ObjectType.COMMIT, base);
        byte[] content = target.getBytes(StandardCharsets.US_ASCII);
        byte[] delta = new byte[content.length + 3];
        delta[0] = (byte) base.length();
        delta[1] = (byte) content.length;
        delta[2] = (byte) content.length;
        System.arraycopy(content, 0, delta, 3, content.length);
        storage.put(TIP, ObjectType.REF_DELTA, Optional.of(RIGHT), delta);
        var context = context(TIP);
        context.addCommon(ROOT);
        assertThat(context.isReady()).isTrue();
        assertThat(storage.lookups).containsExactly(TIP, RIGHT);
    }

    @Test
    void skipsLongHeaderValuesAndNeverInterpretsTheMessageAsParents() throws Exception {
        String header = "tree " + id(99).toHex() + "\ngpgsig " + "x".repeat(100_000) + "\n";
        put(TIP, ObjectType.COMMIT, header + "\nparent " + ROOT.toHex() + "\n");
        var context = context(TIP);
        context.addCommon(ROOT);
        assertThat(context.isReady()).isFalse();
        put(TIP, ObjectType.COMMIT, header + "parent " + ROOT.toHex() + "\n\nmessage");
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void aNonCommitParentCannotMakeTheHistoryReady() {
        commit(TIP, LEFT);
        put(LEFT, ObjectType.BLOB, "content");
        var context = context(TIP);
        context.addCommon(ROOT);
        assertThatThrownBy(context::isReady).isInstanceOf(IOException.class)
                .hasMessageContaining("parent is not a commit");
    }

    @Test
    void walksLongHistoryWithoutRecursiveCalls() throws Exception {
        int length = 1500;
        for (int i = 2; i <= length; i++) {
            commit(id(i), id(i - 1));
        }
        var context = context(id(length));
        context.addCommon(ROOT);
        assertThat(context.isReady()).isTrue();
        assertThat(storage.lookups).hasSize(length - 1);
    }

    @Test
    void roundUsesTheRealGraphCheckToEmitReady() throws Exception {
        commit(ROOT);
        commit(TIP, ROOT);
        var context = context(TIP);
        var iterator = new FetchNegotiatorIterator(context, GitTransport.HTTP);
        assertThat(iterator.next(new NegotiationMessage.Have(ROOT))).isTrue();
        assertThat(iterator.next(NegotiationMessage.Control.END_ROUND)).isFalse();
        assertThat(iterator.getResponsesToSend()).containsExactly(
                new NegotiationResponse.Ack(ROOT, NegotiationResponse.Status.PLAIN),
                NegotiationResponse.Control.READY);
        assertThat(context.ready()).isTrue();
        assertThat(context.doneReceived()).isFalse();
    }

    @Test
    void waitForDoneSkipsGraphTraversal() throws Exception {
        commit(ROOT);
        var context = context(TIP);
        context.request().capabilities().add(GitCapability.WAIT_FOR_DONE.entry());
        var iterator = new FetchNegotiatorIterator(context, GitTransport.HTTP);
        iterator.next(new NegotiationMessage.Have(ROOT));
        iterator.next(NegotiationMessage.Control.END_ROUND);
        assertThat(iterator.getResponsesToSend()).containsExactly(
                new NegotiationResponse.Ack(ROOT, NegotiationResponse.Status.PLAIN));
        assertThat(storage.lookups).containsExactly(ROOT);
        assertThat(context.ready()).isFalse();
    }

    private NegotiationContext context(ObjectId... wants) {
        var request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wants().addAll(List.of(wants));
        return new NegotiationContext(request, storage.api,
                Set.of(GitCapability.SHALLOW, GitCapability.WAIT_FOR_DONE));
    }

    private void commit(ObjectId id, ObjectId... parents) {
        var text = new StringBuilder("tree " + id(99).toHex() + "\n");
        for (ObjectId parent : parents) {
            text.append("parent ").append(parent.toHex()).append('\n');
        }
        text.append("author A <a@b> 0 +0000\ncommitter A <a@b> 0 +0000\n\nmessage\n");
        put(id, ObjectType.COMMIT, text.toString());
    }

    private void put(ObjectId id, ObjectType type, String content) {
        storage.put(id, type, Optional.empty(), content.getBytes(StandardCharsets.US_ASCII));
    }

    private static ObjectId id(int number) {
        return new ObjectId(String.format("%040x", number));
    }
}
