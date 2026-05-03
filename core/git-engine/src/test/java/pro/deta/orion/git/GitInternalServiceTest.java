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
            TrackingRepositoryProvider repositoryProvider = new TrackingRepositoryProvider();
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

    private static final class TrackingRepositoryProvider implements GitRepositoryProvider {
        private boolean findCalled;
        private boolean findOrCreateCalled;

        @Override
        public boolean exists(String repositoryName) {
            return true;
        }

        @Override
        public Result<Repository> find(String repositoryName) {
            findCalled = true;
            throw new AssertionError("find must not be called when write access is denied");
        }

        @Override
        public Result<Repository> findOrCreate(String repositoryName) {
            findOrCreateCalled = true;
            throw new AssertionError("findOrCreate must not be called for an existing repository");
        }

        @Override
        public GitRepositoryProvider.OrionGitRepositoryResolver createResolver() {
            throw new UnsupportedOperationException();
        }
    }
}
