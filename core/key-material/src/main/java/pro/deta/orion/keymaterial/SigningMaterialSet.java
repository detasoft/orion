package pro.deta.orion.keymaterial;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record SigningMaterialSet(KeyMaterialDescriptor active, List<KeyMaterialDescriptor> verification) {
    public SigningMaterialSet {
        if (active == null) {
            throw new IllegalArgumentException("Active signing material must not be null");
        }
        if (!active.purposes().contains(KeyMaterialPurpose.SERVER_SIGNING)
                || active.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Active material must have SERVER_SIGNING purpose");
        }
        if (verification == null) {
            verification = List.of();
        }
        LinkedHashSet<KeyMaterialDescriptor> validated = new LinkedHashSet<>();
        Map<KeyMaterialId, KeyMaterialDescriptor> descriptorsById = new LinkedHashMap<>();
        descriptorsById.put(active.id(), active);
        for (KeyMaterialDescriptor candidate : verification) {
            requireCompatible(active, candidate);
            KeyMaterialDescriptor existing = descriptorsById.get(candidate.id());
            if (existing != null) {
                if (!existing.equals(candidate)) {
                    throw conflictingAlias(candidate);
                }
                continue;
            }
            descriptorsById.put(candidate.id(), candidate);
            validated.add(candidate);
        }
        verification = List.copyOf(validated);
    }

    public List<KeyMaterialDescriptor> verificationIncludingActive() {
        LinkedHashSet<KeyMaterialDescriptor> all = new LinkedHashSet<>();
        all.add(active);
        all.addAll(verification);
        return List.copyOf(all);
    }

    private static void requireCompatible(KeyMaterialDescriptor expected, KeyMaterialDescriptor candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Signing material must not be null");
        }
        if (!candidate.purposes().contains(KeyMaterialPurpose.SERVER_SIGNING)
                || candidate.purposes().contains(KeyMaterialPurpose.TRUST_ANCHOR)) {
            throw new IllegalArgumentException("Signing material purpose does not match active material");
        }
        if (candidate.algorithm() != expected.algorithm()) {
            throw new IllegalArgumentException("Signing material algorithm does not match active material");
        }
    }

    private static IllegalArgumentException conflictingAlias(KeyMaterialDescriptor descriptor) {
        return new IllegalArgumentException(
                "Signing material alias has conflicting descriptors: " + descriptor.alias());
    }
}
