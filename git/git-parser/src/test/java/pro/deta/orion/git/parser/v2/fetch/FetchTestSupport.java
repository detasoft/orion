package pro.deta.orion.git.parser.v2.fetch;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FetchTestSupport {
    private FetchTestSupport() {}

    public static GitStorageApi storage(Path directory) {
        try {
            return new GitStorageApi(
                    Files.createTempDirectory(directory, "repository-"));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    public static GitCapabilities capabilities(GitCapability... capabilities) {
        var result = new GitCapabilities();
        for (GitCapability capability : capabilities) {
            result.add(GitCapabilityValue.value(capability));
        }
        return result;
    }
}
