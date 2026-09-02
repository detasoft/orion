package pro.deta.orion.git.workflow;

import java.nio.file.Files;
import java.nio.file.Path;

public final class GitInteroperabilityHarness {
    private GitInteroperabilityHarness() {
    }

    public static void run(GitScenario scenario, GitClient client, GitServer server) throws Exception {
        requireCapabilities(scenario, client, server);
        Path directory = Files.createTempDirectory("git-matrix-");
        try (server) {
            GitRemoteRepository remote = server.createRemoteRepository(
                    directory, GitScenarioContext.REMOTE_REPOSITORY_NAME);
            scenario.run(new GitScenarioContext(client, server, remote, directory));
        }
    }

    private static void requireCapabilities(GitScenario scenario, GitClient client, GitServer server) {
        if (!client.capabilities().containsAll(scenario.requiredCapabilities())) {
            throw new IllegalStateException("Client " + client.name() + " lacks "
                    + scenario.requiredCapabilities());
        }
        if (!server.capabilities().containsAll(scenario.requiredCapabilities())) {
            throw new IllegalStateException("Server " + server.name() + " lacks "
                    + scenario.requiredCapabilities());
        }
    }
}
