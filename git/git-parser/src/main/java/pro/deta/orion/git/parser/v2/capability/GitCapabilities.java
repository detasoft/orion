package pro.deta.orion.git.parser.v2.capability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GitCapabilities extends ArrayList<GitCapabilityValue> {
    public GitCapabilities() {}

    public GitCapabilities(Collection<? extends GitCapabilityValue> values) {
        super(List.copyOf(values));
    }

    public boolean has(GitCapability capability) {
        Objects.requireNonNull(capability, "capability");
        for (GitCapabilityValue entry : this) {
            if (entry.name().equals(capability.wireName())) {
                return true;
            }
        }
        return false;
    }

    public Optional<String> value(GitCapability capability) {
        Objects.requireNonNull(capability, "capability");
        GitCapabilityValue found = null;
        for (GitCapabilityValue entry : this) {
            if (entry.name().equals(capability.wireName())) {
                if (found != null) {
                    throw new IllegalStateException("Repeated capability: " + capability.wireName());
                }
                found = entry;
            }
        }
        return found == null ? Optional.empty() : found.value();
    }

    public List<String> values(GitCapability capability) {
        Objects.requireNonNull(capability, "capability");
        var result = new ArrayList<String>();
        for (GitCapabilityValue entry : this) {
            if (entry.name().equals(capability.wireName()) && entry.value().isPresent()) {
                result.add(entry.value().orElseThrow());
            }
        }
        return result;
    }
}
