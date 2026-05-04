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

    private static final class TrackingRepositoryProvider implements GitRepositoryProvider {
        private final boolean repositoryExists;
        private boolean findCalled;
        private boolean findOrCreateCalled;

        private TrackingRepositoryProvider(boolean repositoryExists) {
            this.repositoryExists = repositoryExists;
        }

        @Override
        public boolean exists(String repositoryName) {
            return repositoryExists;
        }

        @Override
        public Result<Repository> find(String repositoryName) {
            findCalled = true;
            throw new AssertionError("find must not be called in this test");
        }

        @Override
        public Result<Repository> findOrCreate(String repositoryName) {
            findOrCreateCalled = true;
            throw new AssertionError("findOrCreate must not be called in this test");
        }

        @Override
        public GitRepositoryProvider.OrionGitRepositoryResolver createResolver() {
            throw new UnsupportedOperationException();
        }
    }
}
