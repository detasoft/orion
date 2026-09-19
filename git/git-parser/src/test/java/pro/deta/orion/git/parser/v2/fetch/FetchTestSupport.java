package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;

public final class FetchTestSupport {
    private FetchTestSupport() {}

    public static GitCapabilities capabilities(GitCapability... capabilities) {
        var result = new GitCapabilities();
        for (GitCapability capability : capabilities) {
            result.add(GitCapabilityValue.value(capability));
        }
        return result;
    }
}
