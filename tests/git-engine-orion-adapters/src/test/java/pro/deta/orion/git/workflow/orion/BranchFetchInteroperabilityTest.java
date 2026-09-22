package pro.deta.orion.git.workflow.orion;

import org.eclipse.jgit.api.errors.TransportException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.git.client.GitTransportScheme;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitRemoteRepository;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.client.GitTransportScheme.HTTP;
import static pro.deta.orion.git.client.GitTransportScheme.SSH;

class BranchFetchInteroperabilityTest {
    @TempDir
    Path directory;

    static Stream<Arguments> matrix() {
        return Stream.of(Arguments.of("git", HTTP), Arguments.of("git", SSH),
                Arguments.of("jgit", HTTP), Arguments.of("jgit", SSH));
    }

    @ParameterizedTest(name = "{0} obeys branch fetch grants over {1}")
    @MethodSource("matrix")
    void fetchesGrantedBranchButRejectsAnUnrelatedBranch(String engine, GitTransportScheme scheme)
            throws Exception {
        GitClient client = engine.equals("git") ? GitClients.git() : GitClients.jgitAllowAllSsh();
        try (OrionGitServer server = new OrionGitServer(scheme);
             GitWorkTree main = GitClients.jgitAllowAllSsh().init(directory.resolve("main"));
             GitWorkTree feature = GitClients.jgitAllowAllSsh().init(directory.resolve("feature"));
             GitWorkTree fetched = client.init(directory.resolve("fetched"))) {
            GitRemoteRepository remote = server.createRemoteRepository(directory.resolve("server"), "project.git");
            String mainId = publish(main, remote, "main", "allowed\n");
            String featureId = publish(feature, remote, "feature", "restricted\n");
            RepositorySnapshot before = server.snapshot(remote);
            assertThat(before.commits().get(mainId).parents()).isEmpty();
            assertThat(before.commits().get(featureId).parents()).isEmpty();
            server.setUser(user("main"));
            fetched.addRemote("origin", remote);
            fetched.fetch("origin", "main");
            fetched.checkout("main", "refs/remotes/origin/main");
            assertThat(fetched.head()).isEqualTo(mainId);
            RepositorySnapshot allowed = fetched.snapshot();
            assertThat(allowed.commits()).containsOnlyKeys(mainId);

            assertThatThrownBy(() -> fetched.fetch("origin", "feature"))
                    .isInstanceOfAny(IOException.class, TransportException.class);
            assertThat(allowed.difference(fetched.snapshot())).isNull();

            server.setUser(user("*"));
            fetched.fetch("origin", "feature");
            fetched.checkout("feature", "refs/remotes/origin/feature");
            assertThat(fetched.head()).isEqualTo(featureId);
            assertThat(fetched.snapshot().commits().get(featureId)).isEqualTo(before.commits().get(featureId));
            assertThat(before.difference(server.snapshot(remote))).isNull();
        }
    }

    private static String publish(GitWorkTree source, GitRemoteRepository remote, String branch, String content)
            throws Exception {
        source.writeFile("README.md", content);
        source.add("README.md");
        source.commit(branch);
        source.addRemote("origin", remote);
        source.pushRefs("origin", "refs/heads/main:refs/heads/" + branch);
        return source.head();
    }

    private static InternalUserImpl user(String branch) {
        AccessControl.Grant grant = new AccessControlDraft.Grant("matrix", new ArrayList<>())
                .addKey(AccessControl.GrantKey.REPOSITORY, "*")
                .addKey(AccessControl.GrantKey.BRANCH, branch).toAccessControl();
        return new InternalUserImpl("matrix", List.of(grant));
    }
}
