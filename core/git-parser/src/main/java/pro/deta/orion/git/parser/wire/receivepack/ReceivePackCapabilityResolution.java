package pro.deta.orion.git.parser.wire.receivepack;

import java.util.List;
import java.util.Objects;

public record ReceivePackCapabilityResolution(
        List<ReceivePackCapability> selected,
        List<String> ignored,
        List<String> rejected) {

    public ReceivePackCapabilityResolution {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(ignored, "ignored");
        Objects.requireNonNull(rejected, "rejected");
        selected = List.copyOf(selected);
        ignored = List.copyOf(ignored);
        rejected = List.copyOf(rejected);
    }

    public boolean accepted() {
        return rejected.isEmpty();
    }

    public boolean uses(ReceivePackCapability capability) {
        return selected.contains(capability);
    }
}
