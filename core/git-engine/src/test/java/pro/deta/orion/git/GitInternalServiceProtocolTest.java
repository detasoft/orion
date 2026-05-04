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
import pro.deta.orion.event.type.GitUploadOrionEvent;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.util.stream.IOEStreamProvider;
import pro.deta.orion.util.stream.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
            assertUploadEvent(events, "project");
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

    @Test
    @DisplayName("receive-pack publishes an update event for fast-forward pushes")
    void receivePackPublishesUpdateEventForFastForwardPushes() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository repository = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));

                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.pushFirstCommitThenFastForwardMasterAndListIt(gitServer(service), repository, assertions));
            }

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", ReceiveCommand.Type.CREATE);
            assertReceiveEventRef(receiveEvents.get(1), "project", "writer", "refs/heads/master", ReceiveCommand.Type.UPDATE);
        }
    }

    @Test
    @DisplayName("receive-pack publishes a delete event for deleted refs")
    void receivePackPublishesDeleteEventForDeletedRefs() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));

                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.pushFirstCommitThenDeleteMasterAndListRefs(gitServer(service), assertions));
            }

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", ReceiveCommand.Type.CREATE);
            assertReceiveEventRef(receiveEvents.get(1), "project", "writer", "refs/heads/master", ReceiveCommand.Type.DELETE);
        }
    }

    @Test
    @DisplayName("receive-pack publishes all refs from a multi-ref push")
    void receivePackPublishesAllRefsFromMultiRefPush() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));

                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.pushFeatureBranchAndTagInOneReceivePack(gitServer(service), assertions));
            }

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", ReceiveCommand.Type.CREATE);
            assertThat(receiveEvents.get(1).getReceiveEventRefs())
                    .hasSize(2)
                    .anySatisfy(ref -> {
                        assertThat(ref.getRefName()).isEqualTo("refs/heads/feature");
                        assertThat(ref.getType()).isEqualTo(ReceiveCommand.Type.CREATE);
                        assertThat(ref.getResult()).isEqualTo(ReceiveCommand.Result.OK);
                    })
                    .anySatisfy(ref -> {
                        assertThat(ref.getRefName()).isEqualTo("refs/tags/v1");
                        assertThat(ref.getType()).isEqualTo(ReceiveCommand.Type.CREATE);
                        assertThat(ref.getResult()).isEqualTo(ReceiveCommand.Result.OK);
                    });
        }
    }

    @Test
    @DisplayName("upload-pack denies fetching a branch outside the user's branch grant")
    void uploadPackDeniesFetchingBranchOutsideUserBranchGrant() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository repository = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));
                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.pushFirstCommitThenCreateSecondRootFeatureBranch(gitServer(service), repository, assertions));

                getSc().setUserIdentity(gitReader("reader", "project", "master"));
                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.fetchSecondRootFeatureBranchDenied(gitServer(service), assertions));
            }

            assertThat(events.eventsOf(GitUploadOrionEvent.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("upload-pack allows fetching a branch inside the user's branch grant")
    void uploadPackAllowsFetchingBranchInsideUserBranchGrant() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository repository = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));
                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.pushFirstCommitThenCreateSecondRootFeatureBranch(gitServer(service), repository, assertions));

                getSc().setUserIdentity(gitReader("reader", "project", "master"));
                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.fetchMasterOnly(gitServer(service), assertions));
            }

            assertUploadEvent(events, "project");
        }
    }

    @Test
    @DisplayName("receive-pack publishes rejected non-fast-forward commands")
    void receivePackPublishesRejectedNonFastForwardCommands() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository repository = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));

                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.rejectNonFastForwardPushWhenConfigured(gitServer(service), repository, assertions));
            }

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", ReceiveCommand.Type.CREATE);
            assertReceiveEventRef(
                    receiveEvents.get(1),
                    "project",
                    "writer",
                    "refs/heads/master",
                    ReceiveCommand.Type.UPDATE_NONFASTFORWARD,
                    ReceiveCommand.Result.REJECTED_NONFASTFORWARD);
        }
    }

    @Test
    @DisplayName("upload-pack for a missing repository publishes no upload event")
    void uploadPackForMissingRepositoryPublishesNoUploadEvent() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        RecordingEventManager events = new RecordingEventManager();
        GitInternalService service = new GitInternalService(repositoryProvider, events);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IOEStreamProvider streams = StreamUtils.newInstance(
                new ByteArrayInputStream(new byte[0]),
                output,
                new ByteArrayOutputStream());

        try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
            getSc().setUserIdentity(gitUser("reader", "missing"));
            service.service("test-client", streams, "request-1",
                    ignored -> GitInternalService.parseGitCommand("git-upload-pack /missing.git", List.of("version=2")));
        }

        assertThat(output.size()).isZero();
        assertThat(events.eventsOf(GitUploadOrionEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("upload-pack for an unknown object publishes no upload event")
    void uploadPackForUnknownObjectPublishesNoUploadEvent() {
        GitRepositoryProviderImpl repositoryProvider = newRepositoryProvider();
        try (Repository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);

            try (SecurityContextHolder ignoredSecurityContext = new SecurityContextHolder()) {
                getSc().setUserIdentity(gitUser("writer", "project"));

                SoftAssertions.assertSoftly(assertions ->
                        Scenarios.pushFirstCommitThenFetchUnknownObject(gitServer(service), assertions));
            }

            assertThat(events.eventsOf(GitUploadOrionEvent.class)).isEmpty();
        }
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

    private static InternalUserImpl gitReader(String userId, String repositoryName, String branchName) {
        AccessControl.Grant grant = new AccessControl.Grant("git-" + repositoryName + "-reader", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, branchName);

        return new InternalUserImpl(userId, List.of(grant));
    }

    private static void assertFirstCommitReceiveEvent(RecordingEventManager events, String repositoryName, String userName) {
        assertThat(events.eventsOf(GitReceiveOrionEvent.class))
                .singleElement()
                .satisfies(event -> assertReceiveEventRef(event, repositoryName, userName, "refs/heads/master", ReceiveCommand.Type.CREATE));
    }

    private static void assertUploadEvent(RecordingEventManager events, String repositoryName) {
        assertThat(events.eventsOf(GitUploadOrionEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.getRepositoryName()).isEqualTo(repositoryName));
    }

    private static void assertReceiveEventRef(GitReceiveOrionEvent event, String repositoryName, String userName, String refName, ReceiveCommand.Type type) {
        assertReceiveEventRef(event, repositoryName, userName, refName, type, ReceiveCommand.Result.OK);
    }

    private static void assertReceiveEventRef(
            GitReceiveOrionEvent event,
            String repositoryName,
            String userName,
            String refName,
            ReceiveCommand.Type type,
            ReceiveCommand.Result result) {
        assertThat(event.getRepositoryName()).isEqualTo(repositoryName);
        assertThat(event.getUserName()).isEqualTo(userName);
        assertThat(event.getReceiveEventRefs())
                .singleElement()
                .satisfies(ref -> {
                    assertThat(ref.getRefName()).isEqualTo(refName);
                    assertThat(ref.getType()).isEqualTo(type);
                    assertThat(ref.getResult()).isEqualTo(result);
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
