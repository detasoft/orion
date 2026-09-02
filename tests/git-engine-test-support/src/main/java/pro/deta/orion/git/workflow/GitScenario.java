package pro.deta.orion.git.workflow;

import java.util.Set;

public interface GitScenario {
    String name();

    Set<GitCapability> requiredCapabilities();

    void run(GitScenarioContext context) throws Exception;
}
