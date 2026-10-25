package pro.deta.orion.keymaterial;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

/** Identifies the exact source revision available for key material reload. */
public final class KeyMaterialUpdated extends OrionEvent {
    private final String revision;

    public KeyMaterialUpdated(String revision) {
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    public String revision() {
        return revision;
    }
}
