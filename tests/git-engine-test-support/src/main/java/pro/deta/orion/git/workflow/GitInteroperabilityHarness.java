package pro.deta.orion.git.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class GitInteroperabilityHarness {
    private GitInteroperabilityHarness() {
    }

    public static void run(GitScenario scenario, GitClient client, GitServer server) throws Exception {
        Path directory = null;
        Throwable failure = null;
        try {
            try (server) {
                client.requireAvailable();
                requireCapabilities(scenario, client, server);
                directory = Files.createTempDirectory("git-matrix-");
                GitRemoteRepository remote = remoteRepository(scenario, server, directory);
                scenario.run(new GitScenarioContext(client, server, remote, directory));
            }
        } catch (Exception | Error error) {
            failure = error;
            attachDiagnostics(error, client, server);
            throw error;
        } finally {
            cleanup(directory, failure, client, server);
        }
    }

    private static GitRemoteRepository remoteRepository(
            GitScenario scenario,
            GitServer server,
            Path directory) throws Exception {
        if (scenario.remoteRepositoryMode() == GitScenario.RemoteRepositoryMode.MISSING) {
            return server.missingRemoteRepository(directory, GitScenarioContext.REMOTE_REPOSITORY_NAME);
        }
        return server.createRemoteRepository(directory, GitScenarioContext.REMOTE_REPOSITORY_NAME);
    }

    private static void attachDiagnostics(Throwable error, GitClient client, GitServer server) {
        for (Throwable suppressed : error.getSuppressed()) {
            if (suppressed instanceof EngineDiagnostics) {
                return;
            }
        }
        error.addSuppressed(new EngineDiagnostics(
                "Git engine diagnostics: client=" + diagnostics(client)
                        + "; server=" + diagnostics(server)));
    }

    private static String diagnostics(GitClient client) {
        try {
            return client.diagnostics();
        } catch (RuntimeException | Error error) {
            return "diagnostics unavailable: " + error;
        }
    }

    private static String diagnostics(GitServer server) {
        try {
            return server.diagnostics();
        } catch (RuntimeException | Error error) {
            return "diagnostics unavailable: " + error;
        }
    }

    private static void cleanup(
            Path directory,
            Throwable failure,
            GitClient client,
            GitServer server) throws Exception {
        if (directory == null) {
            return;
        }
        try {
            deleteRecursively(directory);
        } catch (Exception cleanupFailure) {
            if (failure != null) {
                failure.addSuppressed(cleanupFailure);
                return;
            }
            attachDiagnostics(cleanupFailure, client, server);
            throw cleanupFailure;
        }
    }

    private static void deleteRecursively(Path directory) throws Exception {
        List<Path> paths;
        try (var contents = Files.walk(directory)) {
            paths = contents.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private static void requireCapabilities(GitScenario scenario, GitClient client, GitServer server) {
        if (!client.capabilities().containsAll(scenario.requiredClientCapabilities())) {
            throw new IllegalStateException("Client " + client.name() + " lacks "
                    + scenario.requiredClientCapabilities());
        }
        if (!server.capabilities().containsAll(scenario.requiredServerCapabilities())) {
            throw new IllegalStateException("Server " + server.name() + " lacks "
                    + scenario.requiredServerCapabilities());
        }
    }

    private static final class EngineDiagnostics extends IllegalStateException {
        private EngineDiagnostics(String message) {
            super(message);
        }
    }
}
