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
        if (active.purpose() != KeyMaterialPurpose.SERVER_SIGNING) {
            throw new IllegalArgumentException("Active material must have SERVER_SIGNING purpose");
        }
        if (verification == null) {
            verification = List.of();
        }
        LinkedHashSet<KeyMaterialDescriptor> validated = new LinkedHashSet<>();
        Map<KeyMaterialAlias, KeyMaterialDescriptor> descriptorsByAlias = new LinkedHashMap<>();
        descriptorsByAlias.put(active.alias(), active);
        for (KeyMaterialDescriptor candidate : verification) {
            requireCompatible(active, candidate);
            KeyMaterialDescriptor existing = descriptorsByAlias.get(candidate.alias());
            if (existing != null) {
                if (!existing.equals(candidate)) {
                    throw conflictingAlias(candidate);
                }
                continue;
            }
            if (candidate.version().compareTo(active.version()) >= 0) {
                throw new IllegalArgumentException(
                        "Verification material must be older than active material");
            }
            descriptorsByAlias.put(candidate.alias(), candidate);
            validated.add(candidate);
        }
        verification = List.copyOf(validated);
    }

    public SigningMaterialSet rotateTo(KeyMaterialDescriptor target) {
        requireCompatible(active, target);
        if (active.alias().equals(target.alias())) {
            throw aliasAlreadyBelongs(target);
        }
        for (KeyMaterialDescriptor candidate : verification) {
            if (candidate.alias().equals(target.alias())) {
                throw aliasAlreadyBelongs(target);
            }
        }
        if (target.version().compareTo(active.version()) <= 0) {
            throw new IllegalArgumentException(
                    "Rotation target must have a newer version than active material");
        }
        LinkedHashSet<KeyMaterialDescriptor> retained = new LinkedHashSet<>();
        retained.add(active);
        retained.addAll(verification);
        retained.remove(target);
        return new SigningMaterialSet(target, List.copyOf(retained));
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
        if (candidate.purpose() != expected.purpose()) {
            throw new IllegalArgumentException("Signing material purpose does not match active material");
        }
        if (candidate.algorithm() != expected.algorithm()) {
            throw new IllegalArgumentException("Signing material algorithm does not match active material");
        }
        if (!candidate.scope().equals(expected.scope())) {
            throw new IllegalArgumentException("Signing material scope does not match active material");
        }
    }

    private static IllegalArgumentException aliasAlreadyBelongs(KeyMaterialDescriptor target) {
        return new IllegalArgumentException(
                "Rotation target alias already belongs to existing material: " + target.alias().value());
    }

    private static IllegalArgumentException conflictingAlias(KeyMaterialDescriptor descriptor) {
        return new IllegalArgumentException(
                "Signing material alias has conflicting descriptors: " + descriptor.alias().value());
    }
}
