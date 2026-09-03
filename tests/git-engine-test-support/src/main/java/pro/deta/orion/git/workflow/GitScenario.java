package pro.deta.orion.git.workflow;

import java.util.Set;

public interface GitScenario {
    enum RemoteRepositoryMode {
        PROVISIONED,
        MISSING
    }

    String name();

    Set<GitCapability> requiredCapabilities();

    default Set<GitCapability> requiredClientCapabilities() {
        return requiredCapabilities();
    }

    default Set<GitCapability> requiredServerCapabilities() {
        return requiredCapabilities();
    }

    default ExpectedRepositoryState expectedTerminalState() {
        return ExpectedRepositoryState.unspecified();
    }

    default RemoteRepositoryMode remoteRepositoryMode() {
        return RemoteRepositoryMode.PROVISIONED;
    }

    void run(GitScenarioContext context) throws Exception;
}
