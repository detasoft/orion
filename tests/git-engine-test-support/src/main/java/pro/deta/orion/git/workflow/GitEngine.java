package pro.deta.orion.git.workflow;

import java.util.Objects;

public record GitEngine(String name) {
    public GitEngine {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Git engine name must not be blank");
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
