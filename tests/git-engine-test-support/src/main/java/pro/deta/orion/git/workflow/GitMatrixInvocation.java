package pro.deta.orion.git.workflow;

import java.util.Objects;

public record GitMatrixInvocation(GitScenario scenario, GitClient client, GitServer server) {
    public GitMatrixInvocation {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(server, "server");
    }

    @Override
    public String toString() {
        return scenario.name() + " [" + client.engine() + " -> " + server.engine() + "]";
    }
}
