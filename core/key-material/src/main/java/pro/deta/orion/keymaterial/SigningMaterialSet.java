package pro.deta.orion.keymaterial;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record SigningMaterialSet(KeyMaterialId active, List<KeyMaterialId> verification) {
    public SigningMaterialSet {
        Objects.requireNonNull(active, "active");
        LinkedHashSet<KeyMaterialId> retained = new LinkedHashSet<>();
        if (verification != null) {
            for (KeyMaterialId id : verification) {
                Objects.requireNonNull(id, "verification material");
                if (!id.equals(active)) retained.add(id);
            }
        }
        verification = List.copyOf(retained);
    }

    public List<KeyMaterialId> verificationIncludingActive() {
        LinkedHashSet<KeyMaterialId> all = new LinkedHashSet<>();
        all.add(active);
        all.addAll(verification);
        return List.copyOf(all);
    }
}
