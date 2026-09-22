package pro.deta.orion.transport.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.wire.GitWireConfiguration;
import pro.deta.orion.git.parser.wire.advertisement.GitAdvertisedRef;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.transport.git.GitWireTestClient.*;

class GitWireAdvertisementTest {

    @Test
    void advertisesRefsFromFileBackedRepositoryProvider(@TempDir Path rootDirectory) throws Exception {
        FileNativeGitRepositoryProvider firstProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        NativeGitRepository repository = createRepository(firstProvider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        FileNativeGitRepositoryProvider secondProvider = new FileNativeGitRepositoryProvider(rootDirectory);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(secondProvider);

        GitV1Advertisement advertisement = legacyUploadPackAdvertisement(service, request("demo"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(MAIN_ID, "HEAD"),
                GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"));
    }

    @Test
    void advertisesHeadFirstAndSortsRepositoryRefs() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/tags/v1", NULL_ID, TAG_ID);
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyUploadPackAdvertisement(service, request("demo"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(MAIN_ID, "HEAD"),
                GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"), GitAdvertisedRef.direct(TAG_ID,
                "refs/tags/v1"));
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
                        "filter",
                        "allow-reachable-sha1-in-want",
                        "no-progress",
                        "include-tag",
                        "agent=orion-native",
                        "symref=HEAD:refs/heads/main");
    }

    @Test
    void keepsUnbornHeadTargetWhenAnotherBranchExists() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/master", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyUploadPackAdvertisement(service, request("demo"));

        assertThat(advertisement.refs()).containsExactly(
                GitAdvertisedRef.direct(MAIN_ID, "refs/heads/master"));
        assertThat(capabilityTokens(advertisement))
                .contains("symref=HEAD:refs/heads/main");
    }

    @Test
    void omitsEachDisabledUploadPackCapabilityWithoutReorderingOthers() throws Exception {
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
                                "filter",
                                "allow-reachable-sha1-in-want",
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
                                "filter",
                                "allow-reachable-sha1-in-want",
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
                                "filter",
                                "allow-reachable-sha1-in-want",
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
                                "filter",
                                "allow-reachable-sha1-in-want",
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
                                "filter",
                                "allow-reachable-sha1-in-want",
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
                                "filter",
                                "allow-reachable-sha1-in-want",
                                "no-progress",
                                "include-tag",
                                "symref=HEAD:refs/heads/main")));

        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        for (UploadCapabilityCase capabilityCase : cases) {
            GitV1Advertisement advertisement =
                    advertise(service, request("demo"), capabilityCase.configuration());

            assertThat(capabilityTokens(advertisement)).containsExactlyElementsOf(capabilityCase.expectedTokens());
        }
    }

    @Test
    void advertisesReceivePackRefsAndCapabilities() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        NativeGitRepository repository = createRepository(provider, "demo");
        repository.updateRef("refs/heads/main", NULL_ID, MAIN_ID);
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyReceivePackAdvertisement(service, receiveRequest("demo"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(MAIN_ID, "HEAD"),
                GitAdvertisedRef.direct(MAIN_ID, "refs/heads/main"));
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
    void omitsEachDisabledReceivePackCapabilityWithoutReorderingOthers() throws Exception {
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

        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);
        for (ReceiveCapabilityCase capabilityCase : cases) {
            GitV1Advertisement advertisement =
                    advertise(service, receiveRequest("demo"), capabilityCase.configuration());

            assertThat(capabilityTokens(advertisement)).containsExactlyElementsOf(capabilityCase.expectedTokens());
        }
    }

    @Test
    void advertisesEmptyReceivePackRepositoryWithPseudoRef() throws Exception {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        DefaultGitNativeRepositoryService service = new DefaultGitNativeRepositoryService(provider);

        GitV1Advertisement advertisement = legacyReceivePackAdvertisement(service, receiveRequest("demo"));

        assertThat(advertisement.refs()).containsExactly(GitAdvertisedRef.direct(NULL_ID, "capabilities^{}"));
    }

    private record UploadCapabilityCase(GitWireConfiguration configuration, List<String> expectedTokens) {}

    private record ReceiveCapabilityCase(GitWireConfiguration configuration, List<String> expectedTokens) {}
}
