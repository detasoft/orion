package pro.deta.orion.git.parser.v2.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class GitCapabilities extends ArrayList<GitCapabilityValue> {
    public boolean has(GitCapability capability) {
        throw new RuntimeException("2 implement");
    }

    public Optional<String> value(GitCapability capability) {
        throw new RuntimeException("2 implement");
    }

    public List<String> values(GitCapability capability) {
        throw new RuntimeException("2 implement");
    }
}
