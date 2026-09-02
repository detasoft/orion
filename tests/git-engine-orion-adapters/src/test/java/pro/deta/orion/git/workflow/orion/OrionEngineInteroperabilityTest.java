package pro.deta.orion.git.workflow.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.workflow.GitCapability;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitInteroperabilityMatrixRunner;
import pro.deta.orion.git.workflow.GitMatrixInvocation;
import pro.deta.orion.git.workflow.GitScenario;
import pro.deta.orion.git.workflow.GitScenarioContext;
import pro.deta.orion.git.workflow.GitServer;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.git.workflow.GitWorkTree;
import pro.deta.orion.git.workflow.RepositorySnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OrionEngineInteroperabilityTest extends GitInteroperabilityMatrixRunner {
    private static final String UPDATED_CONTENT = "fast-forward\n";
    private static final Set<EnginePair> REQUIRED_PAIRS = Set.of(
            new EnginePair("orion", "jgit"),
            new EnginePair("orion", "git"),
            new EnginePair("orion", "orion"),
            new EnginePair("jgit", "orion"),
            new EnginePair("git", "orion"));
    private static final List<InvocationFactory> INVOCATIONS = List.of(
            invocation("orion", "jgit", OrionGitEngines::client, GitServers::jgit),
            invocation("orion", "git", OrionGitEngines::client, GitServers::git),
            invocation("orion", "orion", OrionGitEngines::client, OrionGitEngines::server),
            invocation("jgit", "orion", GitClients::jgit, OrionGitEngines::server),
            invocation("git", "orion", GitClients::git, OrionGitEngines::server));
    private static final GitScenario TRANSFER = new GitScenario() {
        @Override
        public String name() {
            return "deterministic-push-clone-fetch-pull";
        }

        @Override
        public Set<GitCapability> requiredCapabilities() {
            return GitCapability.all();
        }

        @Override
        public void run(GitScenarioContext context) throws Exception {
            runTransfer(context);
        }
    };

    @Test
    void declaresEveryRequiredOrionFacingPairWithoutFiltering() {
        assertThat(INVOCATIONS)
                .extracting(InvocationFactory::pair)
                .containsExactlyInAnyOrderElementsOf(REQUIRED_PAIRS);
    }

    @Override
    protected Stream<GitMatrixInvocation> matrixInvocations() {
        Set<EnginePair> actual = INVOCATIONS.stream()
                .map(InvocationFactory::pair)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actual.equals(REQUIRED_PAIRS)) {
            throw new IllegalStateException("Incomplete Orion Git matrix: expected="
                    + REQUIRED_PAIRS + ", actual=" + actual);
        }
        return INVOCATIONS.stream().map(InvocationFactory::create);
    }

    private static void runTransfer(GitScenarioContext context) throws Exception {
        try (GitWorkTree source = context.client().init(context.workTreeDirectory("source"))) {
            commit(source, "README.md", "initial\n", "initial");
            String initial = source.head();
            source.addRemote("origin", context.remote());
            source.push("origin", "main");

            try (GitWorkTree clone = context.client().clone(
                    context.remote(), context.workTreeDirectory("clone"))) {
                assertThat(clone.head()).isEqualTo(initial);

                commit(source, "README.md", UPDATED_CONTENT, "fast-forward");
                String updated = source.head();
                source.push("origin", "main");

                clone.fetch("origin");
                clone.updateRef("refs/heads/fetched", "refs/remotes/origin/main");
                clone.pull("origin", "main");
                assertThat(clone.head()).isEqualTo(updated);
                assertThat(clone.snapshot().commits()).isEqualTo(source.snapshot().commits());

                source.updateRef("refs/heads/feature", "HEAD");
                source.updateRef("refs/tags/v1", "HEAD");
                source.pushRefs(
                        "origin",
                        "refs/heads/feature:refs/heads/feature",
                        "refs/tags/v1:refs/tags/v1");
                assertRemoteState(context, source, initial, updated);
            }
        }
    }

    private static void assertRemoteState(
            GitScenarioContext context,
            GitWorkTree source,
            String initial,
            String updated) throws Exception {
        RepositorySnapshot remote = context.server().snapshot(context.remote());
        assertThat(remote.headSymref()).isEqualTo("refs/heads/main");
        assertThat(remote.refs())
                .containsEntry("refs/heads/main", updated)
                .containsEntry("refs/heads/feature", updated)
                .containsEntry("refs/tags/v1", updated);
        assertThat(remote.commits()).containsKeys(initial, updated);
        RepositorySnapshot.Commit commit = remote.commits().get(updated);
        assertThat(commit.parents()).containsExactly(initial);
        RepositorySnapshot.TreeEntry readme = commit.entries().get("README.md");
        assertThat(readme.mode()).isEqualTo(0100644);
        assertThat(readme.objectId()).hasSize(40);
        assertThat(readme.contentHash()).isEqualTo(sha256(UPDATED_CONTENT));
        assertThat(remote.difference(source.snapshot())).isNull();
    }

    private static void commit(
            GitWorkTree workTree,
            String path,
            String content,
            String message) throws Exception {
        workTree.writeFile(path, content);
        workTree.add(path);
        workTree.commit(message);
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static InvocationFactory invocation(
            String client,
            String server,
            ClientFactory clientFactory,
            ServerFactory serverFactory) {
        return new InvocationFactory(new EnginePair(client, server), clientFactory, serverFactory);
    }

    private record EnginePair(String client, String server) {
    }

    private record InvocationFactory(
            EnginePair pair,
            ClientFactory clientFactory,
            ServerFactory serverFactory) {
        private GitMatrixInvocation create() {
            try {
                return new GitMatrixInvocation(TRANSFER, clientFactory.create(), serverFactory.create());
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot create Git matrix invocation for " + pair, error);
            }
        }
    }

    @FunctionalInterface
    private interface ClientFactory {
        GitClient create();
    }

    @FunctionalInterface
    private interface ServerFactory {
        GitServer create() throws IOException;
    }
}
