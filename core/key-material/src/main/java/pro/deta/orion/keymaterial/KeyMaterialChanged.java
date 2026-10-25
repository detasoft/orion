package pro.deta.orion.keymaterial;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Set;

/** Reports the aliases changed by a committed key material modification. */
public final class KeyMaterialChanged extends OrionEvent {
    private final Set<String> resources;

    public KeyMaterialChanged(Set<String> resources) {
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("Changed key material resources must not be empty");
        }
        this.resources = Set.copyOf(resources);
    }

    public Set<String> resources() {
        return resources;
    }
}
