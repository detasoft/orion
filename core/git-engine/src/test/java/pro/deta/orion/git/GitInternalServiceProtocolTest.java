package pro.deta.orion.git;

import org.assertj.core.api.SoftAssertions;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlDraft;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.GitReceiveOrionEvent;
import pro.deta.orion.event.type.GitUploadOrionEvent;
import pro.deta.orion.event.type.OrionEvent;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.git.common.GitRefUpdateResult;
import pro.deta.orion.git.common.GitRefUpdateType;
import pro.deta.orion.util.stream.StandardStreams;
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
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFirstCommitThenListAndFetch(gitServer(service, writer), assertions));

            assertThat(repositoryProvider.exists("project")).isTrue();
            assertFirstCommitReceiveEvent(events, "project", "writer");
            assertUploadEvent(events, "project");
        }
    }

    @Test
    @DisplayName("receive-pack creates the repository before accepting the first push")
    void receivePackCreatesRepositoryBeforeAcceptingFirstPush() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        RecordingEventManager events = new RecordingEventManager();
        GitInternalService service = new GitInternalService(repositoryProvider, events);
        InternalUserImpl creator = gitUser("creator", "project");

        SoftAssertions.assertSoftly(assertions ->
                Scenarios.pushFirstCommitThenListAndFetchFromReceive(gitServer(service, creator), assertions));

        assertThat(repositoryProvider.exists("project")).isTrue();
        assertThat(gitStorageDir.resolve("project/config")).exists();
        assertFirstCommitReceiveEvent(events, "project", "creator");
    }

    @Test
    @DisplayName("receive-pack publishes an update event for fast-forward pushes")
    void receivePackPublishesUpdateEventForFastForwardPushes() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository repositoryHandle = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            Repository repository = jgitRepository(repositoryHandle);
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFirstCommitThenFastForwardMasterAndListIt(gitServer(service, writer), repository, assertions));

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", GitRefUpdateType.CREATE);
            assertReceiveEventRef(receiveEvents.get(1), "project", "writer", "refs/heads/master", GitRefUpdateType.UPDATE);
        }
    }

    @Test
    @DisplayName("receive-pack publishes a delete event for deleted refs")
    void receivePackPublishesDeleteEventForDeletedRefs() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFirstCommitThenDeleteMasterAndListRefs(gitServer(service, writer), assertions));

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", GitRefUpdateType.CREATE);
            assertReceiveEventRef(receiveEvents.get(1), "project", "writer", "refs/heads/master", GitRefUpdateType.DELETE);
        }
    }

    @Test
    @DisplayName("receive-pack publishes all refs from a multi-ref push")
    void receivePackPublishesAllRefsFromMultiRefPush() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFeatureBranchAndTagInOneReceivePack(gitServer(service, writer), assertions));

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", GitRefUpdateType.CREATE);
            assertThat(receiveEvents.get(1).getReceiveEventRefs())
                    .hasSize(2)
                    .anySatisfy(ref -> {
                        assertThat(ref.refName()).isEqualTo("refs/heads/feature");
                        assertThat(ref.type()).isEqualTo(GitRefUpdateType.CREATE);
                        assertThat(ref.result()).isEqualTo(GitRefUpdateResult.OK);
                    })
                    .anySatisfy(ref -> {
                        assertThat(ref.refName()).isEqualTo("refs/tags/v1");
                        assertThat(ref.type()).isEqualTo(GitRefUpdateType.CREATE);
                        assertThat(ref.result()).isEqualTo(GitRefUpdateResult.OK);
                    });
        }
    }

    @Test
    @DisplayName("upload-pack denies fetching a branch outside the user's branch grant")
    void uploadPackDeniesFetchingBranchOutsideUserBranchGrant() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository repositoryHandle = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            Repository repository = jgitRepository(repositoryHandle);
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");
            InternalUserImpl reader = gitReader("reader", "project", "master");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFirstCommitThenCreateSecondRootFeatureBranch(gitServer(service, writer), repository, assertions));
            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.fetchSecondRootFeatureBranchDenied(gitServer(service, reader), assertions));

            assertThat(events.eventsOf(GitUploadOrionEvent.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("upload-pack allows fetching a branch inside the user's branch grant")
    void uploadPackAllowsFetchingBranchInsideUserBranchGrant() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository repositoryHandle = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            Repository repository = jgitRepository(repositoryHandle);
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");
            InternalUserImpl reader = gitReader("reader", "project", "master");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFirstCommitThenCreateSecondRootFeatureBranch(gitServer(service, writer), repository, assertions));
            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.fetchMasterOnly(gitServer(service, reader), assertions));

            assertUploadEvent(events, "project");
        }
    }

    @Test
    @DisplayName("receive-pack publishes rejected non-fast-forward commands")
    void receivePackPublishesRejectedNonFastForwardCommands() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository repositoryHandle = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            Repository repository = jgitRepository(repositoryHandle);
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.rejectNonFastForwardPushWhenConfigured(gitServer(service, writer), repository, assertions));

            List<GitReceiveOrionEvent> receiveEvents = events.eventsOf(GitReceiveOrionEvent.class);
            assertThat(receiveEvents).hasSize(2);
            assertReceiveEventRef(receiveEvents.get(0), "project", "writer", "refs/heads/master", GitRefUpdateType.CREATE);
            assertReceiveEventRef(
                    receiveEvents.get(1),
                    "project",
                    "writer",
                    "refs/heads/master",
                    GitRefUpdateType.UPDATE_NON_FAST_FORWARD,
                    GitRefUpdateResult.REJECTED_NON_FAST_FORWARD);
        }
    }

    @Test
    @DisplayName("upload-pack for a missing repository publishes no upload event")
    void uploadPackForMissingRepositoryPublishesNoUploadEvent() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        RecordingEventManager events = new RecordingEventManager();
        GitInternalService service = new GitInternalService(repositoryProvider, events);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StandardStreams streams = StreamUtils.newInstance(
                new ByteArrayInputStream(new byte[0]),
                output,
                new ByteArrayOutputStream());

        service.service(securityContext(gitUser("reader", "missing"), "request-1"), "test-client", streams, "request-1",
                ignored -> GitInternalService.parseGitCommand("git-upload-pack /missing.git", List.of("version=2")));

        assertThat(output.size()).isZero();
        assertThat(events.eventsOf(GitUploadOrionEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("upload-pack for an unknown object publishes no upload event")
    void uploadPackForUnknownObjectPublishesNoUploadEvent() {
        FileGitRepositoryProvider repositoryProvider = newRepositoryProvider();
        try (GitRepository ignored = repositoryProvider.findOrCreate("project").valueOrFailure("Cannot create project repository")) {
            RecordingEventManager events = new RecordingEventManager();
            GitInternalService service = new GitInternalService(repositoryProvider, events);
            InternalUserImpl writer = gitUser("writer", "project");

            SoftAssertions.assertSoftly(assertions ->
                    Scenarios.pushFirstCommitThenFetchUnknownObject(gitServer(service, writer), assertions));

            assertThat(events.eventsOf(GitUploadOrionEvent.class)).isEmpty();
        }
    }

    private FileGitRepositoryProvider newRepositoryProvider() {
        assertControlledJGitSystemReaderInstalled();
        return new FileGitRepositoryProvider(gitStorageDir);
    }

    private static Repository jgitRepository(GitRepository repository) {
        return repository.unwrapOrThrow(Repository.class);
    }

    private static Scenarios.GitCommandServer gitServer(GitInternalService service, InternalUserImpl userIdentity) {
        return (commandLine, extraProperties) -> serverIO ->
                service.service(
                        securityContext(userIdentity, commandLine),
                        "test-client",
                        serverIO.standardStreams(),
                        commandLine,
                        ignored -> GitInternalService.parseGitCommand(commandLine, extraProperties));
    }

    private static SecurityContext securityContext(InternalUserImpl userIdentity, String requestId) {
        return SecurityContext.createContext()
                .withUserIdentity(userIdentity)
                .withRequestId(requestId);
    }

    private static InternalUserImpl gitUser(String userId, String repositoryName) {
        AccessControl.Grant grant = grantDraft("git-" + repositoryName)
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, "*")
                .toAccessControl();

        return new InternalUserImpl(userId, List.of(grant));
    }

    private static InternalUserImpl gitReader(String userId, String repositoryName, String branchName) {
        AccessControl.Grant grant = grantDraft("git-" + repositoryName + "-reader")
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING)
                .addKey(AccessControl.GrantKey.BRANCH, branchName)
                .toAccessControl();

        return new InternalUserImpl(userId, List.of(grant));
    }

    private static AccessControlDraft.Grant grantDraft(String id) {
        return new AccessControlDraft.Grant(id, new java.util.ArrayList<>());
    }

    private static void assertFirstCommitReceiveEvent(RecordingEventManager events, String repositoryName, String userName) {
        assertThat(events.eventsOf(GitReceiveOrionEvent.class))
                .singleElement()
                .satisfies(event -> assertReceiveEventRef(event, repositoryName, userName, "refs/heads/master", GitRefUpdateType.CREATE));
    }

    private static void assertUploadEvent(RecordingEventManager events, String repositoryName) {
        assertThat(events.eventsOf(GitUploadOrionEvent.class))
                .singleElement()
                .satisfies(event -> assertThat(event.getRepositoryName()).isEqualTo(repositoryName));
    }

    private static void assertReceiveEventRef(GitReceiveOrionEvent event, String repositoryName, String userName, String refName, GitRefUpdateType type) {
        assertReceiveEventRef(event, repositoryName, userName, refName, type, GitRefUpdateResult.OK);
    }

    private static void assertReceiveEventRef(
            GitReceiveOrionEvent event,
            String repositoryName,
            String userName,
            String refName,
            GitRefUpdateType type,
            GitRefUpdateResult result) {
        assertThat(event.getRepositoryName()).isEqualTo(repositoryName);
        assertThat(event.getUserName()).isEqualTo(userName);
        assertThat(event.getReceiveEventRefs())
                .singleElement()
                .satisfies(ref -> {
                    assertThat(ref.refName()).isEqualTo(refName);
                    assertThat(ref.type()).isEqualTo(type);
                    assertThat(ref.result()).isEqualTo(result);
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
