package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.PackIngestionLimits;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.nativestorage.pack.PackIngestor;
import pro.deta.orion.git.nativestorage.pack.PackPublicationRequest;
import pro.deta.orion.git.nativestorage.pack.PackPublicationStore;
import pro.deta.orion.git.nativestorage.pack.PublishedPack;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.GitUploadPackException;
import pro.deta.orion.git.nativestorage.upload.NativeFetchOptions;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUri;
import pro.deta.orion.git.nativestorage.upload.NativePackfileUriSelection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "1".repeat(40);
    private static final PackIngestionLimits LIMITS =
            new PackIngestionLimits(1024 * 1024, 100, 1024 * 1024);

    @Test
    void exposesConfiguredIdentityAndEmptyRefSnapshot() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        assertThat(repository.name()).isEqualTo("demo.git");
        assertThat(repository.defaultHead()).isEqualTo("refs/heads/main");
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void exposesUpdatedRefsThroughFreshSnapshots() {
        LooseRefStore refs = new LooseRefStore();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                new LooseObjectStore(),
                "refs/heads/main");

        refs.update("refs/heads/main", NULL_ID, MAIN_ID);

        assertThat(repository.refs())
                .containsExactlyEntriesOf(
                        java.util.Map.of("refs/heads/main", MAIN_ID));
    }

    @Test
    void publishesQuarantinedObjectsWhenRefUpdatesApply() {
        LooseObjectStore publishedObjects = new LooseObjectStore();
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId blob = quarantine.write(
                ObjectType.BLOB,
                "published".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                publishedObjects,
                "refs/heads/main");

        List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                quarantine,
                List.of(new LooseRefStore.Update(
                        "refs/heads/main",
                        NULL_ID,
                        blob.value())));

        assertThat(results).containsExactly(RefUpdateResult.CREATED);
        assertThat(repository.refs())
                .containsEntry("refs/heads/main", blob.value());
        assertThat(repository.readObject(blob)).isPresent();
    }

    @Test
    void reportsDirectAndReceivePackRefUpdatesAfterPublication() {
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId first = quarantine.write(ObjectType.BLOB, "first".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");
        List<NativeGitRepository.RefUpdate> updates = new ArrayList<>();
        repository.onRefUpdate(updates::add);

        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        repository.publishObjectsAndRefs(
                quarantine,
                List.of(new LooseRefStore.Update(
                        "refs/heads/configuration",
                        NULL_ID,
                        first.value())));

        assertThat(updates)
                .extracting(NativeGitRepository.RefUpdate::refName)
                .containsExactly("refs/heads/main", "refs/heads/configuration");
    }

    @Test
    void doesNotReportRejectedAtomicRefUpdates() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        List<NativeGitRepository.RefUpdate> updates = new ArrayList<>();
        repository.onRefUpdate(updates::add);

        repository.publishObjectsAndRefs(
                new LooseObjectStore(),
                List.of(
                        new LooseRefStore.Update("refs/heads/topic", NULL_ID, "2".repeat(40)),
                        new LooseRefStore.Update("refs/heads/main", "3".repeat(40), "4".repeat(40))),
                true);

        assertThat(updates).isEmpty();
        assertThat(repository.refs()).doesNotContainKey("refs/heads/topic");
    }

    @Test
    void validatesObjectClosureAcrossQuarantineAndPublishedObjects() {
        LooseObjectStore publishedObjects = new LooseObjectStore();
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId tree = publishedObjects.write(
                ObjectType.TREE,
                new byte[0]);
        GitObjectId commit = writeCommit(
                quarantine,
                tree,
                null,
                "quarantined commit");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                publishedObjects,
                "refs/heads/main");

        assertThat(repository.hasCompleteObjectClosure(commit, quarantine))
                .isTrue();
    }

    @Test
    void rejectsObjectClosureWithMissingReferencedObject() {
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId missingTree = GitObjectId.of("f".repeat(40));
        GitObjectId commit = writeCommit(
                quarantine,
                missingTree,
                null,
                "incomplete commit");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        assertThat(repository.hasCompleteObjectClosure(commit, quarantine))
                .isFalse();
    }

    @Test
    void buildsPackWithoutObjectsReachableFromHaves() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId wanted = objects.write(
                ObjectType.BLOB,
                "wanted".getBytes(StandardCharsets.UTF_8));
        GitObjectId have = objects.write(
                ObjectType.BLOB,
                "already present".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        CompositeByteBuf pack = produce(repository.fetch(
                new NativeFetchRequest(
                        Set.of(wanted, have),
                        Set.of(have),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(true, true, false))));

        try {
            assertThat(pack.getCharSequence(
                    0,
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("PACK");
            assertThat(pack.getInt(8)).isEqualTo(1);
        } finally {
            pack.release();
        }
    }

    @Test
    void includesAnnotatedTagWhoseTargetIsSent() {
        LooseRefStore refs = new LooseRefStore();
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "tagged".getBytes(StandardCharsets.UTF_8));
        GitObjectId tag = objects.write(
                ObjectType.TAG,
                ("object " + blob + "\n"
                        + "type blob\n"
                        + "tag v1\n"
                        + "tagger Test <test@example.com> 0 +0000\n"
                        + "\nmessage\n")
                        .getBytes(StandardCharsets.UTF_8));
        refs.update("refs/tags/v1", NULL_ID, tag.value());
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                objects,
                "refs/heads/main");

        CompositeByteBuf pack = produce(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blob),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(false, false, true))));

        try {
            assertThat(pack.getInt(8)).isEqualTo(2);
        } finally {
            pack.release();
        }
    }

    @Test
    void filteredFetchBuildsPackWithoutTreeReachedBlobs() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "filtered".getBytes(StandardCharsets.UTF_8));
        GitObjectId tree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", blob));
        GitObjectId commit = writeCommit(objects, tree, null, "filtered");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(commit),
                        Set.of(),
                        true,
                        Set.of(),
                        new NativeFetchOptions(
                                false,
                                false,
                                false,
                                false,
                                NativeObjectFilter.BLOB_NONE,
                                Set.of())));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(pack.getInt(8)).isEqualTo(2);
        } finally {
            pack.release();
        }
    }

    @Test
    void fetchResponseAdvertisesAcceptedPackfileUriAndOmitsCoveredObjects() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "covered".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");
        NativePackfileUri uri = new NativePackfileUri(
                "a".repeat(40),
                "https://git.example/r/demo.git/objects/pack/"
                        + "a".repeat(40)
                        + ".pack");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(blob),
                        Set.of(),
                        true,
                        Set.of(),
                        new NativeFetchOptions(
                                false,
                                false,
                                false,
                                false,
                                NativeObjectFilter.NONE,
                                Set.of("https"))),
                (objectIds, protocols) ->
                        new NativePackfileUriSelection(
                                List.of(uri),
                                Set.of(blob)));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(response.packfileUris()).containsExactly(uri);
            assertThat(pack.getInt(8)).isZero();
        } finally {
            pack.release();
        }
    }

    @Test
    void fetchResponseDoesNotUsePackfileUriSourceWithoutClientProtocols() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "inline".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(blob),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(false, false, false)),
                (objectIds, protocols) ->
                        new NativePackfileUriSelection(
                                List.of(new NativePackfileUri(
                                        "b".repeat(40),
                                        "https://git.example/pack")),
                                Set.of(blob)));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(response.packfileUris()).isEmpty();
            assertThat(pack.getInt(8)).isEqualTo(1);
        } finally {
            pack.release();
        }
    }

    @Test
    void fetchBuildsServerPackInsteadOfReturningReceivedPackBytes() {
        LooseRefStore refs = new LooseRefStore();
        LooseObjectStore objects = new LooseObjectStore();
        RecordingPackPublicationStore publicationStore =
                new RecordingPackPublicationStore();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                objects,
                "refs/heads/main",
                publicationStore);
        PackBlob first = blob("first from client".getBytes(StandardCharsets.UTF_8));
        PackBlob second = blob("second from client".getBytes(StandardCharsets.UTF_8));
        List<PackBlob> incomingOrder = new ArrayList<>(List.of(first, second));
        incomingOrder.sort((left, right) ->
                right.objectId().value().compareTo(left.objectId().value()));
        byte[] receivedPack = pack(incomingOrder);
        PackIngestionResult.Complete complete =
                complete(accept(repository.beginPackIngestion(LIMITS), receivedPack));
        repository.publishObjectsAndRefs(
                complete.quarantine(),
                List.of(new LooseRefStore.Update(
                        "refs/heads/main",
                        NULL_ID,
                        first.objectId().value())));

        CompositeByteBuf fetchPack = produce(repository.fetch(
                new NativeFetchRequest(
                        Set.of(first.objectId(), second.objectId()),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(true, true, false))));

        try {
            byte[] generatedPack = ByteBufUtil.getBytes(fetchPack);
            LooseObjectStore fetchedObjects = ingest(generatedPack);

            assertThat(publicationStore.packBytes()).isEqualTo(receivedPack);
            assertThat(generatedPack).isNotEqualTo(receivedPack);
            assertThat(fetchedObjects.contains(first.objectId())).isTrue();
            assertThat(fetchedObjects.contains(second.objectId())).isTrue();
        } finally {
            fetchPack.release();
        }
    }

    @Test
    void fetchUsesNonThinDeltasOnlyWhenOfsDeltaIsNegotiated() {
        LooseObjectStore objects = new LooseObjectStore();
        SimilarBlobs blobs = similarBlobs(objects);
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        byte[] thinOnlyPack = produceBytes(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blobs.first(), blobs.second()),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(true, false, false))));
        byte[] deltaPack = produceBytes(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blobs.first(), blobs.second()),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(false, true, false))));

        assertThat(packEntryTypes(thinOnlyPack)).doesNotContain(7);
        assertThat(packEntryTypes(deltaPack)).contains(7);
        assertThat(ingest(deltaPack).contains(blobs.first())).isTrue();
        assertThat(ingest(deltaPack).contains(blobs.second())).isTrue();
    }

    @Test
    void fetchUsesThinDeltaWhenBaseIsReachableFromHave() {
        LooseObjectStore objects = new LooseObjectStore();
        SimilarBlobs blobs = similarBlobs(objects);
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        byte[] thinPack = produceBytes(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blobs.second()),
                        Set.of(blobs.first()),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(true, true, false))));

        assertThat(packEntryTypes(thinPack)).containsExactly(7);
        assertThat(refDeltaBaseIds(thinPack))
                .containsExactly(blobs.first());
        assertThat(ingest(thinPack, objects).contains(blobs.second()))
                .isTrue();
    }

    @Test
    void fetchFallsBackWhenThinPackWasNotNegotiated() {
        LooseObjectStore objects = new LooseObjectStore();
        SimilarBlobs blobs = similarBlobs(objects);
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        byte[] pack = produceBytes(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blobs.second()),
                        Set.of(blobs.first()),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(false, true, false))));

        assertThat(packEntryTypes(pack))
                .containsExactly(ObjectType.BLOB.packTypeId());
    }

    @Test
    void fetchFallsBackWhenThinBaseIsMissing() {
        LooseObjectStore objects = new LooseObjectStore();
        SimilarBlobs blobs = similarBlobs(objects);
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        byte[] pack = produceBytes(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blobs.second()),
                        Set.of(GitObjectId.of("a".repeat(40))),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(true, true, false))));

        assertThat(packEntryTypes(pack))
                .containsExactly(ObjectType.BLOB.packTypeId());
    }

    @Test
    void resolvesWantedRefsIntoPackAndResponseMetadata() {
        LooseRefStore refs = new LooseRefStore();
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "wanted".getBytes(StandardCharsets.UTF_8));
        refs.update("refs/heads/main", NULL_ID, blob.value());
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(),
                        Set.of(),
                        true,
                        Set.of("refs/heads/main"),
                        NativeFetchOptions.DEFAULT));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(response.wantedRefs())
                    .containsExactlyEntriesOf(
                            java.util.Map.of("refs/heads/main", blob));
            assertThat(pack.getInt(8)).isEqualTo(1);
        } finally {
            pack.release();
        }
    }

    @Test
    void repositorySavesFilesToNewBranchAndLoadsThemBack() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        repository.saveFiles(
                "main",
                Map.of("orion.xml", "initial acl".getBytes(StandardCharsets.UTF_8)),
                "initial acl",
                GitCommitAuthor.EMPTY);

        GitRepositoryFileSnapshot snapshot =
                repository.loadFiles("main", List.of("orion.xml"));
        assertThat(snapshot.files())
                .containsEntry(
                        "orion.xml",
                        "initial acl".getBytes(StandardCharsets.UTF_8));
        assertThat(repository.refs())
                .containsKey("refs/heads/main");
    }

    @Test
    void repositorySavesFilesOverExistingBranchContent() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        repository.saveFiles(
                "main",
                Map.of(
                        "orion.xml",
                        "initial acl".getBytes(StandardCharsets.UTF_8),
                        "nested/acl.xml",
                        "nested acl".getBytes(StandardCharsets.UTF_8)),
                "initial acl",
                GitCommitAuthor.EMPTY);

        repository.saveFiles(
                "main",
                Map.of("orion.xml", "updated acl".getBytes(StandardCharsets.UTF_8)),
                "updated acl",
                GitCommitAuthor.EMPTY);

        GitRepositoryFileSnapshot snapshot =
                repository.loadFiles("main", List.of("orion.xml", "nested/acl.xml"));
        assertThat(snapshot.files())
                .containsEntry(
                        "orion.xml",
                        "updated acl".getBytes(StandardCharsets.UTF_8))
                .containsEntry(
                        "nested/acl.xml",
                        "nested acl".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void repositoryPopulatesDefaultHeadWhenSavingDifferentFirstBranch() throws Exception {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        repository.saveFiles(
                "master",
                Map.of("orion.xml", "initial acl".getBytes(StandardCharsets.UTF_8)),
                "initial acl",
                GitCommitAuthor.EMPTY);

        assertThat(repository.refs().get("refs/heads/main"))
                .isEqualTo(repository.refs().get("refs/heads/master"));
    }

    @Test
    void rejectsMissingWantedRefBeforePackBuild() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        assertThatThrownBy(() -> repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(),
                        Set.of(),
                        true,
                        Set.of("refs/heads/missing"),
                        NativeFetchOptions.DEFAULT)))
                .isInstanceOfSatisfying(
                        GitUploadPackException.class,
                        error -> assertThat(error.kind())
                                .isEqualTo(
                                        GitUploadPackException.Kind
                                                .MISSING_REF));
    }

    private static CompositeByteBuf produce(
            NativePackProducer producer) {
        CompositeByteBuf complete = Unpooled.compositeBuffer();
        try (producer) {
            while (true) {
                ByteBuf fragment = Unpooled.buffer(3, 3);
                NativePackProducer.Result result;
                try {
                    result = producer.produce(fragment);
                    complete.addComponent(
                            true,
                            fragment.retain());
                } finally {
                    fragment.release();
                }
                if (result == NativePackProducer.Result.COMPLETED) {
                    return complete;
                }
            }
        } catch (RuntimeException error) {
            complete.release();
            throw error;
        }
    }

    private static byte[] produceBytes(NativePackProducer producer) {
        CompositeByteBuf pack = produce(producer);
        try {
            return ByteBufUtil.getBytes(pack);
        } finally {
            pack.release();
        }
    }

    private static PackIngestionResult accept(
            PackIngestionSession session,
            byte[] bytes) {
        ByteBuf input = Unpooled.wrappedBuffer(bytes);
        try {
            PackIngestionResult result = session.accept(input);
            if (result instanceof PackIngestionResult.NeedInput) {
                return session.endOfInput();
            }
            return result;
        } finally {
            input.release();
        }
    }

    private static PackIngestionResult.Complete complete(
            PackIngestionResult result) {
        assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
        return (PackIngestionResult.Complete) result;
    }

    private static LooseObjectStore ingest(byte[] pack) {
        ByteBuf input = Unpooled.wrappedBuffer(pack);
        try {
            return new PackIngestor(pack.length).ingest(input);
        } finally {
            input.release();
        }
    }

    private static LooseObjectStore ingest(
            byte[] pack,
            LooseObjectStore publishedObjects) {
        ByteBuf input = Unpooled.wrappedBuffer(pack);
        try {
            return new PackIngestor(pack.length).ingest(
                    input,
                    publishedObjects);
        } finally {
            input.release();
        }
    }

    private static GitObjectId writeCommit(
            LooseObjectStore objects,
            GitObjectId tree,
            GitObjectId parent,
            String message) {
        StringBuilder data = new StringBuilder()
                .append("tree ")
                .append(tree)
                .append('\n');
        if (parent != null) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> 0 +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return objects.write(
                ObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(
            String mode,
            String name,
            GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0")
                .getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.value()));
        return output.toByteArray();
    }

    private static PackBlob blob(byte[] data) {
        return new PackBlob(blobId(data), data);
    }

    private static SimilarBlobs similarBlobs(LooseObjectStore objects) {
        byte[] firstData = ("shared prefix\n".repeat(80)
                + "first line\n"
                + "shared suffix\n".repeat(80))
                .getBytes(StandardCharsets.UTF_8);
        byte[] secondData = ("shared prefix\n".repeat(80)
                + "second line updated\n"
                + "shared suffix\n".repeat(80))
                .getBytes(StandardCharsets.UTF_8);
        return new SimilarBlobs(
                objects.write(ObjectType.BLOB, firstData),
                objects.write(ObjectType.BLOB, secondData));
    }

    private static List<Integer> packEntryTypes(byte[] pack) {
        int count = intAt(pack, 8);
        int offset = 12;
        List<Integer> types = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            EntryHeader header = readEntryHeader(pack, offset);
            types.add(header.typeId());
            offset = header.nextOffset();
            if (header.typeId() == 7) {
                offset += 20;
            }
            offset = skipDeflated(pack, offset, pack.length - 20);
        }
        return types;
    }

    private static List<GitObjectId> refDeltaBaseIds(byte[] pack) {
        int count = intAt(pack, 8);
        int offset = 12;
        List<GitObjectId> baseIds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            EntryHeader header = readEntryHeader(pack, offset);
            offset = header.nextOffset();
            if (header.typeId() == 7) {
                byte[] baseId = new byte[20];
                System.arraycopy(pack, offset, baseId, 0, baseId.length);
                baseIds.add(GitObjectId.of(HexFormat.of().formatHex(baseId)));
                offset += baseId.length;
            }
            offset = skipDeflated(pack, offset, pack.length - 20);
        }
        return baseIds;
    }

    private static EntryHeader readEntryHeader(byte[] pack, int offset) {
        int currentOffset = offset;
        int current = pack[currentOffset++] & 0xff;
        int typeId = (current >>> 4) & 0x07;
        while ((current & 0x80) != 0) {
            current = pack[currentOffset++] & 0xff;
        }
        return new EntryHeader(typeId, currentOffset);
    }

    private static int skipDeflated(
            byte[] pack,
            int offset,
            int end) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(pack, offset, end - offset);
            byte[] scratch = new byte[1024];
            while (!inflater.finished()) {
                int produced = inflater.inflate(scratch);
                if (produced == 0) {
                    if (inflater.needsInput()) {
                        throw new IllegalStateException(
                                "Deflated pack entry is truncated");
                    }
                    if (inflater.needsDictionary()) {
                        throw new IllegalStateException(
                                "Deflated pack entry needs a dictionary");
                    }
                    throw new IllegalStateException(
                            "Deflated pack entry made no progress");
                }
            }
            return end - inflater.getRemaining();
        } catch (DataFormatException error) {
            throw new IllegalStateException(
                    "Invalid deflated pack entry",
                    error);
        } finally {
            inflater.end();
        }
    }

    private static GitObjectId blobId(byte[] data) {
        byte[] header = ("blob " + data.length + "\0")
                .getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = sha1();
        digest.update(header);
        digest.update(data);
        return GitObjectId.of(HexFormat.of().formatHex(digest.digest()));
    }

    private static byte[] pack(List<PackBlob> objects) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt(body, 0x5041434b);
            writeInt(body, 2);
            writeInt(body, objects.size());
            for (PackBlob object : objects) {
                writeBlobObject(body, object.data());
            }
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream pack = new ByteArrayOutputStream();
            pack.writeBytes(bodyBytes);
            pack.writeBytes(sha1(bodyBytes));
            return pack.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void writeBlobObject(
            ByteArrayOutputStream output,
            byte[] data) throws IOException {
        int size = data.length;
        int first = (ObjectType.BLOB.packTypeId() << 4) | (size & 0x0f);
        size >>>= 4;
        if (size != 0) {
            first |= 0x80;
        }
        output.write(first);
        while (size != 0) {
            int next = size & 0x7f;
            size >>>= 7;
            if (size != 0) {
                next |= 0x80;
            }
            output.write(next);
        }
        try (DeflaterOutputStream deflater =
                     new DeflaterOutputStream(output)) {
            deflater.write(data);
        }
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    private static byte[] sha1(byte[] bytes) {
        return sha1().digest(bytes);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 not available", error);
        }
    }

    private static int intAt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | (bytes[offset + 3] & 0xff);
    }

    private record SimilarBlobs(
            GitObjectId first,
            GitObjectId second) {
    }

    private record EntryHeader(
            int typeId,
            int nextOffset) {
    }

    private record PackBlob(GitObjectId objectId, byte[] data) {
        private PackBlob {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private static final class RecordingPackPublicationStore
            implements PackPublicationStore {
        private byte[] packBytes;

        @Override
        public Optional<PublishedPack> publish(
                PackPublicationRequest request) {
            packBytes = request.packBytes();
            return Optional.of(new PublishedPack(
                    request.packId(),
                    request.packBytes().length,
                    request.objectCount(),
                    request.packId(),
                    request.indexId()));
        }

        private byte[] packBytes() {
            return packBytes.clone();
        }
    }
}
