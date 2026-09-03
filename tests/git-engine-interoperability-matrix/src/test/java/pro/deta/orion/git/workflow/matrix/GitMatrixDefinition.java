package pro.deta.orion.git.workflow.matrix;

import pro.deta.orion.git.workflow.GitClients;
import pro.deta.orion.git.workflow.GitMatrixInvocation;
import pro.deta.orion.git.workflow.GitScenario;
import pro.deta.orion.git.workflow.GitServers;
import pro.deta.orion.git.workflow.GitWorkflowScenarios;
import pro.deta.orion.git.workflow.orion.OrionGitEngines;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class GitMatrixDefinition {
    private static final List<EnginePair> REQUIRED_PAIRS = List.of(
            pair("orion", "orion", OrionGitEngines::client, OrionGitEngines::server),
            pair("orion", "jgit", OrionGitEngines::client, GitServers::jgit),
            pair("orion", "git", OrionGitEngines::client, GitServers::git),
            pair("jgit", "orion", GitClients::jgit, OrionGitEngines::server),
            pair("git", "orion", GitClients::git, OrionGitEngines::server));
    private static final List<EnginePair> CONTROL_PAIRS = List.of(
            pair("jgit", "jgit", GitClients::jgit, GitServers::jgit),
            pair("jgit", "git", GitClients::jgit, GitServers::git),
            pair("git", "jgit", GitClients::git, GitServers::jgit),
            pair("git", "git", GitClients::git, GitServers::git));

    private GitMatrixDefinition() {
    }

    static List<GitMatrixInvocation> requiredCases() {
        List<GitMatrixInvocation> cases = cases(REQUIRED_PAIRS);
        requireCompleteCoverage(cases);
        return cases;
    }

    static List<GitMatrixInvocation> controlCases() {
        return cases(CONTROL_PAIRS);
    }

    static void requireCompleteCoverage(List<GitMatrixInvocation> cases) {
        Set<String> expected = expectedNames(REQUIRED_PAIRS);
        Set<String> actual = new HashSet<>();
        for (GitMatrixInvocation matrixCase : cases) {
            actual.add(matrixCase.displayName());
        }
        if (cases.size() != expected.size() || !actual.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new HashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalStateException("Incomplete required Git matrix coverage: expected="
                    + expected.size() + ", actual=" + cases.size() + ", missing=" + missing
                    + ", unexpected=" + unexpected);
        }
    }

    private static List<GitMatrixInvocation> cases(List<EnginePair> pairs) {
        List<GitMatrixInvocation> cases = new ArrayList<>();
        for (EnginePair pair : pairs) {
            for (GitScenario scenario : GitWorkflowScenarios.catalog()) {
                cases.add(new GitMatrixInvocation(
                        scenario,
                        pair.clientName(),
                        pair.serverName(),
                        pair.clientFactory(),
                        pair.serverFactory()));
            }
        }
        return List.copyOf(cases);
    }

    private static Set<String> expectedNames(List<EnginePair> pairs) {
        Set<String> names = new HashSet<>();
        for (EnginePair pair : pairs) {
            for (GitScenario scenario : GitWorkflowScenarios.catalog()) {
                names.add(scenario.name() + " [" + pair.name() + "]");
            }
        }
        return names;
    }

    private static EnginePair pair(
            String clientName,
            String serverName,
            GitMatrixInvocation.ClientFactory clientFactory,
            GitMatrixInvocation.ServerFactory serverFactory) {
        return new EnginePair(clientName, serverName, clientFactory, serverFactory);
    }

    private record EnginePair(
            String clientName,
            String serverName,
            GitMatrixInvocation.ClientFactory clientFactory,
            GitMatrixInvocation.ServerFactory serverFactory) {
        private String name() {
            return clientName + " -> " + serverName;
        }
    }
}
