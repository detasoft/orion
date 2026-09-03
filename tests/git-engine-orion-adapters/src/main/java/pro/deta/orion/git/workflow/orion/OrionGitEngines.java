package pro.deta.orion.git.workflow.orion;

import pro.deta.orion.git.workflow.GitCapability;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitServer;

import java.util.EnumSet;
import java.util.Set;

public final class OrionGitEngines {
    static final Set<GitCapability> CLIENT_CAPABILITIES = GitCapability.symmetric();
    static final Set<GitCapability> SERVER_CAPABILITIES = serverCapabilities();

    private OrionGitEngines() {
    }

    public static GitClient client() {
        return new OrionGitClient();
    }

    public static GitServer server() {
        return new OrionGitServer();
    }

    private static Set<GitCapability> serverCapabilities() {
        Set<GitCapability> capabilities = EnumSet.copyOf(GitCapability.symmetric());
        capabilities.add(GitCapability.CREATE_MISSING_REPOSITORY_ON_PUSH);
        return Set.copyOf(capabilities);
    }
}
