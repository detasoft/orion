package pro.deta.orion.git.workflow.orion;

import pro.deta.orion.git.workflow.GitCapability;
import pro.deta.orion.git.workflow.GitClient;
import pro.deta.orion.git.workflow.GitServer;

import java.util.Set;

import static pro.deta.orion.git.workflow.GitCapability.CLONE;
import static pro.deta.orion.git.workflow.GitCapability.COMMIT;
import static pro.deta.orion.git.workflow.GitCapability.FAST_FORWARD_PULL;
import static pro.deta.orion.git.workflow.GitCapability.FETCH;
import static pro.deta.orion.git.workflow.GitCapability.INITIALIZE;
import static pro.deta.orion.git.workflow.GitCapability.PUSH;

public final class OrionGitEngines {
    static final Set<GitCapability> CAPABILITIES = Set.of(
            INITIALIZE,
            CLONE,
            COMMIT,
            PUSH,
            FETCH,
            FAST_FORWARD_PULL);

    private OrionGitEngines() {
    }

    public static GitClient client() {
        return new OrionGitClient();
    }

    public static GitServer server() {
        return new OrionGitServer();
    }
}
