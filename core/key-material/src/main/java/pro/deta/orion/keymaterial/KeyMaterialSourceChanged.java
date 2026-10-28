package pro.deta.orion.keymaterial;

import pro.deta.orion.event.type.OrionEvent;

import java.util.Objects;

/** Identifies source content to reload without exposing serialized material in events. */
public final class KeyMaterialSourceChanged extends OrionEvent {
    private final KeyMaterialContentSource source;
    private final String revision;

    public KeyMaterialSourceChanged(KeyMaterialContentSource source, String revision) {
        this.source = Objects.requireNonNull(source, "source");
        this.revision = Objects.requireNonNull(revision, "revision");
    }

    public KeyMaterialContentSource source() {
        return source;
    }

    public String revision() {
        return revision;
    }
}
