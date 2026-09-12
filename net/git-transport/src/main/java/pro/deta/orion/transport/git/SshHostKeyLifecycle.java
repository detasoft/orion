package pro.deta.orion.transport.git;

import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.SshHostKeyCapability;
import pro.deta.orion.keymaterial.SshHostKeyMaterial;
import pro.deta.orion.keymaterial.SshHostKeyReference;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns SSH transport host-key creation and selection policy.
 */
public final class SshHostKeyLifecycle {
    private static final KeyMaterialVersion INITIAL_VERSION = new KeyMaterialVersion(1);

    private SshHostKeyLifecycle() {
    }

    public static SshHostKeyCapability open(
            SshHostKeyMaterial material,
            List<SshHostKeyReference> references) throws IOException, GeneralSecurityException {
        if (material == null) {
            throw new IllegalArgumentException("SSH host key material must not be null");
        }
        if (references == null) {
            throw new IllegalArgumentException("SSH host key references must not be null");
        }
        List<KeyMaterialDescriptor> available = material.available();
        if (references.isEmpty() && available.isEmpty()) {
            material.generateIfMissing(Map.of(
                    defaultKey(material, "ssh-host-rsa", KeyMaterialAlgorithm.RSA),
                    KeyMaterialConstants.RSA_KEY_SIZE_BITS,
                    defaultKey(material, "ssh-host-ec", KeyMaterialAlgorithm.EC),
                    KeyMaterialConstants.EC_KEY_SIZE_BITS));
            available = material.available();
        }
        List<KeyMaterialDescriptor> selected = references.isEmpty()
                ? available
                : select(available, references);
        return material.load(selected);
    }

    private static KeyMaterialDescriptor defaultKey(
            SshHostKeyMaterial material,
            String logicalAlias,
            KeyMaterialAlgorithm algorithm) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(logicalAlias + "-v" + INITIAL_VERSION.value()),
                KeyMaterialPurpose.SSH_HOST,
                algorithm,
                INITIAL_VERSION,
                material.scope());
    }

    private static List<KeyMaterialDescriptor> select(
            List<KeyMaterialDescriptor> available,
            List<SshHostKeyReference> references) throws GeneralSecurityException {
        ArrayList<KeyMaterialDescriptor> selected = new ArrayList<>();
        for (SshHostKeyReference reference : references) {
            if (reference == null) {
                throw new IllegalArgumentException("SSH host key reference must not be null");
            }
            KeyMaterialDescriptor match = exactMatch(available, reference.alias());
            if (match == null && !isConcreteAlias(reference.alias())) {
                match = latestLogicalMatch(available, reference.alias());
            }
            if (match == null) {
                throw new GeneralSecurityException(
                        "SSH host key reference not found: " + reference.alias());
            }
            if (selected.contains(match)) {
                throw new IllegalArgumentException(
                        "Duplicate SSH host key reference: " + reference.alias());
            }
            selected.add(match);
        }
        return List.copyOf(selected);
    }

    private static KeyMaterialDescriptor exactMatch(
            List<KeyMaterialDescriptor> available,
            String alias) {
        for (KeyMaterialDescriptor candidate : available) {
            if (alias.equals(candidate.alias().value())) {
                return candidate;
            }
        }
        return null;
    }

    private static KeyMaterialDescriptor latestLogicalMatch(
            List<KeyMaterialDescriptor> available,
            String alias) throws GeneralSecurityException {
        KeyMaterialDescriptor match = null;
        for (KeyMaterialDescriptor candidate : available) {
            if (alias.equals(logicalAlias(candidate))
                    && (match == null || candidate.version().compareTo(match.version()) > 0)) {
                match = candidate;
            }
        }
        return match;
    }

    private static boolean isConcreteAlias(String alias) {
        int versionMarker = alias.lastIndexOf("-v");
        if (versionMarker <= 0 || versionMarker + 2 == alias.length()) {
            return false;
        }
        for (int index = versionMarker + 2; index < alias.length(); index++) {
            if (!Character.isDigit(alias.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String logicalAlias(KeyMaterialDescriptor descriptor)
            throws GeneralSecurityException {
        String suffix = "-v" + descriptor.version().value();
        String concreteAlias = descriptor.alias().value();
        if (!concreteAlias.endsWith(suffix) || concreteAlias.length() == suffix.length()) {
            throw new GeneralSecurityException(
                    "SSH host key alias does not identify its version: " + concreteAlias);
        }
        return concreteAlias.substring(0, concreteAlias.length() - suffix.length());
    }
}
