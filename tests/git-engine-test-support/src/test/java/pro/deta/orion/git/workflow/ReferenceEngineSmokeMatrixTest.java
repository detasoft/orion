package pro.deta.orion.git.workflow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceEngineSmokeMatrixTest extends GitInteroperabilityMatrixRunner {
    private static final GitScenario SMOKE = new GitScenario() {
        @Override
        public String name() {
            return "reference-engine-smoke";
        }

        @Override
        public Set<GitCapability> requiredCapabilities() {
            return GitCapability.symmetric();
        }

        @Override
        public void run(GitScenarioContext context) throws Exception {
            try (GitWorkTree source = context.client().init(context.workTreeDirectory("source"))) {
                source.writeFile("README.md", "initial\n");
                source.add("README.md");
                source.commit("initial");
                source.addRemote("origin", context.remote());
                source.push("origin", "main");

                try (GitWorkTree clone = context.client().clone(
                        context.remote(), context.workTreeDirectory("clone"))) {
                    assertThat(clone.head()).isEqualTo(source.head());

                    source.writeFile("README.md", "fast-forward\n");
                    source.add("README.md");
                    source.commit("fast-forward");
                    source.push("origin", "main");

                    clone.fetch("origin");
                    clone.updateRef("refs/heads/fetched", "refs/remotes/origin/main");
                    clone.pull("origin", "main");
                    assertThat(clone.head()).isEqualTo(source.head());

                    source.updateRef("refs/heads/feature", "HEAD");
                    source.updateRef("refs/tags/v1", "HEAD");
                    source.pushRefs(
                            "origin",
                            "refs/heads/feature:refs/heads/feature",
                            "refs/tags/v1:refs/tags/v1");
                }

                RepositorySnapshot remote = context.server().snapshot(context.remote());
                assertThat(remote.refs())
                        .containsEntry("refs/heads/main", source.head())
                        .containsEntry("refs/heads/feature", source.head())
                        .containsEntry("refs/tags/v1", source.head());
            }
        }
    };

    @Override
    protected Stream<GitMatrixInvocation> matrixInvocations() {
        try {
            return Stream.of(
                    invocation(GitClients.jgit(), GitServers.jgit()),
                    invocation(GitClients.jgit(), GitServers.git()),
                    invocation(GitClients.git(), GitServers.jgit()),
                    invocation(GitClients.git(), GitServers.git()));
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot start reference Git server", error);
        }
    }

    private static GitMatrixInvocation invocation(GitClient client, GitServer server) {
        return new GitMatrixInvocation(SMOKE, client, server);
    }
}
