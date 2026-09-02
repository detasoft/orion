package pro.deta.orion.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.crypto.PasswordHashingAlgorithm;
import pro.deta.orion.git.nativestorage.GitCommitAuthor;
import pro.deta.orion.git.nativestorage.GitRepositoryFileSnapshot;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionConfiguration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

class InternalConfigurationRepositoryLifecycleTest {
    private static final String REPOSITORY_NAME = "internal/configuration";
    private static final String CONFIGURATION_REF = "refs/heads/configuration";
    private static final String ACL_PATH = "config/orion.xml";

    @TempDir
    private Path tempDir;

    @Test
    void bootstrapsOnceAndReusesTheCommittedAclOnRestart() throws Exception {
        OrionConfiguration configuration = configuration();
        ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        char[] rootPassword;
        String firstVersion;
        try {
            System.setOut(new PrintStream(processOutput, true, StandardCharsets.UTF_8));
            OrionComponent first = component(configuration);
            OrionApplicationLifecycle firstLifecycle = first.orionApplicationLifecycle();
            try {
                assertThat(firstLifecycle.runApplication()).isEqualTo(RUNNING);
                rootPassword = first.orionAccessControlService()
                        .plainRootToken(PlainRootTokenAccessForTests.create());
                assertAuthenticated(first, "root", new String(rootPassword));
                assertThat(first.nativeGitRepositoryProvider().repositoryNames())
                        .containsExactly(REPOSITORY_NAME);
                GitRepositoryFileSnapshot snapshot = repository(first).loadFiles(
                        CONFIGURATION_REF,
                        List.of(ACL_PATH));
                firstVersion = snapshot.version().orElseThrow();
                AccessControl acl = new XmlService().deserialize(
                        new ByteArrayInputStream(snapshot.files().get(ACL_PATH)));
                assertThat(acl.getUsers()).extracting(AccessControl.User::getId).contains("root");
            } finally {
                assertThat(firstLifecycle.shutdownApplication()).isEqualTo(FIN);
            }

            OrionComponent restarted = component(configuration);
            OrionApplicationLifecycle restartedLifecycle = restarted.orionApplicationLifecycle();
            try {
                assertThat(restartedLifecycle.runApplication()).isEqualTo(RUNNING);
                assertAuthenticated(restarted, "root", new String(rootPassword));
                assertThat(repository(restarted).loadFiles(CONFIGURATION_REF, List.of(ACL_PATH)).version())
                        .contains(firstVersion);
                assertThatThrownBy(() -> restarted.orionAccessControlService()
                        .plainRootToken(PlainRootTokenAccessForTests.create()))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                assertThat(restartedLifecycle.shutdownApplication()).isEqualTo(FIN);
            }
        } finally {
            System.setOut(originalOut);
        }

        assertThat(processOutput.toString(StandardCharsets.UTF_8)).containsOnlyOnce("---ROOT PASSWORD: ");
    }

    @Test
    void reloadsReceivePackPublicationsAndRetainsLastValidAcl() throws Exception {
        OrionComponent component = component(configuration());
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            NativeGitRepository repository = repository(component);

            publishCandidate(repository, "push", aclBytes("push-user", "push-password"));
            assertAuthenticated(component, "push-user", "push-password");

            publishCandidate(repository, "invalid", "<not-valid-xml".getBytes(StandardCharsets.UTF_8));
            assertAuthenticated(component, "push-user", "push-password");

            publishCandidate(repository, "recovery", aclBytes("recovery-user", "recovery-password"));
            assertAuthenticated(component, "recovery-user", "recovery-password");
        } finally {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    @Test
    void concurrentRefUpdatesActivateTheAcceptedCandidate() throws Exception {
        OrionComponent component = component(configuration());
        OrionApplicationLifecycle lifecycle = component.orionApplicationLifecycle();
        try {
            assertThat(lifecycle.runApplication()).isEqualTo(RUNNING);
            NativeGitRepository repository = repository(component);
            String alphaId = saveCandidate(repository, "alpha", aclBytes("alpha", "alpha-password"));
            String betaId = saveCandidate(repository, "beta", aclBytes("beta", "beta-password"));
            String expectedOldId = repository.refs().get(CONFIGURATION_REF);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<List<RefUpdateResult>> alpha = executor.submit(() -> {
                    start.await();
                    return publish(repository, expectedOldId, alphaId);
                });
                Future<List<RefUpdateResult>> beta = executor.submit(() -> {
                    start.await();
                    return publish(repository, expectedOldId, betaId);
                });
                start.countDown();
                assertThat(List.of(alpha.get().getFirst(), beta.get().getFirst()))
                        .containsExactlyInAnyOrder(RefUpdateResult.FAST_FORWARD, RefUpdateResult.STALE);
            }

            String activeId = repository.refs().get(CONFIGURATION_REF);
            if (alphaId.equals(activeId)) {
                assertAuthenticated(component, "alpha", "alpha-password");
            } else {
                assertThat(activeId).isEqualTo(betaId);
                assertAuthenticated(component, "beta", "beta-password");
            }
        } finally {
            assertThat(lifecycle.shutdownApplication()).isEqualTo(FIN);
        }
    }

    private OrionConfiguration configuration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getBootstrap().setBaseDir(tempDir.resolve("runtime").toString());
        configuration.getStorage().setLocation(tempDir.resolve("repositories").toUri().toString());
        configuration.getBootstrap().getAccessControl().setLocation("local:" + REPOSITORY_NAME);
        configuration.getBootstrap().getAccessControl().setRef(CONFIGURATION_REF);
        configuration.getBootstrap().getAccessControl().setPaths(List.of(ACL_PATH));
        configuration.getTransport().getGit().setEnabled(false);
        configuration.getTransport().getSsh().setEnabled(false);
        configuration.getTransport().getHttp().setEnabled(false);
        configuration.getTransport().getHttps().setEnabled(false);
        return configuration;
    }

    private static OrionComponent component(OrionConfiguration configuration) {
        return DaggerOrionComponent.builder()
                .configurationProvider(() -> configuration)
                .build();
    }

    private static NativeGitRepository repository(OrionComponent component) {
        return component.nativeGitRepositoryProvider().find(REPOSITORY_NAME)
                .valueOrFailure("internal configuration repository");
    }

    private static void publishCandidate(
            NativeGitRepository repository,
            String candidateName,
            byte[] content) throws Exception {
        String candidateId = saveCandidate(repository, candidateName, content);
        String expectedOldId = repository.refs().get(CONFIGURATION_REF);
        assertThat(publish(repository, expectedOldId, candidateId))
                .containsExactly(RefUpdateResult.FAST_FORWARD);
    }

    private static String saveCandidate(
            NativeGitRepository repository,
            String candidateName,
            byte[] content) throws Exception {
        String candidateRef = "refs/heads/candidate-" + candidateName;
        repository.saveFiles(
                candidateRef,
                Map.of(ACL_PATH, content),
                "candidate " + candidateName,
                GitCommitAuthor.EMPTY);
        return repository.refs().get(candidateRef);
    }

    private static List<RefUpdateResult> publish(
            NativeGitRepository repository,
            String expectedOldId,
            String candidateId) {
        return repository.publishObjectsAndRefs(
                new LooseObjectStore(),
                List.of(new LooseRefStore.Update(
                        CONFIGURATION_REF,
                        expectedOldId,
                        candidateId)),
                true);
    }

    private static byte[] aclBytes(String userId, String password) throws Exception {
        OrionPasswordHashingService hashingService = new OrionPasswordHashingService();
        String hash = hashingService.calculateHash(PasswordHashingAlgorithm.SHA1, password.toCharArray());
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, hash));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XmlService().serialize(draft.toAccessControl(), output);
        return output.toByteArray();
    }

    private static void assertAuthenticated(OrionComponent component, String userId, String password) {
        assertThat(component.orionAccessControlService().authenticateUser(
                userId,
                password.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AuthenticationResult.Success.class);
    }
}
