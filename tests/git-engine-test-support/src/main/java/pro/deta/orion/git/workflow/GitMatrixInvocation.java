package pro.deta.orion.git.workflow;

import java.util.Objects;

public record GitMatrixInvocation(
        GitScenario scenario,
        String clientName,
        String serverName,
        ClientFactory clientFactory,
        ServerFactory serverFactory) {
    public GitMatrixInvocation {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(clientFactory, "clientFactory");
        Objects.requireNonNull(serverFactory, "serverFactory");
    }

    public GitMatrixInvocation(GitScenario scenario, GitClient client, GitServer server) {
        this(
                scenario,
                Objects.requireNonNull(client, "client").engine().name(),
                Objects.requireNonNull(server, "server").engine().name(),
                () -> client,
                () -> server);
    }

    public String pairName() {
        return clientName + " -> " + serverName;
    }

    public String displayName() {
        return scenario.name() + " [" + pairName() + "]";
    }

    public void run() throws Exception {
        GitClient client = Objects.requireNonNull(clientFactory.create(), "clientFactory result");
        requireEngine("client", clientName, client.engine().name());
        GitServer server = Objects.requireNonNull(serverFactory.create(), "serverFactory result");
        if (!serverName.equals(server.engine().name())) {
            try (server) {
                requireEngine("server", serverName, server.engine().name());
            }
        }
        GitInteroperabilityHarness.run(scenario, client, server);
    }

    private static void requireEngine(String role, String declared, String actual) {
        if (!declared.equals(actual)) {
            throw new IllegalStateException("Git matrix declared " + role + "=" + declared
                    + " but factory created actual=" + actual);
        }
    }

    @Override
    public String toString() {
        return displayName();
    }

    @FunctionalInterface
    public interface ClientFactory {
        GitClient create();
    }

    @FunctionalInterface
    public interface ServerFactory {
        GitServer create() throws Exception;
    }
}
