package pro.deta.orion.git.parser.wire.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GitCapabilitySet(
        List<GitCapability> asList) {

    public GitCapabilitySet {
        Objects.requireNonNull(asList, "asList");
        asList = List.copyOf(asList);
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (GitCapability capability : asList) {
            names.add(capability.name());
        }
        return List.copyOf(names);
    }

    public Optional<String> value(String name) {
        Objects.requireNonNull(name, "name");
        for (GitCapability capability : asList) {
            if (name.equals(capability.name())) {
                return capability.value();
            }
        }
        return Optional.empty();
    }
}
