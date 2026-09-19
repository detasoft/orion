package pro.deta.orion.git.parser.v2.fetch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitTransport;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackTestData;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.v2.capability.GitCapabilityValue.value;
import static pro.deta.orion.git.parser.v2.fetch.FetchTestSupport.capabilities;

class FetchReadinessTest {
    @TempDir
    Path directory;
    private GitStorageApi storage;
    private ObjectId tree;

    @BeforeEach
    void setup() throws Exception {
        storage = new GitStorageApi(directory);
        tree = PackTestData.store(storage, GitObjectType.TREE, new byte[0]);
    }

    @Test
    void requiresWantsAndConfirmedCommonObjectsWithoutMutatingNegotiation() throws Exception {
        ObjectId root = commit();
        ObjectId tip = commit(root);
        NegotiationContext context = context(tip);
        assertThat(context.isReady()).isFalse();
        context.addCommon(root);
        assertThat(context.isReady()).isTrue();
        assertThat(context.commonObjects()).containsExactly(root);
        assertThat(context.ready()).isFalse();
        assertThat(context.doneReceived()).isFalse();
        context.request().wants().clear();
        assertThat(context.isReady()).isFalse();
    }

    @Test
    void everyIndependentWantNeedsItsOwnCommonPath() throws Exception {
        ObjectId root = commit();
        ObjectId left = commit(root);
        ObjectId right = put(GitObjectType.COMMIT, commitText() + "another root");
        NegotiationContext context = context(left, right);
        context.addCommon(root);
        assertThat(context.isReady()).isFalse();
        context.addCommon(right);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void aSiblingDoesNotEstablishCommonHistoryButTheSecondMergeParentDoes() throws Exception {
        ObjectId root = commit();
        ObjectId left = commit(root);
        ObjectId right = put(GitObjectType.COMMIT, commitText(root) + "other branch");
        NegotiationContext context = context(left);
        context.addCommon(right);
        assertThat(context.isReady()).isFalse();
        NegotiationContext merge = context(commit(left, right));
        merge.addCommon(right);
        assertThat(merge.isReady()).isTrue();
    }

    @Test
    void doesNotTraverseAnUnconfirmedShallowBoundary() throws Exception {
        ObjectId root = commit();
        ObjectId boundary = commit(root);
        NegotiationContext context = context(commit(boundary));
        context.request().shallowCommits().add(boundary);
        context.addCommon(root);
        assertThat(context.isReady()).isFalse();
        context.addCommon(boundary);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void includesResolvedWantRefsAndPeelsTags() throws Exception {
        ObjectId root = commit();
        ObjectId tag = put(GitObjectType.TAG, "object " + commit(root) + "\ntype commit\ntag release\n\nmessage");
        RefId ref = new RefId("refs/tags/release");
        NegotiationContext context = context();
        context.request().wantRefs().add(ref.value());
        context.resolveWantedRefs(new RefsSnapshot(Map.of(ref, tag), new Head.Symbolic(ref)));
        context.addCommon(root);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void treesAndBlobsNeedNoHistoryButNonCommitParentsAreInvalid() throws Exception {
        ObjectId blob = put(GitObjectType.BLOB, "content");
        ObjectId root = commit();
        NegotiationContext content = context(tree, blob);
        content.addCommon(root);
        assertThat(content.isReady()).isTrue();
        NegotiationContext malformed = context(commit(blob));
        malformed.addCommon(root);
        assertThatThrownBy(malformed::isReady).isInstanceOf(IOException.class)
                .hasMessageContaining("parent is not a commit");
    }

    @Test
    void missingAndMalformedHistoryAreErrors() throws Exception {
        ObjectId root = commit();
        NegotiationContext missing = context(new ObjectId("f".repeat(40)));
        missing.addCommon(root);
        assertThatThrownBy(missing::isReady).isInstanceOf(IOException.class);
        NegotiationContext malformed = context(put(GitObjectType.COMMIT, "tree " + tree + "\nparent invalid\n\n"));
        malformed.addCommon(root);
        assertThatThrownBy(malformed::isReady).isInstanceOf(IOException.class);
    }

    @Test
    void skipsLongHeaderValuesAndNeverInterpretsTheMessageAsParents() throws Exception {
        ObjectId root = commit();
        String header = "tree " + tree + "\ngpgsig " + "x".repeat(100_000) + "\n";
        NegotiationContext message = context(put(GitObjectType.COMMIT, header + "\nparent " + root + "\n"));
        message.addCommon(root);
        assertThat(message.isReady()).isFalse();
        NegotiationContext parent = context(put(GitObjectType.COMMIT, header + "parent " + root + "\n\n"));
        parent.addCommon(root);
        assertThat(parent.isReady()).isTrue();
    }

    @Test
    void readsParentsFromARefDeltaCommit() throws Exception {
        ObjectId root = commit();
        byte[] base = ("tree " + tree + "\n\n").getBytes(StandardCharsets.US_ASCII);
        byte[] content = ("tree " + tree + "\nparent " + root + "\n\nmessage")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] instructions = PackTestData.join(
                new byte[]{(byte) base.length, (byte) content.length, (byte) content.length}, content);
        ObjectId tip = PackTestData.storeDelta(storage, GitObjectType.COMMIT, base, instructions, content);
        NegotiationContext context = context(tip);
        context.addCommon(root);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void walksLongHistoryWithoutRecursiveCalls() throws Exception {
        ObjectId root = commit();
        ObjectId tip = root;
        List<byte[]> entries = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            byte[] bytes = commitText(tip).getBytes(StandardCharsets.US_ASCII);
            entries.add(PackTestData.entry(GitObjectType.COMMIT, bytes));
            tip = PackTestData.objectId(GitObjectType.COMMIT, bytes);
        }
        try (IndexedPack pack = PackTestData.ingest(PackTestData.pack(entries.toArray(byte[][]::new)),
                storage.newPack())) {
            storage.persist(pack);
        }
        NegotiationContext context = context(tip);
        context.addCommon(root);
        assertThat(context.isReady()).isTrue();
    }

    @Test
    void emitsReadyForCommonHistoryButHonorsWaitForDone() throws Exception {
        ObjectId root = commit();
        ObjectId tip = commit(root);
        for (boolean wait : new boolean[]{false, true}) {
            NegotiationContext context = context(tip);
            if (wait) {
                context.request().capabilities().add(value(GitCapability.WAIT_FOR_DONE));
            }
            FetchNegotiatorIterator iterator = new FetchNegotiatorIterator(context, GitTransport.HTTP);
            iterator.next(new NegotiationMessage.Have(root));
            iterator.next(NegotiationMessage.Control.END_ROUND);
            assertThat(context.ready()).isEqualTo(!wait);
            assertThat(iterator.getResponsesToSend().contains(NegotiationResponse.Control.READY)).isEqualTo(!wait);
        }
    }

    private NegotiationContext context(ObjectId... wants) {
        FetchRequest request = new FetchRequest();
        request.setMode(FetchRequest.Mode.PROTOCOL_V2);
        request.wants().addAll(List.of(wants));
        return new NegotiationContext(request, storage,
                capabilities(GitCapability.SHALLOW, GitCapability.WAIT_FOR_DONE));
    }

    private ObjectId commit(ObjectId... parents) throws Exception {
        return put(GitObjectType.COMMIT, commitText(parents));
    }

    private String commitText(ObjectId... parents) {
        StringBuilder text = new StringBuilder("tree " + tree + "\n");
        for (ObjectId parent : parents) {
            text.append("parent ").append(parent).append('\n');
        }
        return text.append("author A <a@b> 0 +0000\ncommitter A <a@b> 0 +0000\n\nmessage\n").toString();
    }

    private ObjectId put(GitObjectType type, String content) throws Exception {
        return PackTestData.store(storage, type, content.getBytes(StandardCharsets.US_ASCII));
    }
}
