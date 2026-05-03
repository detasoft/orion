package pro.deta.orion.git;

import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContextHolder;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.event.type.OrionEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.git.JGitRuntimeAssertions.assertControlledJGitSystemReaderInstalled;
import static pro.deta.orion.git.JGitRuntimeAssertions.installDefaultControlledJGitRuntime;
import static pro.deta.orion.auth.SecurityContextHolder.getSc;

@DisplayName("Git internal service protocol")
@ResourceLock("jgit-system-reader")
class GitInternalServiceProtocolTest extends BaseOrionTest {
    @TempDir
    private Path gitStorageDir;

    @BeforeEach
    void installControlledJGitRuntime() {
        installDefaultControlledJGitRuntime();
    }

    @AfterEach
    void resetControlledJGitRuntime() {
        try {
            assertControlledJGitSystemReaderInstalled();
        } finally {
            installDefaultControlledJGitRuntime();
        }
    }

    @Test
    @DisplayName("existing repository can be pushed, listed and fetched without a network transport")
    void existingRepositoryCanBePushedListedAndFetchedWithoutNetworkTransport() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));

                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.pushFirstCommitThenListAndFetch(gitServer(service), assertions));
            }

            assertThat(repositoryProvider.exists("project")).isTrue();
            assertFirstCommitReceiveEvent(events, "project", "writer");
        }
    }

    @Test
    @DisplayName("receive-pack creates the repository before accepting the first push")
    void receivePackCreatesRepositoryBeforeAcceptingFirstPush() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        RecordingEventManager events = new RecordingEventManager();
        GitInternalService service = new GitInternalService(repositoryProvider, events);

        try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
            getSc().setUserIdentity(gitUser("creator", "project"));

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFirstCommitThenListAndFetchFromReceive(gitServer(service), assertions));
        }

        assertThat(repositoryProvider.exists("project")).isTrue();
        assertThat(gitStorageDir.resolve("project/config")).exists();
        assertFirstCommitReceiveEvent(events, "project", "creator");
    }

    private GitRepositoryProviderImpl newRepositoryProvider() {
        assertControlledJGitSystemReaderInstalled();
        return new GitRepositoryProviderImpl(gitStorageDir);
    }

    private static Scenarios.GitCommandServer gitServer(GitInternalService service) {
        return (commandLine, extraProperties) -> serverIO ->
                service.service(
                        "test-client",
                        serverIO.ioEStreams(),
                        commandLine,
                        ignored -> GitInternalService.parseGitCommand(commandLine, extraProperties));
    }

    private static InternalUserImpl gitUser(String userId, String repositoryName) {
        AccessControl.Grant grant = new AccessControl.Grant("git-" + repositoryName, new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "*");

        return new InternalUserImpl(userId, List.of(grant));
    }

    private static void assertFirstCommitReceiveEvent(RecordingEventManager events, String repositoryName, String userName) {
        assertThat(events.eventsOf(GitReceiveOrionEvent.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getRepositoryName()).isEqualTo(repositoryName);
                    assertThat(event.getUserName()).isEqualTo(userName);
                    assertThat(event.getReceiveEventRefs())
                            .singleElement()
                            .satisfies(ref -> {
                                assertThat(ref.getRefName()).isEqualTo("refs/heads/master");
                                assertThat(ref.getType()).isEqualTo(ReceiveCommand.Type.CREATE);
                                assertThat(ref.getResult()).isEqualTo(ReceiveCommand.Result.OK);
                            });
                });
    }

    private static final class RecordingEventManager extends OrionEventManager {
        private final List<OrionEvent> events = new ArrayList<>();

        @Override
        public void publish(Supplier<OrionEvent> eventPublisher) {
            events.add(eventPublisher.get());
        }

        @Override
        public void publish(OrionEvent orionEvent) {
            events.add(orionEvent);
        }

        private <T extends OrionEvent> List<T> eventsOf(Class<T> type) {
            return events.stream()
                    .filter(type::isInstance)
                    .map(type::cast)
                    .toList();
        }
    }
}
