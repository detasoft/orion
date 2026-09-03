package pro.deta.orion.transport.git;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.object.LooseObjectPrefix;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.NativeFetchOptions;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.pack.NoDeltaPackBuilder;
import pro.deta.orion.git.nativestorage.pack.PackIngestionResult;
import pro.deta.orion.git.nativestorage.pack.PackIngestionSession;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryService;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.NativePackfileUriSourceFactory;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitLsRefsResponse;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.wire.capability.GitCapability;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestService;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceiveCommand;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceiveCommandSection;
import pro.deta.orion.git.parser.wire.exchange.LegacyReceivePack;
import pro.deta.orion.git.parser.wire.exchange.LsRefsRequest;
import pro.deta.orion.util.Result;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultGitNativeRepositoryServiceTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "1".repeat(40);
    private static final String TAG_ID = "2".repeat(40);

    @Test
    void uploadPackAdvertisementFailsWhenRepositoryIsMissing() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        assertThatThrownBy(() -> legacyUploadPackAdvertisement(service, request("/demo.git")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Native repository does not exist: /demo.git");

        assertThat(provider.exists("/demo.git")).isFalse();
    }

    @Test
    void opensRepositoryForLogicalReadBeforeUploadAdvertisement() {
        RecordingProvider provider = new RecordingProvider(providerWithMainRef());
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        legacyUploadPackAdvertisement(service, request("/demo.git"));

        assertThat(provider.readCalls).isEqualTo(1);
    }

    @Test
    void advertisesRefsFromFileBackedRepositoryProvider(@TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider firstProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = firstProvider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        FileNativeGitRepositoryProvider secondProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(secondProvider);

        GitV1Advertisement advertisement = legacyUploadPackAdvertisement(service, request("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(MAIN_ID, "HEAD"), GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"));
    }

    @Test
    void advertisesHeadFirstAndSortsRepositoryRefs() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/tags/v1", NULL_ID, TAG_ID);
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyUploadPackAdvertisement(service, request("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(MAIN_ID, "HEAD"), GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"), GitAdvertisedRef.direct(TAG_ID, "refs/tags/v1"));
        assertThat(advertisement.capabilities())
                .extracting(capability -> capability.wireToken())
                .containsExactly(
                        "multi_ack_detailed",
                        "multi_ack",
                        "thin-pack",
                        "side-band-64k",
                        "ofs-delta",
                        "shallow",
                        "deepen-since",
                        "deepen-not",
                        "deepen-relative",
                        "no-progress",
                        "include-tag",
                        "agent=orion-native",
                        "symref=HEAD:refs/heads/main");
    }

    @Test
    void advertisesHeadFromExistingBranchWhenDefaultHeadTargetIsMissing() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/master", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyUploadPackAdvertisement(service, request("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(MAIN_ID, "HEAD"),
                GitAdvertisedRef.direct(MAIN_ID, "refs/heads/master"));
        assertThat(capabilityTokens(advertisement))
                .contains("symref=HEAD:refs/heads/master");
    }

    @Test
    void omitsEachDisabledUploadPackCapabilityWithoutReorderingOthers() {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        List<UploadCapabilityCase> cases = List.of(
                new UploadCapabilityCase(
                        uploadConfiguration(false, true, true, true, true, true),
                        List.of(
                                "thin-pack",
                                "side-band-64k",
                                "ofs-delta",
                                "shallow",
                                "deepen-since",
                                "deepen-not",
                                "deepen-relative",
                                "no-progress",
                                "include-tag",
                                "agent=orion-native",
                                "symref=HEAD:refs/heads/main")),
                new UploadCapabilityCase(
                        uploadConfiguration(true, false, true, true, true, true),
                        List.of(
                                "multi_ack_detailed",
                                "multi_ack",
                                "side-band-64k",
                                "ofs-delta",
                                "shallow",
                                "deepen-since",
                                "deepen-not",
                                "deepen-relative",
                                "no-progress",
                                "include-tag",
                                "agent=orion-native",
                                "symref=HEAD:refs/heads/main")),
                new UploadCapabilityCase(
                        uploadConfiguration(true, true, false, true, true, true),
                        List.of(
                                "multi_ack_detailed",
                                "multi_ack",
                                "thin-pack",
                                "ofs-delta",
                                "shallow",
                                "deepen-since",
                                "deepen-not",
                                "deepen-relative",
                                "no-progress",
                                "include-tag",
                                "agent=orion-native",
                                "symref=HEAD:refs/heads/main")),
                new UploadCapabilityCase(
                        uploadConfiguration(true, true, true, false, true, true),
                        List.of(
                                "multi_ack_detailed",
                                "multi_ack",
                                "thin-pack",
                                "side-band-64k",
                                "shallow",
                                "deepen-since",
                                "deepen-not",
                                "deepen-relative",
                                "no-progress",
                                "include-tag",
                                "agent=orion-native",
                                "symref=HEAD:refs/heads/main")),
                new UploadCapabilityCase(
                        uploadConfiguration(true, true, true, true, false, true),
                        List.of(
                                "multi_ack_detailed",
                                "multi_ack",
                                "thin-pack",
                                "side-band-64k",
                                "ofs-delta",
                                "shallow",
                                "deepen-since",
                                "deepen-not",
                                "deepen-relative",
                                "no-progress",
                                "include-tag",
                                "agent=orion-native")),
                new UploadCapabilityCase(
                        uploadConfiguration(true, true, true, true, true, false),
                        List.of(
                                "multi_ack_detailed",
                                "multi_ack",
                                "thin-pack",
                                "side-band-64k",
                                "ofs-delta",
                                "shallow",
                                "deepen-since",
                                "deepen-not",
                                "deepen-relative",
                                "no-progress",
                                "include-tag",
                                "symref=HEAD:refs/heads/main")));

        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        for (UploadCapabilityCase capabilityCase : cases) {
            GitV1Advertisement advertisement =
                    service.legacyUploadPackAdvertisement(
                            request("/demo.git"),
                            GitNativeRepositoryAccessHook.ALLOW_ALL,
                            capabilityCase.configuration());

            assertThat(capabilityTokens(advertisement)).containsExactlyElementsOf(capabilityCase.expectedTokens());
        }
    }

    @Test
    void advertisesReceivePackRefsAndCapabilities() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyReceivePackAdvertisement(service, receiveRequest("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(MAIN_ID, "HEAD"), GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"));
        assertThat(advertisement.capabilities())
                .extracting(capability -> capability.wireToken())
                .containsExactly(
                        "report-status",
                        "report-status-v2",
                        "delete-refs",
                        "side-band-64k",
                        "quiet",
                        "atomic",
                        "ofs-delta",
                        "object-format=sha1",
                        "agent=orion-native");
    }

    @Test
    void omitsEachDisabledReceivePackCapabilityWithoutReorderingOthers() {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        List<ReceiveCapabilityCase> cases = List.of(
                new ReceiveCapabilityCase(
                        receiveConfiguration(false, true, true, true, true),
                        List.of(
                                "delete-refs",
                                "side-band-64k",
                                "quiet",
                                "atomic",
                                "ofs-delta",
                                "object-format=sha1",
                                "agent=orion-native")),
                new ReceiveCapabilityCase(
                        receiveConfiguration(true, false, true, true, true),
                        List.of(
                                "report-status",
                                "report-status-v2",
                                "delete-refs",
                                "quiet",
                                "atomic",
                                "ofs-delta",
                                "object-format=sha1",
                                "agent=orion-native")),
                new ReceiveCapabilityCase(
                        receiveConfiguration(true, true, false, true, true),
                        List.of(
                                "report-status",
                                "report-status-v2",
                                "delete-refs",
                                "side-band-64k",
                                "quiet",
                                "atomic",
                                "object-format=sha1",
                                "agent=orion-native")),
                new ReceiveCapabilityCase(
                        receiveConfiguration(true, true, true, false, true),
                        List.of(
                                "report-status",
                                "report-status-v2",
                                "delete-refs",
                                "side-band-64k",
                                "quiet",
                                "atomic",
                                "ofs-delta",
                                "agent=orion-native")),
                new ReceiveCapabilityCase(
                        receiveConfiguration(true, true, true, true, false),
                        List.of(
                                "report-status",
                                "report-status-v2",
                                "delete-refs",
                                "side-band-64k",
                                "quiet",
                                "atomic",
                                "ofs-delta",
                                "object-format=sha1")));

        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        for (ReceiveCapabilityCase capabilityCase : cases) {
            GitV1Advertisement advertisement =
                    service.legacyReceivePackAdvertisement(
                            receiveRequest("/demo.git"),
                            GitNativeRepositoryAccessHook.ALLOW_ALL,
                            capabilityCase.configuration());

            assertThat(capabilityTokens(advertisement)).containsExactlyElementsOf(capabilityCase.expectedTokens());
        }
    }

    @Test
    void advertisesEmptyReceivePackRepositoryWithPseudoRef() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyReceivePackAdvertisement(service, receiveRequest("/demo.git"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(NULL_ID, "capabilities^{}"));
    }

    @Test
    void uploadChecksReadAccessBeforeRepositoryLookup() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository");
        RecordingAccessHook accessHook = new RecordingAccessHook();
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);

        service.legacyUploadPackAdvertisement(
                request("/demo.git"),
                accessHook,
                GitWireConfiguration.allSupported());

        assertThat(accessHook.calls()).containsExactly("read /demo.git");
    }

    @Test
    void uploadStopsBeforeRepositoryLookupWhenReadHookRejects() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository");
        RecordingAccessHook accessHook = new RecordingAccessHook();
        accessHook.rejectRead();
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);

        assertThatThrownBy(() -> service.legacyUploadPackAdvertisement(
                request("/demo.git"),
                accessHook,
                GitWireConfiguration.allSupported()))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("denied read /demo.git");

        assertThat(accessHook.calls()).containsExactly("read /demo.git");
    }

    @Test
    void receiveCreatesMissingRepositoryAfterReceiveAndCreateHooks() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        RecordingAccessHook accessHook = new RecordingAccessHook();
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);

        service.legacyReceivePackAdvertisement(
                receiveRequest("/demo.git"),
                accessHook,
                GitWireConfiguration.allSupported());

        assertThat(provider.exists("/demo.git")).isTrue();
        assertThat(accessHook.calls()).containsExactly(
                "receive /demo.git",
                "create /demo.git");
    }

    @Test
    void receiveFindsExistingRepositoryAfterReceiveAndWriteHooks() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository");
        RecordingAccessHook accessHook = new RecordingAccessHook();
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);

        service.legacyReceivePackAdvertisement(
                receiveRequest("/demo.git"),
                accessHook,
                GitWireConfiguration.allSupported());

        assertThat(accessHook.calls()).containsExactly(
                "receive /demo.git",
                "write /demo.git");
    }

    @Test
    void receiveStopsBeforeRepositoryLookupWhenReceiveHookRejects() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        RecordingAccessHook accessHook = new RecordingAccessHook();
        accessHook.rejectReceive();
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);

        assertThatThrownBy(() -> service.legacyReceivePackAdvertisement(
                receiveRequest("/demo.git"),
                accessHook,
                GitWireConfiguration.allSupported()))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("denied receive /demo.git");
        assertThat(provider.exists("/demo.git")).isFalse();
    }

    @Test
    void receiveAppliesValidNonAtomicRefsAfterPublishingIncomingObjects() {
        InMemoryNativeGitRepositoryProvider provider = providerWithMainRef();
        NativeGitRepository repository = provider.find("/demo.git")
                .valueOrFailure("repository");
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId feature = quarantine.write(
                ObjectType.BLOB,
                "feature".getBytes(StandardCharsets.US_ASCII));

        List<GitNativeRepositoryService.ReceivePackStatus> statuses =
                service.completeLegacyReceivePack(
                        receivePack(
                                service,
                                List.of(
                                        new LegacyReceiveCommand(
                                                GitObjectId.of(TAG_ID),
                                                GitObjectId.of(NULL_ID),
                                                "refs/heads/main"),
                                        new LegacyReceiveCommand(
                                                GitObjectId.of(NULL_ID),
                                                feature,
                                                "refs/heads/feature")),
                                Set.of(),
                                quarantine),
                        GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(statuses)
                .containsExactly(
                        new GitNativeRepositoryService.ReceivePackStatus(
                                "refs/heads/main", false, "stale"),
                        new GitNativeRepositoryService.ReceivePackStatus(
                                "refs/heads/feature", true, ""));
        assertThat(repository.refs())
                .containsEntry("refs/heads/main", MAIN_ID)
                .containsEntry("refs/heads/feature", feature.value());
        assertThat(repository.readObject(feature)).isPresent();
    }

    @Test
    void receivePublishesThroughRepositoryProvider() {
        InMemoryNativeGitRepositoryProvider backend = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = backend.create("/demo.git")
                .valueOrFailure("repository");
        RecordingProvider provider = new RecordingProvider(backend);
        provider.rejectPublication = true;
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId feature = quarantine.write(
                ObjectType.BLOB,
                "feature".getBytes(StandardCharsets.US_ASCII));

        List<GitNativeRepositoryService.ReceivePackStatus> statuses =
                service.completeLegacyReceivePack(
                        receivePack(
                                service,
                                List.of(new LegacyReceiveCommand(
                                        GitObjectId.of(NULL_ID),
                                        feature,
                                        "refs/heads/feature")),
                                Set.of(),
                                quarantine),
                        GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(provider.publishCalls).isEqualTo(1);
        assertThat(statuses).containsExactly(
                new GitNativeRepositoryService.ReceivePackStatus(
                        "refs/heads/feature", false, "stale"));
        assertThat(repository.refs()).isEmpty();
        assertThat(repository.readObject(feature)).isEmpty();
    }

    @Test
    void receiveRejectsUnauthorizedRefWithoutPublishingIt() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git")
                .valueOrFailure("repository");
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId feature = quarantine.write(
                ObjectType.BLOB,
                "feature".getBytes(StandardCharsets.US_ASCII));
        RecordingAccessHook accessHook = new RecordingAccessHook();
        accessHook.rejectUpdates();

        List<GitNativeRepositoryService.ReceivePackStatus> statuses =
                service.completeLegacyReceivePack(
                        receivePack(
                                service,
                                List.of(new LegacyReceiveCommand(
                                        GitObjectId.of(NULL_ID),
                                        feature,
                                        "refs/heads/feature")),
                                Set.of(),
                                quarantine),
                        accessHook);

        assertThat(statuses).containsExactly(
                new GitNativeRepositoryService.ReceivePackStatus(
                        "refs/heads/feature", false, "ACCESS_DENIED"));
        assertThat(repository.refs()).doesNotContainKey("refs/heads/feature");
        assertThat(accessHook.calls()).contains("update /demo.git refs/heads/feature false");
    }

    @Test
    void receiveIdentifiesNonFastForwardUpdateForAuthorization() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git")
                .valueOrFailure("repository");
        GitObjectId oldObject = repository.writeObject(
                ObjectType.BLOB,
                "old".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/heads/main", NULL_ID, oldObject.value());
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId newObject = quarantine.write(
                ObjectType.BLOB,
                "new".getBytes(StandardCharsets.US_ASCII));
        RecordingAccessHook accessHook = new RecordingAccessHook();

        List<GitNativeRepositoryService.ReceivePackStatus> statuses =
                service.completeLegacyReceivePack(
                        receivePack(
                                service,
                                List.of(new LegacyReceiveCommand(
                                        oldObject,
                                        newObject,
                                        "refs/heads/main")),
                                Set.of(),
                                quarantine),
                        accessHook);

        assertThat(statuses).containsExactly(
                new GitNativeRepositoryService.ReceivePackStatus(
                        "refs/heads/main", true, ""));
        assertThat(accessHook.calls()).contains("update /demo.git refs/heads/main true");
    }

    @Test
    void receiveKeepsPublishedPackWhenAtomicRefTransactionIsStale(
            @TempDir Path rootDirectory) {
        FileNativeGitRepositoryProvider provider =
                new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = provider.create("/demo.git")
                .valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        LooseObjectStore incoming = new LooseObjectStore();
        GitObjectId feature = incoming.write(
                ObjectType.BLOB,
                "feature".getBytes(StandardCharsets.US_ASCII));
        PackIngestionResult.Complete complete = ingestReceivePack(
                service,
                pack(incoming, feature));

        List<GitNativeRepositoryService.ReceivePackStatus> statuses =
                service.completeLegacyReceivePack(
                        receivePack(
                                service,
                                List.of(
                                        new LegacyReceiveCommand(
                                                GitObjectId.of(TAG_ID),
                                                GitObjectId.of(NULL_ID),
                                                "refs/heads/main"),
                                        new LegacyReceiveCommand(
                                                GitObjectId.of(NULL_ID),
                                                feature,
                                                "refs/heads/feature")),
                                Set.of(GitCapability.ATOMIC.name()),
                                complete.quarantine()),
                        GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(statuses)
                .containsExactly(
                        new GitNativeRepositoryService.ReceivePackStatus(
                                "refs/heads/main", false, "stale"),
                        new GitNativeRepositoryService.ReceivePackStatus(
                                "refs/heads/feature", false, "atomic-push-failure"));
        assertThat(repository.refs())
                .containsExactly(Map.entry("refs/heads/main", MAIN_ID));
        assertThat(complete.publishedPack()).isPresent();
        String packId = complete.publishedPack().orElseThrow().packId();
        assertThat(repository.publishedPacks())
                .extracting(manifest -> manifest.packId())
                .containsExactly(packId);
        try (var published = repository.openPublishedPack(packId).orElseThrow()) {
            assertThat(published.input().readAllBytes()).isNotEmpty();
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
        assertThat(repository.readObject(feature)).isPresent();
    }

    @Test
    void receiveDoesNotPublishRefWithIncompleteObjectClosure() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git")
                .valueOrFailure("repository");
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId missingTree = GitObjectId.of("f".repeat(40));
        GitObjectId incompleteCommit = quarantine.write(
                ObjectType.COMMIT,
                incompleteCommit(missingTree).getBytes(StandardCharsets.US_ASCII));

        List<GitNativeRepositoryService.ReceivePackStatus> statuses =
                service.completeLegacyReceivePack(
                        receivePack(
                                service,
                                List.of(new LegacyReceiveCommand(
                                        GitObjectId.of(NULL_ID),
                                        incompleteCommit,
                                        "refs/heads/main")),
                                Set.of(),
                                quarantine),
                        GitNativeRepositoryAccessHook.ALLOW_ALL);

        assertThat(statuses).containsExactly(
                new GitNativeRepositoryService.ReceivePackStatus(
                        "refs/heads/main", false, "missing-necessary-objects"));
        assertThat(repository.refs()).isEmpty();
        assertThat(repository.readObject(incompleteCommit)).isPresent();
        assertThat(repository.readObject(missingTree)).isEmpty();
    }

    @Test
    void fetchesPackFromRepositoryNamedByInitialRequest() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository");
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        NativePackProducer producer = legacyUploadPack(
                service,
                request("/demo.git"),
                new NativeFetchRequest(
                        Set.of(),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(false, false, false)));

        ByteBuf pack = Unpooled.buffer();
        try (producer) {
            assertThat(producer.produce(pack)).isEqualTo(NativePackProducer.Result.COMPLETED);
            assertThat(provider.exists("/demo.git")).isTrue();
            assertThat(pack.readCharSequence(4, java.nio.charset.StandardCharsets.US_ASCII)).hasToString("PACK");
        } finally {
            pack.release();
        }
    }

    @Test
    void fetchResolvesBranchesContainingEachWantedObject() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git")
                .valueOrFailure("repository");
        GitObjectId wanted = repository.writeObject(
                ObjectType.BLOB,
                "wanted".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/heads/main", NULL_ID, wanted.value());
        repository.updateRef("refs/heads/feature", NULL_ID, wanted.value());
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);
        RecordingAccessHook accessHook = new RecordingAccessHook();

        try (NativePackProducer ignored = service.legacyUploadPack(
                request("/demo.git"),
                new NativeFetchRequest(
                        Set.of(wanted),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(false, false, false)),
                accessHook)) {
            assertThat(accessHook.calls()).containsExactly(
                    "read /demo.git",
                    "fetch /demo.git [feature, main]");
        }
    }

    @Test
    void fetchReportsUnresolvedWantToAccessHook() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository");
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);
        RecordingAccessHook accessHook = new RecordingAccessHook();
        accessHook.rejectUnresolvedFetch();
        GitObjectId missing = GitObjectId.of("f".repeat(40));

        assertThatThrownBy(() -> service.legacyUploadPack(
                request("/demo.git"),
                new NativeFetchRequest(
                        Set.of(missing),
                        Set.of(),
                        true,
                        Set.of(),
                        NativeFetchOptions.initial(false, false, false)),
                accessHook))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("unresolved want");
        assertThat(accessHook.calls()).containsExactly(
                "read /demo.git",
                "fetch /demo.git []");
    }

    @Test
    void protocolV2FetchResolvesBranchesContainingWantedObject() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git")
                .valueOrFailure("repository");
        GitObjectId wanted = repository.writeObject(
                ObjectType.BLOB,
                "wanted".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/heads/main", NULL_ID, wanted.value());
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);
        RecordingAccessHook accessHook = new RecordingAccessHook();
        NativeFetchRequest fetchRequest = new NativeFetchRequest(
                Set.of(wanted),
                Set.of(),
                true,
                Set.of(),
                NativeFetchOptions.initial(false, false, false));

        try (NativePackProducer ignored = service.protocolV2Fetch(
                request("/demo.git"),
                fetchRequest,
                accessHook,
                NativePackfileUriSourceFactory.NONE)
                .packProducer()) {
            assertThat(accessHook.calls()).containsExactly(
                    "read /demo.git",
                    "fetch /demo.git [main]");
        }
    }

    @Test
    void protocolV2FetchReportsUnresolvedWantToAccessHook() {
        InMemoryNativeGitRepositoryProvider provider =
                new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository");
        GitNativeRepositoryService service =
                new DefaultGitNativeRepositoryService(provider);
        RecordingAccessHook accessHook = new RecordingAccessHook();
        accessHook.rejectUnresolvedFetch();
        GitObjectId missing = GitObjectId.of("f".repeat(40));
        NativeFetchRequest fetchRequest = new NativeFetchRequest(
                Set.of(missing),
                Set.of(),
                true,
                Set.of(),
                NativeFetchOptions.initial(false, false, false));

        assertThatThrownBy(() -> service.protocolV2Fetch(
                request("/demo.git"),
                fetchRequest,
                accessHook,
                NativePackfileUriSourceFactory.NONE))
                .isInstanceOf(
                        GitNativeRepositoryAccessHook.AccessDeniedException.class)
                .hasMessageContaining("unresolved want");
        assertThat(accessHook.calls()).containsExactly(
                "read /demo.git",
                "fetch /demo.git []");
    }

    @Test
    void acknowledgesProtocolV2FetchHavesPresentInRepositoryOrder() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId firstPresent = repository.writeObject(ObjectType.BLOB, "first".getBytes(StandardCharsets.US_ASCII));
        GitObjectId secondPresent = repository.writeObject(ObjectType.BLOB, "second".getBytes(StandardCharsets.US_ASCII));
        GitObjectId missing = GitObjectId.of("f".repeat(40));
        LinkedHashSet<GitObjectId> haves = new LinkedHashSet<>();
        haves.add(secondPresent);
        haves.add(missing);
        haves.add(firstPresent);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        List<GitObjectId> acknowledgments = protocolV2FetchAcknowledgments(
                service,
                request("/demo.git"),
                new NativeFetchRequest(
                        Set.of(firstPresent),
                        haves,
                        false,
                        Set.of(),
                        new NativeFetchOptions(
                                false,
                                false,
                                false,
                                true,
                                NativeObjectFilter.NONE,
                                Set.of())));

        assertThat(acknowledgments).containsExactly(secondPresent, firstPresent);
    }

    @Test
    void listsMatchingBranchesAndLightweightTagsInLexicographicOrder() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId lightweightTagId = repository.writeObject(ObjectType.COMMIT, "tag target".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/tags/v1", NULL_ID, lightweightTagId.value());
        repository.updateRef("refs/heads/topic", NULL_ID, TAG_ID);
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "refs/heads/main"), direct(TAG_ID, "refs/heads/topic"), direct(lightweightTagId.value(), "refs/tags/v1"));
    }

    @Test
    void doesNotDuplicateRefsMatchedByOverlappingPrefixes() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(false, false, false, List.of("refs/", "refs/heads/")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "refs/heads/main"));
    }

    @Test
    void returnsEmptyResponseWhenNoRefsMatch() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(false, false, false, List.of("refs/tags/")));

        assertThat(response.refs()).isEmpty();
    }

    @Test
    void listsResolvedHeadWithoutSymrefTargetWhenNotRequested() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(false, false, false, List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "HEAD"));
    }

    @Test
    void listsResolvedHeadWithSymrefTargetWhenRequested() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(false, true, false, List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "HEAD", Optional.of("refs/heads/main"), Optional.empty()));
    }

    @Test
    void listsHeadFromExistingBranchWhenDefaultHeadTargetIsMissing() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/master", NULL_ID, MAIN_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(
                service,
                request("/demo.git"),
                new LsRefsRequest(false, true, true, List.of("HEAD")));

        assertThat(response.refs()).containsExactly(
                direct(MAIN_ID, "HEAD", Optional.of("refs/heads/master"), Optional.empty()));
    }

    @Test
    void listsOnlySynthesizedHeadWhenSnapshotContainsStoredHead() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        repository.updateRef("HEAD", NULL_ID, TAG_ID);
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(false, true, false, List.of("HEAD")));

        assertThat(response.refs()).containsExactly(direct(MAIN_ID, "HEAD", Optional.of("refs/heads/main"), Optional.empty()));
    }

    @Test
    void listsUnbornHeadWhenRequested() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository");
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(false, true, true, List.of("HEAD")));

        assertThat(response.refs()).containsExactly(new GitLsRefsResponse.UnbornRef("HEAD", "refs/heads/main"));
    }

    @Test
    void peelsNestedAnnotatedTagToFinalNonTagObject() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT, "commit".getBytes(StandardCharsets.US_ASCII));
        GitObjectId innerTagId = repository.writeObject(ObjectType.TAG, tagData(commitId.value()));
        GitObjectId outerTagId = repository.writeObject(ObjectType.TAG, tagData(innerTagId.value()));
        repository.updateRef("refs/tags/nested", NULL_ID, outerTagId.value());
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(outerTagId.value(), "refs/tags/nested", Optional.empty(), Optional.of(commitId.value())));
    }

    @Test
    void peelsAnnotatedTagWithLargeBodyFromBoundedPrefix() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT, "commit".getBytes(StandardCharsets.US_ASCII));
        byte[] objectLine = ("object " + commitId.value() + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tagData = new byte[1024 * 1024 + objectLine.length];
        Arrays.fill(tagData, (byte) 'x');
        System.arraycopy(objectLine, 0, tagData, 0, objectLine.length);
        GitObjectId tagId = repository.writeObject(ObjectType.TAG, tagData);
        repository.updateRef("refs/tags/large", NULL_ID, tagId.value());
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        Optional<LooseObjectPrefix> prefix = repository.readObjectPrefix(tagId, 48);
        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/tags/large")));

        assertThat(prefix).isPresent();
        assertThat(prefix.get().dataPrefix()).hasSize(48).isEqualTo(objectLine);
        assertThat(response.refs()).containsExactly(direct(tagId.value(), "refs/tags/large", Optional.empty(), Optional.of(commitId.value())));
    }

    @Test
    void omitsPeeledAttributeForMalformedAnnotatedTag() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId malformedTagId = repository.writeObject(ObjectType.TAG, "object not-a-hex-object-id\n".getBytes(StandardCharsets.US_ASCII));
        repository.updateRef("refs/tags/malformed", NULL_ID, malformedTagId.value());
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(malformedTagId.value(), "refs/tags/malformed"));
    }

    @Test
    void omitsPeeledAttributeWhenAnnotatedTagTargetIsMissing() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId tagId = repository.writeObject(ObjectType.TAG, tagData("f".repeat(40)));
        repository.updateRef("refs/tags/missing-target", NULL_ID, tagId.value());
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(tagId.value(), "refs/tags/missing-target"));
    }

    @Test
    void memoizesSharedTagChainsAcrossMatchingRefs() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT, "commit".getBytes(StandardCharsets.US_ASCII));
        List<GitObjectId> tagIds = new ArrayList<>();
        GitObjectId targetId = commitId;
        int chainLength = 180;
        for (int i = 0; i < chainLength; i++) {
            GitObjectId tagId = repository.writeObject(ObjectType.TAG, tagData(targetId.value()));
            tagIds.add(tagId);
            targetId = tagId;
            repository.updateRef("refs/tags/shared-%03d".formatted(i), NULL_ID, tagId.value());
        }
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/tags/shared-")));

        List<GitLsRefsResponse.DirectRef> expected = new ArrayList<>();
        for (int i = 0; i < chainLength; i++) {
            expected.add(direct(tagIds.get(i).value(), "refs/tags/shared-%03d".formatted(i), Optional.empty(), Optional.of(commitId.value())));
        }
        assertThat(response.refs()).containsExactlyElementsOf(expected);
    }

    @Test
    void distinguishesCachedLightweightTagFromAnnotatedTagTarget() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId commitId = repository.writeObject(ObjectType.COMMIT, "commit".getBytes(StandardCharsets.US_ASCII));
        GitObjectId annotatedTagId = repository.writeObject(ObjectType.TAG, tagData(commitId.value()));
        repository.updateRef("refs/tags/a-lightweight", NULL_ID, commitId.value());
        repository.updateRef("refs/tags/z-annotated", NULL_ID, annotatedTagId.value());
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/tags/")));

        assertThat(response.refs()).containsExactly(direct(commitId.value(), "refs/tags/a-lightweight"), direct(annotatedTagId.value(), "refs/tags/z-annotated", Optional.empty(), Optional.of(commitId.value())));
    }

    @Test
    void omitsPeeledAttributeWhenTagChainExceedsDepthLimit() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = provider.create("/demo.git").valueOrFailure("repository");
        GitObjectId targetId = repository.writeObject(ObjectType.COMMIT, "commit".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i <= DefaultGitNativeRepositoryService.MAX_TAG_TRAVERSAL_DEPTH; i++) {
            targetId = repository.writeObject(ObjectType.TAG, tagData(targetId.value()));
        }
        repository.updateRef("refs/tags/too-deep", NULL_ID, targetId.value());
        GitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitLsRefsResponse response = lsRefs(service, request("/demo.git"), new LsRefsRequest(true, false, false, List.of("refs/tags/too-deep")));

        assertThat(response.refs()).containsExactly(direct(targetId.value(), "refs/tags/too-deep"));
    }

    private static byte[] tagData(String targetId) {
        return ("object " + targetId + "\n" + "type tag\n" + "tag nested\n\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static GitV1Advertisement legacyUploadPackAdvertisement(
            GitNativeRepositoryService service,
            InitialRequestData data) {
        return service.legacyUploadPackAdvertisement(
                data,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                GitWireConfiguration.allSupported());
    }

    private static GitV1Advertisement legacyReceivePackAdvertisement(
            GitNativeRepositoryService service,
            InitialRequestData data) {
        return service.legacyReceivePackAdvertisement(
                data,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                GitWireConfiguration.allSupported());
    }

    private static NativePackProducer legacyUploadPack(
            GitNativeRepositoryService service,
            InitialRequestData data,
            NativeFetchRequest request) {
        return service.legacyUploadPack(
                data,
                request,
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }

    private static List<GitObjectId> protocolV2FetchAcknowledgments(
            GitNativeRepositoryService service,
            InitialRequestData data,
            NativeFetchRequest request) {
        return service.protocolV2FetchAcknowledgments(
                data,
                request,
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }

    private static GitLsRefsResponse lsRefs(
            GitNativeRepositoryService service,
            InitialRequestData data,
            LsRefsRequest request) {
        return service.lsRefs(
                data,
                request,
                GitNativeRepositoryAccessHook.ALLOW_ALL);
    }

    private static GitLsRefsResponse.DirectRef direct(String objectId, String name) {
        return direct(objectId, name, Optional.empty(), Optional.empty());
    }

    private static GitLsRefsResponse.DirectRef direct(String objectId, String name, Optional<String> symrefTarget, Optional<String> peeledObjectId) {
        return new GitLsRefsResponse.DirectRef(objectId, name, symrefTarget, peeledObjectId);
    }

    private static InMemoryNativeGitRepositoryProvider providerWithMainRef() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        provider.create("/demo.git").valueOrFailure("repository").updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        return provider;
    }

    private static GitWireConfiguration uploadConfiguration(boolean multiAckDetailed, boolean thinPack, boolean sideBand64k, boolean ofsDelta, boolean symref, boolean agent) {
        GitWireConfiguration supported = GitWireConfiguration.allSupported();
        return new GitWireConfiguration(new GitWireConfiguration.LegacyUploadPack(multiAckDetailed, thinPack, sideBand64k, ofsDelta, symref, agent), supported.receivePack(), supported.protocolV2());
    }

    private static GitWireConfiguration receiveConfiguration(boolean reportStatus, boolean sideBand64k, boolean ofsDelta, boolean objectFormat, boolean agent) {
        GitWireConfiguration supported = GitWireConfiguration.allSupported();
        return new GitWireConfiguration(supported.uploadPack(), new GitWireConfiguration.LegacyReceivePack(reportStatus, sideBand64k, ofsDelta, objectFormat, agent), supported.protocolV2());
    }

    private static List<String> capabilityTokens(GitV1Advertisement advertisement) {
        List<String> tokens = new ArrayList<>();
        for (var capability : advertisement.capabilities()) {
            tokens.add(capability.wireToken());
        }
        return tokens;
    }

    private static InitialRequestData request(String path) {
        return new InitialRequestData(InitialRequestService.UPLOAD_PACK, path, "localhost", Map.of());
    }

    private static InitialRequestData receiveRequest(String path) {
        return new InitialRequestData(InitialRequestService.RECEIVE_PACK, path, "localhost", Map.of());
    }

    private static LegacyReceivePack receivePack(
            GitNativeRepositoryService service,
            List<LegacyReceiveCommand> commands,
            Set<String> capabilities,
            LooseObjectStore quarantine) {
        InitialRequestData request = receiveRequest("/demo.git");
        GitV1Advertisement advertisement = service.legacyReceivePackAdvertisement(
                request,
                GitNativeRepositoryAccessHook.ALLOW_ALL,
                GitWireConfiguration.allSupported());
        return new LegacyReceivePack(
                new LegacyReceiveCommandSection(
                        request,
                        commands,
                        capabilities,
                        advertisement),
                quarantine);
    }

    private static PackIngestionResult.Complete ingestReceivePack(
            GitNativeRepositoryService service,
            byte[] pack) {
        try (PackIngestionSession session = service.beginLegacyReceivePack(
                receiveRequest("/demo.git"),
                GitNativeRepositoryAccessHook.ALLOW_ALL)) {
            ByteBuf input = Unpooled.wrappedBuffer(pack);
            try {
                PackIngestionResult result = session.accept(input);
                if (result instanceof PackIngestionResult.NeedInput) {
                    result = session.endOfInput();
                }
                assertThat(result).isInstanceOf(PackIngestionResult.Complete.class);
                return (PackIngestionResult.Complete) result;
            } finally {
                input.release();
            }
        }
    }

    private static byte[] pack(LooseObjectStore objects, GitObjectId objectId) {
        ByteBuf output = Unpooled.buffer();
        try (NativePackProducer producer = new NoDeltaPackBuilder().producer(
                objects,
                List.of(objectId))) {
            assertThat(producer.produce(output))
                    .isEqualTo(NativePackProducer.Result.COMPLETED);
            byte[] pack = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), pack);
            return pack;
        } finally {
            output.release();
        }
    }

    private static String incompleteCommit(GitObjectId missingTree) {
        return "tree " + missingTree.value() + "\n"
                + "author Orion <orion@example.com> 0 +0000\n"
                + "committer Orion <orion@example.com> 0 +0000\n\n"
                + "missing tree\n";
    }

    private record UploadCapabilityCase(GitWireConfiguration configuration, List<String> expectedTokens) {
    }

    private record ReceiveCapabilityCase(GitWireConfiguration configuration, List<String> expectedTokens) {
    }

    private static final class RecordingProvider implements NativeGitRepositoryProvider {
        private final NativeGitRepositoryProvider backend;
        private int readCalls;
        private int publishCalls;
        private boolean rejectPublication;

        private RecordingProvider(NativeGitRepositoryProvider backend) {
            this.backend = backend;
        }

        @Override
        public List<String> repositoryNames() {
            return backend.repositoryNames();
        }

        @Override
        public boolean exists(String repositoryName) {
            return backend.exists(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> find(String repositoryName) {
            return backend.find(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> create(String repositoryName) {
            return backend.create(repositoryName);
        }

        @Override
        public Result<NativeGitRepository> openForRead(String repositoryName) {
            readCalls++;
            return backend.find(repositoryName);
        }

        @Override
        public List<RefUpdateResult> publish(
                String repositoryName,
                LooseObjectStore objects,
                List<LooseRefStore.Update> updates,
                boolean atomic) {
            publishCalls++;
            if (!rejectPublication) {
                return NativeGitRepositoryProvider.super.publish(
                        repositoryName,
                        objects,
                        updates,
                        atomic);
            }
            List<RefUpdateResult> results = new ArrayList<>(updates.size());
            for (int index = 0; index < updates.size(); index++) {
                results.add(RefUpdateResult.STALE);
            }
            return List.copyOf(results);
        }
    }

    private static final class RecordingAccessHook
            implements GitNativeRepositoryAccessHook {
        private final List<String> calls = new ArrayList<>();
        private boolean rejectRead;
        private boolean rejectReceive;
        private boolean rejectUpdates;
        private boolean rejectUnresolvedFetch;

        @Override
        public void beforeRead(String repositoryName) {
            calls.add("read " + repositoryName);
            if (rejectRead) {
                throw new AccessDeniedException(
                        "denied read " + repositoryName,
                        null);
            }
        }

        @Override
        public void beforeReceive(String repositoryName) {
            calls.add("receive " + repositoryName);
            if (rejectReceive) {
                throw new AccessDeniedException(
                        "denied receive " + repositoryName,
                        null);
            }
        }

        @Override
        public void beforeCreate(String repositoryName) {
            calls.add("create " + repositoryName);
        }

        @Override
        public void beforeWrite(String repositoryName) {
            calls.add("write " + repositoryName);
        }

        @Override
        public void beforeFetch(
                String repositoryName,
                List<String> branchNames) {
            calls.add("fetch " + repositoryName + " " + branchNames);
            if (rejectUnresolvedFetch && branchNames.isEmpty()) {
                throw new AccessDeniedException("unresolved want", null);
            }
        }

        @Override
        public void beforeUpdate(
                String repositoryName,
                String refName,
                boolean force) {
            calls.add("update " + repositoryName + " " + refName + " " + force);
            if (rejectUpdates) {
                throw new AccessDeniedException("denied update", null);
            }
        }

        private void rejectRead() {
            rejectRead = true;
        }

        private void rejectReceive() {
            rejectReceive = true;
        }

        private void rejectUpdates() {
            rejectUpdates = true;
        }

        private void rejectUnresolvedFetch() {
            rejectUnresolvedFetch = true;
        }

        private List<String> calls() {
            return List.copyOf(calls);
        }
    }
}
