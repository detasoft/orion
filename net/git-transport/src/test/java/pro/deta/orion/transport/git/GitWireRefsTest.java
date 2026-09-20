package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.v2.lsrefs.LsRefsRequest;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.id.CommitId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.transport.git.GitWireTestClient.*;

class GitWireRefsTest {

    @Test
    void listsMatchingBranchesAndLightweightTagsInLexicographicOrder() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId lightweightTagId = repository.writeObject(ObjectType.COMMIT,
                "tag target".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/tags/v1", NULL_ID, lightweightTagId.value());
        repository.updateRef("refs/heads/topic", NULL_ID, TAG_ID);
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "refs/heads/main"), direct(TAG_ID,
                "refs/heads/topic"), direct(lightweightTagId.value(), "refs/tags/v1"));
    }

    @Test
    void doesNotDuplicateRefsMatchedByOverlappingPrefixes() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(
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

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).isEmpty();
    }

    @Test
    void listsResolvedHeadWithoutSymrefTargetWhenNotRequested() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, false, false,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "HEAD"));
    }

    @Test
    void listsResolvedHeadWithSymrefTargetWhenRequested() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, true, false,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "HEAD", Optional.of("refs/heads/main"),
                Optional.empty()));
    }

    @Test
    void listsHeadFromExistingBranchWhenDefaultHeadTargetIsMissing() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/master", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(false, true, true, List.of("HEAD")));

        assertThat(response.refs()).containsExactly(
                direct(MAIN_ID, "HEAD", Optional.of("refs/heads/master"), Optional.empty()));
    }

    @Test
    void listsDetachedHeadThroughTheDedicatedHeadApi() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        repository.storage().updateHead(new Head.Detached(new CommitId(TAG_ID)));
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, true, false,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(TAG_ID, "HEAD"));
    }

    @Test
    void listsUnbornHeadWhenRequested() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        createRepository(provider, "demo");
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(false, true, true,
                List.of("HEAD")));

        assertThat(response.refs()).containsExactly(new GitLsRefsResponse.UnbornRef("HEAD", "refs/heads/main"));
    }

    @Test
    void peelsNestedAnnotatedTagToFinalNonTagObject() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        GitObjectId innerTagId = repository.writeObject(ObjectType.TAG, tagData(commitId.value()));
        GitObjectId outerTagId = repository.writeObject(ObjectType.TAG, tagData(innerTagId.value()));
        repository.updateRef("refs/tags/nested", NULL_ID, outerTagId.value());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(outerTagId.value(), "refs/tags/nested",
                Optional.empty(), Optional.of(commitId.value())));
    }

    @Test
    void peelsAnnotatedTagWithLargeBodyFromBoundedPrefix() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        byte[] objectLine = ("object " + commitId.value() + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tagData = new byte[1024 * 1024 + objectLine.length];
        Arrays.fill(tagData, (byte) 'x');
        System.arraycopy(objectLine, 0, tagData, 0, objectLine.length);
        GitObjectId tagId = repository.writeObject(ObjectType.TAG, tagData);
        repository.updateRef("refs/tags/large", NULL_ID, tagId.value());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        Optional<LooseObjectPrefix> prefix = repository.readObjectPrefix(tagId, 48);
        GitLsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(true, false, false, List.of("refs/tags/large")));

        assertThat(prefix).isPresent();
        assertThat(prefix.get().dataPrefix()).hasSize(48).isEqualTo(objectLine);
        assertThat(response.refs()).containsExactly(direct(tagId.value(), "refs/tags/large", Optional.empty(),
                Optional.of(commitId.value())));
    }

    @Test
    void omitsPeeledAttributeForMalformedAnnotatedTag() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId malformedTagId = repository.writeObject(ObjectType.TAG,
                "object not-a-hex-object-id\n".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/tags/malformed", NULL_ID, malformedTagId.value());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(malformedTagId.value(), "refs/tags/malformed"));
    }

    @Test
    void omitsPeeledAttributeWhenAnnotatedTagTargetIsMissing() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId tagId = repository.writeObject(ObjectType.TAG, tagData("f".repeat(40)));
        repository.updateRef("refs/tags/missing-target", NULL_ID, tagId.value());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(tagId.value(), "refs/tags/missing-target"));
    }

    @Test
    void memoizesSharedTagChainsAcrossMatchingRefs() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        List<GitObjectId> tagIds = new ArrayList<>();
        GitObjectId targetId = commitId;
        int chainLength = 180;
        for (int i = 0; i < chainLength; i++) {
            GitObjectId tagId = repository.writeObject(ObjectType.TAG, tagData(targetId.value()));
            tagIds.add(tagId);
            targetId = tagId;
            repository.updateRef("refs/tags/shared-%03d".formatted(i), NULL_ID, tagId.value());
        }
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(true, false, false, List.of("refs/tags/shared-")));

        List<GitLsRefsResponse.DirectRef> expected = new ArrayList<>();
        for (int i = 0; i < chainLength; i++) {
            expected.add(direct(tagIds.get(i).value(), "refs/tags/shared-%03d".formatted(i), Optional.empty(),
                Optional.of(commitId.value())));
        }
        assertThat(response.refs()).containsExactlyElementsOf(expected);
    }

    @Test
    void distinguishesCachedLightweightTagFromAnnotatedTagTarget() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        GitObjectId annotatedTagId = repository.writeObject(ObjectType.TAG, tagData(commitId.value()));
        repository.updateRef("refs/tags/a-lightweight", NULL_ID, commitId.value());
        repository.updateRef("refs/tags/z-annotated", NULL_ID, annotatedTagId.value());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("demo"), new LsRefsRequest(true, false, false,
                List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(commitId.value(), "refs/tags/a-lightweight"),
                direct(annotatedTagId.value(), "refs/tags/z-annotated", Optional.empty(),
                Optional.of(commitId.value())));
    }

    @Test
    void omitsPeeledAttributeWhenTagChainExceedsDepthLimit() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        GitObjectId targetId = repository.writeObject(ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i <= 256; i++) {
            targetId = repository.writeObject(ObjectType.TAG, tagData(targetId.value()));
        }
        repository.updateRef("refs/tags/too-deep", NULL_ID, targetId.value());
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(
                service,
                request("demo"),
                new LsRefsRequest(true, false, false, List.of("refs/tags/too-deep")));

        assertThat(response.refs()).containsExactly(direct(targetId.value(), "refs/tags/too-deep"));
    }

    private static byte[] tagData(String targetId) {
        return ("object " + targetId + "\n" + "type tag\n" + "tag nested\n\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
