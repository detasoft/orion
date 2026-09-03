package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agentd.core.AgentConfiguration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAgentdProvisionerTest {
    private static final String PERMIT = "single-use-secret-permit";

    @Test
    void generatedLaunchArgumentsAreAcceptedByAgentdAndSelectBundledSessionHost() {
        String release = "/opt/orion/releases/1.2.3";

        AgentConfiguration configuration = AgentConfiguration.parse(
                RemoteAgentdProvisioner.agentdArguments(release, launchRequest("1.2.3"))
                        .toArray(String[]::new));

        assertThat(configuration.sessionHostExecutable())
                .isEqualTo(Path.of(release, "session-host"));
    }

    @Test
    void uploadsVerifiesAndAtomicallyActivatesRuntimeBundle(@TempDir Path root) throws Exception {
        KeyPair hostKey = keyPair();
        KeyPair clientKey = keyPair();
        Path artifacts = Files.createDirectories(root.resolve("local"));
        RemoteRuntimeBundle bundle = bundle(artifacts, "1.2.3");
        Path installRoot = root.resolve("remote/orion");
        Files.createDirectories(root.resolve("remote"));
        try (TestSshServer server = TestSshServer.start(root, hostKey, clientKey)) {
            RemoteAgentdProvisioner provisioner = provisioner(
                    server, clientKey, installRoot, new RuntimeBundleCatalog(List.of(bundle)));

            ProvisioningResult result = provisioner.install(launchRequest("1.2.3"));

            Path release = installRoot.resolve("releases/1.2.3");
            assertThat(result.platform()).isEqualTo(localPlatform());
            assertThat(result.releaseDirectory()).isEqualTo(release.toString());
            assertThat(Files.readString(release.resolve("agentd"))).isEqualTo("agentd-binary");
            assertThat(Files.readString(release.resolve("session-host"))).isEqualTo("session-host-binary");
            assertThat(Files.readSymbolicLink(installRoot.resolve("current")))
                    .isEqualTo(Path.of("releases/1.2.3"));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(release.resolve("agentd"))))
                    .isEqualTo("rwx------");
        }
    }

    @Test
    void digestMismatchLeavesCurrentReleaseUnchanged(@TempDir Path root) throws Exception {
        KeyPair hostKey = keyPair();
        KeyPair clientKey = keyPair();
        Path artifacts = Files.createDirectories(root.resolve("local"));
        RemoteRuntimeBundle valid = bundle(artifacts, "old");
        RuntimeArtifact mismatchedAgentd = RuntimeArtifact.withExpectedDigest(
                valid.agentd().source(), "agentd", "0".repeat(64));
        RemoteRuntimeBundle mismatched = new RemoteRuntimeBundle(
                "new", localPlatform(), mismatchedAgentd, valid.sessionHost());
        Path installRoot = root.resolve("remote/orion");
        Path releases = Files.createDirectories(installRoot.resolve("releases/old"));
        Files.createSymbolicLink(installRoot.resolve("current"), Path.of("releases/old"));
        try (TestSshServer server = TestSshServer.start(root, hostKey, clientKey)) {
            RemoteAgentdProvisioner provisioner = provisioner(
                    server, clientKey, installRoot, new RuntimeBundleCatalog(List.of(mismatched)));

            assertThatThrownBy(() -> provisioner.install(launchRequest("new")))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.INTEGRITY);

            assertThat(Files.readSymbolicLink(installRoot.resolve("current")))
                    .isEqualTo(Path.of("releases/old"));
            assertThat(releases).exists();
        }
    }

    @Test
    void retryReplacesOnlyItsOwnPartialStagingDirectory(@TempDir Path root) throws Exception {
        KeyPair hostKey = keyPair();
        KeyPair clientKey = keyPair();
        Path artifacts = Files.createDirectories(root.resolve("local"));
        RemoteRuntimeBundle bundle = bundle(artifacts, "retry");
        Path installRoot = root.resolve("remote/orion");
        AgentdLaunchRequest request = launchRequest("retry");
        Path releases = Files.createDirectories(installRoot.resolve("releases"));
        Path ownStaging = Files.createDirectories(
                releases.resolve(".staging-" + request.launchId().value()));
        Files.writeString(ownStaging.resolve("partial"), "partial");
        Path otherStaging = Files.createDirectories(releases.resolve(".staging-other"));
        Files.writeString(otherStaging.resolve("keep"), "keep");
        try (TestSshServer server = TestSshServer.start(root, hostKey, clientKey)) {
            RemoteAgentdProvisioner provisioner = provisioner(
                    server, clientKey, installRoot, new RuntimeBundleCatalog(List.of(bundle)));

            provisioner.install(request);

            assertThat(ownStaging).doesNotExist();
            assertThat(otherStaging.resolve("keep")).hasContent("keep");
        }
    }

    @Test
    void unavailableRequestedVersionFailsBeforeRemoteMutation(@TempDir Path root) throws Exception {
        KeyPair hostKey = keyPair();
        KeyPair clientKey = keyPair();
        Path artifacts = Files.createDirectories(root.resolve("local"));
        RemoteRuntimeBundle available = bundle(artifacts, "available");
        Path installRoot = root.resolve("remote/orion");
        Files.createDirectories(root.resolve("remote"));
        try (TestSshServer server = TestSshServer.start(root, hostKey, clientKey)) {
            RemoteAgentdProvisioner provisioner = provisioner(
                    server, clientKey, installRoot, new RuntimeBundleCatalog(List.of(available)));

            assertThatThrownBy(() -> provisioner.install(launchRequest("missing")))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.RUNTIME_UNAVAILABLE);

            assertThat(installRoot).doesNotExist();
        }
    }

    @Test
    void detachedAgentdUsesBundledSessionHostAfterSshCloses(@TempDir Path root) throws Exception {
        KeyPair hostKey = keyPair();
        KeyPair clientKey = keyPair();
        Path artifacts = Files.createDirectories(root.resolve("local"));
        RemoteRuntimeBundle bundle = fixtureBundle(artifacts, "detached");
        Path installRoot = root.resolve("remote/orion");
        Files.createDirectories(root.resolve("remote"));
        try (TestSshServer server = TestSshServer.start(root, hostKey, clientKey);
             ProvisioningLaunchPermit permit = new ProvisioningLaunchPermit(
                     PERMIT.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            RemoteAgentdProvisioner provisioner = provisioner(
                    server, clientKey, installRoot, new RuntimeBundleCatalog(List.of(bundle)));

            AgentdLaunchRequest request = launchRequest(
                    "detached", root.resolve("remote/state").toString());
            ProvisioningResult result = provisioner.provision(request, permit);

            assertThat(result.version()).isEqualTo("detached");
            assertThat(server.hasActiveSessions()).isFalse();
            assertThat(installRoot.resolve("agentd.marker")).doesNotExist();
            awaitFile(installRoot.resolve("session-host.marker"));
            assertThat(installRoot.resolve("agentd.marker")).hasContent("agentd-started");
            assertThat(installRoot.resolve("session-host.marker")).hasContent("session-host-started");
            assertThat(server.commands()).noneMatch(command -> command.contains(PERMIT));
            assertThat(allRemoteText(installRoot)).doesNotContain(PERMIT);
        }
    }

    @Test
    void launchSetupFailurePreservesCurrentAndKeepsVerifiedRelease(@TempDir Path root) throws Exception {
        KeyPair hostKey = keyPair();
        KeyPair clientKey = keyPair();
        Path artifacts = Files.createDirectories(root.resolve("local"));
        RemoteRuntimeBundle bundle = fixtureBundle(artifacts, "new");
        Path installRoot = root.resolve("remote/orion");
        Path oldRelease = Files.createDirectories(installRoot.resolve("releases/old"));
        Files.createSymbolicLink(installRoot.resolve("current"), Path.of("releases/old"));
        Path stateBlocker = Files.writeString(root.resolve("state-blocker"), "not-a-directory");
        try (TestSshServer server = TestSshServer.start(root, hostKey, clientKey);
             ProvisioningLaunchPermit permit = new ProvisioningLaunchPermit(
                     PERMIT.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            RemoteAgentdProvisioner provisioner = provisioner(
                    server, clientKey, installRoot, new RuntimeBundleCatalog(List.of(bundle)));
            AgentdLaunchRequest request = launchRequest(
                    "new", stateBlocker.resolve("child").toString());

            assertThatThrownBy(() -> provisioner.provision(request, permit))
                    .isInstanceOf(ProvisioningException.class)
                    .extracting(error -> ((ProvisioningException) error).failure())
                    .isEqualTo(ProvisioningFailure.LAUNCH);

            assertThat(Files.readSymbolicLink(installRoot.resolve("current")))
                    .isEqualTo(Path.of("releases/old"));
            assertThat(oldRelease).isDirectory();
            assertThat(installRoot.resolve("releases/new/agentd")).exists();
            assertThat(installRoot.resolve("agentd.marker")).doesNotExist();
        }
    }

    private static RemoteAgentdProvisioner provisioner(
            TestSshServer server,
            KeyPair clientKey,
            Path installRoot,
            RuntimeBundleCatalog catalog) {
        return new RemoteAgentdProvisioner(
                server.endpoint(), new SshCredentials(clientKey),
                options(), installRoot.toString(), catalog);
    }

    private static RemoteRuntimeBundle bundle(Path directory, String version) throws Exception {
        Path agentd = Files.writeString(directory.resolve("agentd-" + version), "agentd-binary");
        Path sessionHost = Files.writeString(
                directory.resolve("session-host-" + version), "session-host-binary");
        return new RemoteRuntimeBundle(
                version,
                localPlatform(),
                RuntimeArtifact.from(agentd, "agentd"),
                RuntimeArtifact.from(sessionHost, "session-host"));
    }

    private static RemoteRuntimeBundle fixtureBundle(Path directory, String version) throws Exception {
        Path agentd = directory.resolve("agentd-" + version);
        Path sessionHost = directory.resolve("session-host-" + version);
        Files.copy(Path.of("src/test/resources/fixtures/agentd"), agentd);
        Files.copy(Path.of("src/test/resources/fixtures/session-host"), sessionHost);
        return new RemoteRuntimeBundle(
                version,
                localPlatform(),
                RuntimeArtifact.from(agentd, "agentd"),
                RuntimeArtifact.from(sessionHost, "session-host"));
    }

    private static void awaitFile(Path path) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!Files.exists(path) && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        assertThat(path).exists();
    }

    private static String allRemoteText(Path root) throws Exception {
        StringBuilder text = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                text.append(Files.readString(path));
            }
        }
        return text.toString();
    }

    private static RemotePlatform localPlatform() {
        String os = System.getProperty("os.name").toLowerCase().contains("mac") ? "Darwin" : "Linux";
        return RemotePlatform.parse(os, System.getProperty("os.arch"));
    }

    private static AgentdLaunchRequest launchRequest(String version) {
        return launchRequest(version, "/var/lib/orion/agent");
    }

    private static AgentdLaunchRequest launchRequest(String version, String stateDirectory) {
        return new AgentdLaunchRequest(
                URI.create("https://orion.example/agent/control"),
                stateDirectory,
                new AgentId("agent-1"),
                new AgentGeneration(1),
                new AgentLaunchId(UUID.randomUUID()),
                1_048_576,
                version);
    }

    private static ProvisioningOptions options() {
        return new ProvisioningOptions(
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                Duration.ofSeconds(3), Duration.ofSeconds(20));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
