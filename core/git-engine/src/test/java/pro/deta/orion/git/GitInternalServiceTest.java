package pro.deta.orion.git;

import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContextHolder;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.stream.IOEStreamProvider;
import pro.deta.orion.util.stream.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Git internal service permissions")
class GitInternalServiceTest {
    @Test
    @DisplayName("receive-pack for an existing repository requires write access")
    void receivePackForExistingRepositoryRequiresWriteAccess() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            AccessControl.Grant repositoryGrant = new AccessControl.Grant("repository-only", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "project");
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(repositoryGrant)));
            TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider(true);
            GitInternalService service = new GitInternalService(repositoryProvider, null);
            IOEStreamProvider streams = StreamUtils.newInstance(
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());

            service.service("test-client", streams, "request-1",
                    ignoredInput -> GitInternalService.parseGitCommand("git-receive-pack /project.git", List.of()));

            assertThat(repositoryProvider.findCalled).isFalse();
            assertThat(repositoryProvider.findOrCreateCalled).isFalse();
            assertThat(outputBytes(streams)).isEqualTo("0015ERR ACCESS_DENIED0000");
        }
    }

    @Test
    @DisplayName("receive-pack for a missing repository requires create access")
    void receivePackForMissingRepositoryRequiresCreateAccess() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            AccessControl.Grant writeOnlyGrant = new AccessControl.Grant("write-only", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                    .addKey(AccessControl.GrantKey.WRITE, AccessControl.TRUE_STRING);
            getSc().setUserIdentity(new InternalUserImpl("writer", List.of(writeOnlyGrant)));
            TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider(false);
            GitInternalService service = new GitInternalService(repositoryProvider, null);
            IOEStreamProvider streams = StreamUtils.newInstance(
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());

            service.service("test-client", streams, "request-1",
                    ignoredInput -> GitInternalService.parseGitCommand("git-receive-pack /project.git", List.of()));

            assertThat(repositoryProvider.findCalled).isFalse();
            assertThat(repositoryProvider.findOrCreateCalled).isFalse();
            assertThat(outputBytes(streams)).isEqualTo("0015ERR ACCESS_DENIED0000");
        }
    }

    @Test
    @DisplayName("upload-pack for an existing repository requires read access before opening it")
    void uploadPackForExistingRepositoryRequiresReadAccessBeforeOpeningIt() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("anonymous", List.of()));
            TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider(true);
            GitInternalService service = new GitInternalService(repositoryProvider, null);
            IOEStreamProvider streams = StreamUtils.newInstance(
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());

            service.service("test-client", streams, "request-1",
                    ignoredInput -> GitInternalService.parseGitCommand("git-upload-pack /project.git", List.of("version=2")));

            assertThat(repositoryProvider.findCalled).isFalse();
            assertThat(repositoryProvider.findOrCreateCalled).isFalse();
            assertThat(outputBytes(streams)).isEqualTo("0015ERR ACCESS_DENIED0000");
        }
    }

    @Test
    @DisplayName("upload-pack for a missing repository does not create it")
    void uploadPackForMissingRepositoryDoesNotCreateRepository() {
        TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider(false);
        GitInternalService service = new GitInternalService(repositoryProvider, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IOEStreamProvider streams = StreamUtils.newInstance(
                new ByteArrayInputStream(new byte[0]),
                output,
                new ByteArrayOutputStream());

        service.service("test-client", streams, "request-1",
                ignoredInput -> GitInternalService.parseGitCommand("git-upload-pack /project.git", List.of("version=2")));

        assertThat(repositoryProvider.findCalled).isFalse();
        assertThat(repositoryProvider.findOrCreateCalled).isFalse();
        assertThat(output.size()).isZero();
    }

    @Test
    @DisplayName("unknown git command returns a protocol error without opening a repository")
    void unknownGitCommandReturnsProtocolErrorWithoutOpeningRepository() {
        TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider(true);
        GitInternalService service = new GitInternalService(repositoryProvider, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IOEStreamProvider streams = StreamUtils.newInstance(
                new ByteArrayInputStream(new byte[0]),
                output,
                new ByteArrayOutputStream());

        service.service("test-client", streams, "request-1",
                ignoredInput -> GitInternalService.parseGitCommand("git-upload-archive /project.git", List.of()));

        assertThat(repositoryProvider.findCalled).isFalse();
        assertThat(repositoryProvider.findOrCreateCalled).isFalse();
        assertThat(output.toString()).isEqualTo("0017ERR unknown command0000");
    }

    @Test
    @DisplayName("service passes nested repository locators without losing path segments")
    void servicePassesNestedRepositoryLocatorsWithoutLosingPathSegments() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            AccessControl.Grant createGrant = new AccessControl.Grant("create-nested", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "team/project")
                    .addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING);
            getSc().setUserIdentity(new InternalUserImpl("creator", List.of(createGrant)));
            TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider(false);
            GitInternalService service = new GitInternalService(repositoryProvider, null);
            IOEStreamProvider streams = StreamUtils.newInstance(
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());

            service.service("test-client", streams, "request-1",
                    ignoredInput -> GitInternalService.parseGitCommand("git-receive-pack /team/project.git", List.of()));

            assertThat(repositoryProvider.existsRepositoryName).isEqualTo("team/project");
            assertThat(repositoryProvider.findOrCreateRepositoryName).isEqualTo("team/project");
        }
    }

    @Test
    @DisplayName("service keeps unsafe locators inside repository validation")
    void serviceKeepsUnsafeLocatorsInsideRepositoryValidation() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            AccessControl.Grant createGrant = new AccessControl.Grant("create-any", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "*")
                    .addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING);
            getSc().setUserIdentity(new InternalUserImpl("creator", List.of(createGrant)));
            TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider(false);
            GitInternalService service = new GitInternalService(repositoryProvider, null);
            IOEStreamProvider streams = StreamUtils.newInstance(
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    new ByteArrayOutputStream());

            service.service("test-client", streams, "request-1",
                    ignoredInput -> GitInternalService.parseGitCommand("git-receive-pack /../outside.git", List.of()));

            assertThat(repositoryProvider.existsRepositoryName).isEqualTo("../outside");
            assertThat(repositoryProvider.findOrCreateCalled).isFalse();
        }
    }

    private static String outputBytes(IOEStreamProvider streams) {
        ByteArrayOutputStream output = (ByteArrayOutputStream) streams.getOutputStream();
        return output.toString();
    }

    private static final class TrackingRepositoryProvider implements GitRepositoryProvider {
        private final boolean repositoryExists;
        private boolean findCalled;
        private boolean findOrCreateCalled;
        private String existsRepositoryName;
        private String findRepositoryName;
        private String findOrCreateRepositoryName;

        private TrackingRepositoryProvider(boolean repositoryExists) {
            this.repositoryExists = repositoryExists;
        }

        @Override
        public boolean exists(String repositoryName) {
            existsRepositoryName = repositoryName;
            return repositoryExists;
        }

        @Override
        public Result<Repository> find(String repositoryName) {
            findCalled = true;
            findRepositoryName = repositoryName;
            return Result.Failure.generalFailure("find is not implemented in this test");
        }

        @Override
        public Result<Repository> findOrCreate(String repositoryName) {
            findOrCreateCalled = true;
            findOrCreateRepositoryName = repositoryName;
            return Result.Failure.generalFailure("findOrCreate is not implemented in this test");
        }

        @Override
        public GitRepositoryProvider.OrionGitRepositoryResolver createResolver() {
            throw new UnsupportedOperationException();
        }
    }
}
