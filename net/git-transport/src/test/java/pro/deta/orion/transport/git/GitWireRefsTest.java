package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.transport.git.GitWireTestClient.*;

class GitWireRefsTest {

    @Test
    void listsMatchingBranchesAndLightweightTagsInLexicographicOrder() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId lightweightTagId = repository.writeObject(GitObjectType.COMMIT,
                "tag target".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/tags/v1", NULL_ID, lightweightTagId.toHex());
        repository.updateRef("refs/heads/topic", NULL_ID, TAG_ID);
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "refs/heads/main"), direct(TAG_ID,
                "refs/heads/topic"), direct(lightweightTagId.toHex(), "refs/tags/v1"));
    }

    @Test
    void doesNotDuplicateRefsMatchedByOverlappingPrefixes() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(false, false, false, List.of("refs/", "refs/heads/")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "refs/heads/main"));
    }

    @Test
    void returnsEmptyResponseWhenNoRefsMatch() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).isEmpty();
    }

    @Test
    void listsResolvedHeadWithoutSymrefTargetWhenNotRequested() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, false, false,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "HEAD"));
    }

    @Test
    void listsResolvedHeadWithSymrefTargetWhenRequested() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, true, false,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "HEAD", Optional.of("refs/heads/main"),
                Optional.empty()));
    }

    @Test
    void listsUnbornHeadWhenAnotherBranchExists() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/master", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(false, true, true, List.of("HEAD")));

        assertThat(response.refs()).containsExactly(
                new LsRefsResponse.UnbornRef("HEAD", "refs/heads/main"));
    }

    @Test
    void listsDetachedHeadThroughTheDedicatedHeadApi() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        repository.storage().updateHead(new Head.Detached(new CommitId(TAG_ID)));
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, true, false,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(TAG_ID, "HEAD"));
    }

    @Test
    void listsUnbornHeadWhenRequested() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        createRepository(provider, "demo");
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, true, true,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(new LsRefsResponse.UnbornRef("HEAD", "refs/heads/main"));
    }

    @Test
    void peelsNestedAnnotatedTagToFinalNonTagObject() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId commitId = repository.writeObject(GitObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        ObjectId innerTagId = repository.writeObject(GitObjectType.TAG, tagData(commitId.toHex()));
        ObjectId outerTagId = repository.writeObject(GitObjectType.TAG, tagData(innerTagId.toHex()));
        repository.updateRef("refs/tags/nested", NULL_ID, outerTagId.toHex());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(outerTagId.toHex(), "refs/tags/nested",
                Optional.empty(), Optional.of(commitId.toHex())));
    }

    @Test
    void peelsAnnotatedTagWithLargeBodyFromBoundedPrefix() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId commitId = repository.writeObject(GitObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        byte[] objectLine = ("object " + commitId.toHex() + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tagData = new byte[1024 * 1024 + objectLine.length];
        Arrays.fill(tagData, (byte) 'x');
        System.arraycopy(objectLine, 0, tagData, 0, objectLine.length);
        ObjectId tagId = repository.writeObject(GitObjectType.TAG, tagData);
        repository.updateRef("refs/tags/large", NULL_ID, tagId.toHex());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(true, false, false, List.of("refs/tags/large")));

        assertThat(response.refs()).containsExactly(direct(tagId.toHex(), "refs/tags/large", Optional.empty(),
                Optional.of(commitId.toHex())));
    }

    @Test
    void omitsPeeledAttributeForMalformedAnnotatedTag() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId malformedTagId = repository.writeObject(GitObjectType.TAG,
                "object not-a-hex-object-id\n".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/tags/malformed", NULL_ID, malformedTagId.toHex());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(malformedTagId.toHex(), "refs/tags/malformed"));
    }

    @Test
    void omitsPeeledAttributeWhenAnnotatedTagTargetIsMissing() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId tagId = repository.writeObject(GitObjectType.TAG, tagData("f".repeat(40)));
        repository.updateRef("refs/tags/missing-target", NULL_ID, tagId.toHex());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(tagId.toHex(), "refs/tags/missing-target"));
    }

    @Test
    void memoizesSharedTagChainsAcrossMatchingRefs() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId commitId = repository.writeObject(GitObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        List<ObjectId> tagIds = new ArrayList<>();
        ObjectId targetId = commitId;
        int chainLength = 180;
        for (int i = 0; i < chainLength; i++) {
            ObjectId tagId = repository.writeObject(GitObjectType.TAG, tagData(targetId.toHex()));
            tagIds.add(tagId);
            targetId = tagId;
            repository.updateRef("refs/tags/shared-%03d".formatted(i), NULL_ID, tagId.toHex());
        }
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(true, false, false, List.of("refs/tags/shared-")));

        List<LsRefsResponse.DirectRef> expected = new ArrayList<>();
        for (int i = 0; i < chainLength; i++) {
            expected.add(direct(tagIds.get(i).toHex(), "refs/tags/shared-%03d".formatted(i), Optional.empty(),
                Optional.of(commitId.toHex())));
        }
        assertThat(response.refs()).containsExactlyElementsOf(expected);
    }

    @Test
    void distinguishesCachedLightweightTagFromAnnotatedTagTarget() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId commitId = repository.writeObject(GitObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        ObjectId annotatedTagId = repository.writeObject(GitObjectType.TAG, tagData(commitId.toHex()));
        repository.updateRef("refs/tags/a-lightweight", NULL_ID, commitId.toHex());
        repository.updateRef("refs/tags/z-annotated", NULL_ID, annotatedTagId.toHex());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(commitId.toHex(), "refs/tags/a-lightweight"),
                direct(annotatedTagId.toHex(), "refs/tags/z-annotated", Optional.empty(),
                Optional.of(commitId.toHex())));
    }

    @Test
    void omitsPeeledAttributeWhenTagChainExceedsDepthLimit() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        ObjectId targetId = repository.writeObject(GitObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i <= 256; i++) {
            targetId = repository.writeObject(GitObjectType.TAG, tagData(targetId.toHex()));
        }
        repository.updateRef("refs/tags/too-deep", NULL_ID, targetId.toHex());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        LsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(true, false, false, List.of("refs/tags/too-deep")));

        assertThat(response.refs()).containsExactly(direct(targetId.toHex(), "refs/tags/too-deep"));
    }

    private static byte[] tagData(String targetId) {
        return ("object " + targetId + "\n" + "type tag\n" + "tag nested\n\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
