package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.continuation.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.continuation.exchange.LsRefsRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GitNativeRepositoryServiceTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "1".repeat(40);
    private static final String TAG_ID = "2".repeat(40);

    @Test
    void createsEmptyRepositoryAndAdvertisesCapabilityPseudoRef() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitV1Advertisement advertisement =
                service.legacyUploadPackAdvertisement(
                        request("/demo.git"));

        assertThat(provider.exists("demo.git")).isTrue();
        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(
                        NULL_ID,
                        "capabilities^{}"));
    }

    @Test
    void advertisesHeadFirstAndSortsRepositoryRefs() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef(
                "refs/tags/v1",
                NULL_ID,
                TAG_ID);
        repository.updateRef(
                "refs/heads/main",
                NULL_ID,
                MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitV1Advertisement advertisement =
                service.legacyUploadPackAdvertisement(
                        request("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(MAIN_ID, "HEAD"),
                GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main"),
                GitAdvertisedRef.direct(
                        TAG_ID,
                        "refs/tags/v1"));
        assertThat(advertisement.capabilities())
                .extracting(capability -> capability.wireToken())
                .contains("symref=HEAD:refs/heads/main");
    }

    @Test
    void advertisesReceivePackRefsAndCapabilities() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef(
                "refs/heads/main",
                NULL_ID,
                MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitV1Advertisement advertisement =
                service.legacyReceivePackAdvertisement(
                        receiveRequest("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(MAIN_ID, "HEAD"),
                GitAdvertisedRef.direct(
                        MAIN_ID,
                        "refs/heads/main"));
        assertThat(advertisement.capabilities())
                .extracting(capability -> capability.wireToken())
                .containsExactly(
                        "report-status",
                        "side-band-64k",
                        "ofs-delta",
                        "object-format=sha1",
                        "agent=orion-native");
    }

    @Test
    void advertisesEmptyReceivePackRepositoryWithPseudoRef() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitV1Advertisement advertisement =
                service.legacyReceivePackAdvertisement(
                        receiveRequest("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(
                        NULL_ID,
                        "capabilities^{}"));
    }

    @Test
    void fetchesPackFromRepositoryNamedByInitialRequest() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        NativePackProducer producer = service.legacyUploadPack(
                request("/demo.git"),
                new NativeFetchRequest(
                        Set.of(),
                        Set.of(),
                        true,
                        false,
                        false));

        ByteBuf pack = Unpooled.buffer();
        try (producer) {
            assertThat(producer.produce(pack))
                    .isEqualTo(
                            NativePackProducer.Result.COMPLETED);
            assertThat(provider.exists("demo.git")).isTrue();
            assertThat(pack.readCharSequence(
                    4,
                    java.nio.charset.StandardCharsets.US_ASCII))
                    .hasToString("PACK");
        } finally {
            pack.release();
        }
    }

    @Test
    void listsMatchingBranchesAndLightweightTagsInLexicographicOrder() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId lightweightTagId = repository.writeObject(
                ObjectType.COMMIT,
                "tag target".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/tags/v1",
                NULL_ID,
                lightweightTagId.value());
        repository.updateRef("refs/heads/topic", NULL_ID, TAG_ID);
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/")));

        assertThat(response.refs()).containsExactly(
                direct(MAIN_ID, "refs/heads/main"),
                direct(TAG_ID, "refs/heads/topic"),
                direct(lightweightTagId.value(), "refs/tags/v1"));
    }

    @Test
    void doesNotDuplicateRefsMatchedByOverlappingPrefixes() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        false,
                        false,
                        false,
                        List.of("refs/", "refs/heads/")));

        assertThat(response.refs()).containsExactly(
                direct(MAIN_ID, "refs/heads/main"));
    }

    @Test
    void returnsEmptyResponseWhenNoRefsMatch() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        false,
                        false,
                        false,
                        List.of("refs/tags/")));

        assertThat(response.refs()).isEmpty();
    }

    @Test
    void listsResolvedHeadWithoutSymrefTargetWhenNotRequested() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        false,
                        false,
                        false,
                        List.of("HEAD")));

        assertThat(response.refs()).containsExactly(
                direct(MAIN_ID, "HEAD"));
    }

    @Test
    void listsResolvedHeadWithSymrefTargetWhenRequested() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        false,
                        true,
                        false,
                        List.of("HEAD")));

        assertThat(response.refs()).containsExactly(
                direct(
                        MAIN_ID,
                        "HEAD",
                        Optional.of("refs/heads/main"),
                        Optional.empty()));
    }

    @Test
    void listsOnlySynthesizedHeadWhenSnapshotContainsStoredHead() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        repository.updateRef("HEAD", NULL_ID, TAG_ID);
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        false,
                        true,
                        false,
                        List.of("HEAD")));

        assertThat(response.refs()).containsExactly(
                direct(
                        MAIN_ID,
                        "HEAD",
                        Optional.of("refs/heads/main"),
                        Optional.empty()));
    }

    @Test
    void listsUnbornHeadWhenRequested() {
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(
                        new InMemoryNativeGitRepositoryProvider());

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        false,
                        true,
                        true,
                        List.of("HEAD")));

        assertThat(response.refs()).containsExactly(
                new GitLsRefsResponse.UnbornRef(
                        "HEAD",
                        "refs/heads/main"));
    }

    @Test
    void peelsNestedAnnotatedTagToFinalNonTagObject() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(
                ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        GitObjectId innerTagId = repository.writeObject(
                ObjectType.TAG,
                tagData(commitId.value()));
        GitObjectId outerTagId = repository.writeObject(
                ObjectType.TAG,
                tagData(innerTagId.value()));
        repository.updateRef(
                "refs/tags/nested",
                NULL_ID,
                outerTagId.value());
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(
                direct(
                        outerTagId.value(),
                        "refs/tags/nested",
                        Optional.empty(),
                        Optional.of(commitId.value())));
    }

    @Test
    void peelsAnnotatedTagWithLargeBodyFromBoundedPrefix() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(
                ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        byte[] objectLine = ("object " + commitId.value() + "\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] tagData = new byte[1024 * 1024 + objectLine.length];
        Arrays.fill(tagData, (byte) 'x');
        System.arraycopy(objectLine, 0, tagData, 0, objectLine.length);
        GitObjectId tagId = repository.writeObject(
                ObjectType.TAG,
                tagData);
        repository.updateRef(
                "refs/tags/large",
                NULL_ID,
                tagId.value());
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        Optional<LooseObjectPrefix> prefix =
                repository.readObjectPrefix(tagId, 48);
        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/tags/large")));

        assertThat(prefix).isPresent();
        assertThat(prefix.get().dataPrefix())
                .hasSize(48)
                .isEqualTo(objectLine);
        assertThat(response.refs()).containsExactly(
                direct(
                        tagId.value(),
                        "refs/tags/large",
                        Optional.empty(),
                        Optional.of(commitId.value())));
    }

    @Test
    void omitsPeeledAttributeForMalformedAnnotatedTag() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId malformedTagId = repository.writeObject(
                ObjectType.TAG,
                "object not-a-hex-object-id\n"
                        .getBytes(StandardCharsets.US_ASCII));
        repository.updateRef(
                "refs/tags/malformed",
                NULL_ID,
                malformedTagId.value());
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(
                direct(
                        malformedTagId.value(),
                        "refs/tags/malformed"));
    }

    @Test
    void omitsPeeledAttributeWhenAnnotatedTagTargetIsMissing() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId tagId = repository.writeObject(
                ObjectType.TAG,
                tagData("f".repeat(40)));
        repository.updateRef(
                "refs/tags/missing-target",
                NULL_ID,
                tagId.value());
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(
                direct(
                        tagId.value(),
                        "refs/tags/missing-target"));
    }

    @Test
    void memoizesSharedTagChainsAcrossMatchingRefs() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(
                ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        List<GitObjectId> tagIds = new ArrayList<>();
        GitObjectId targetId = commitId;
        int chainLength = 180;
        for (int i = 0; i < chainLength; i++) {
            GitObjectId tagId = repository.writeObject(
                    ObjectType.TAG,
                    tagData(targetId.value()));
            tagIds.add(tagId);
            targetId = tagId;
            repository.updateRef(
                    "refs/tags/shared-%03d".formatted(i),
                    NULL_ID,
                    tagId.value());
        }
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/tags/shared-")));

        List<GitLsRefsResponse.DirectRef> expected = new ArrayList<>();
        for (int i = 0; i < chainLength; i++) {
            expected.add(direct(
                    tagIds.get(i).value(),
                    "refs/tags/shared-%03d".formatted(i),
                    Optional.empty(),
                    Optional.of(commitId.value())));
        }
        assertThat(response.refs()).containsExactlyElementsOf(expected);
    }

    @Test
    void distinguishesCachedLightweightTagFromAnnotatedTagTarget() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(
                ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        GitObjectId annotatedTagId = repository.writeObject(
                ObjectType.TAG,
                tagData(commitId.value()));
        repository.updateRef(
                "refs/tags/a-lightweight",
                NULL_ID,
                commitId.value());
        repository.updateRef(
                "refs/tags/z-annotated",
                NULL_ID,
                annotatedTagId.value());
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(
                direct(
                        commitId.value(),
                        "refs/tags/a-lightweight"),
                direct(
                        annotatedTagId.value(),
                        "refs/tags/z-annotated",
                        Optional.empty(),
                        Optional.of(commitId.value())));
    }

    @Test
    void omitsPeeledAttributeWhenTagChainExceedsDepthLimit() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository =
                provider.findOrCreate("demo.git")
                        .valueOrFailure("repository");
        GitObjectId targetId = repository.writeObject(
                ObjectType.COMMIT,
                "commit".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0;
                i <= GitNativeRepositoryService.MAX_TAG_TRAVERSAL_DEPTH;
                i++) {
            targetId = repository.writeObject(
                    ObjectType.TAG,
                    tagData(targetId.value()));
        }
        repository.updateRef(
                "refs/tags/too-deep",
                NULL_ID,
                targetId.value());
        GitNativeRepositoryService service =
                new GitNativeRepositoryService(provider);

        GitLsRefsResponse response = service.lsRefs(
                request("/demo.git"),
                new LsRefsRequest(
                        true,
                        false,
                        false,
                        List.of("refs/tags/too-deep")));

        assertThat(response.refs()).containsExactly(
                direct(targetId.value(), "refs/tags/too-deep"));
    }

    private static byte[] tagData(String targetId) {
        return ("object " + targetId + "\n"
                + "type tag\n"
                + "tag nested\n\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static GitLsRefsResponse.DirectRef direct(
            String objectId,
            String name) {
        return direct(
                objectId,
                name,
                Optional.empty(),
                Optional.empty());
    }

    private static GitLsRefsResponse.DirectRef direct(
            String objectId,
            String name,
            Optional<String> symrefTarget,
            Optional<String> peeledObjectId) {
        return new GitLsRefsResponse.DirectRef(
                objectId,
                name,
                symrefTarget,
                peeledObjectId);
    }

    private static InitialRequestData request(String path) {
        return new InitialRequestData(
                InitialRequestService.UPLOAD_PACK,
                path,
                "localhost",
                Map.of());
    }

    private static InitialRequestData receiveRequest(String path) {
        return new InitialRequestData(
                InitialRequestService.RECEIVE_PACK,
                path,
                "localhost",
                Map.of());
    }
}
