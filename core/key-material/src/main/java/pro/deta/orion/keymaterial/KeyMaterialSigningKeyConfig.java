package pro.deta.orion.keymaterial;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public record KeyMaterialSigningKeyConfig(String activeAlias, List<String> verificationAliases) {
    public KeyMaterialSigningKeyConfig {
        requireAlias(activeAlias, "Active signing key alias must not be empty");
        if (verificationAliases == null) {
            verificationAliases = List.of();
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String alias : verificationAliases) {
            requireAlias(alias, "Verification signing key alias must not be empty");
            if (!activeAlias.equals(alias)) {
                aliases.add(alias);
            }
        }
        verificationAliases = List.copyOf(aliases);
    }

    public static KeyMaterialSigningKeyConfig active(String activeAlias) {
        return new KeyMaterialSigningKeyConfig(activeAlias, List.of());
    }

    public KeyMaterialSigningKeyConfig rotateTo(String newActiveAlias) {
        requireAlias(newActiveAlias, "New active signing key alias must not be empty");
        List<String> aliases = new ArrayList<>();
        if (!newActiveAlias.equals(activeAlias)) {
            aliases.add(activeAlias);
        }
        for (String alias : verificationAliases) {
            if (!newActiveAlias.equals(alias) && !aliases.contains(alias)) {
                aliases.add(alias);
            }
        }
        return new KeyMaterialSigningKeyConfig(newActiveAlias, aliases);
    }

    List<String> verificationAliasesIncludingActive() {
        List<String> aliases = new ArrayList<>();
        aliases.add(activeAlias);
        for (String alias : verificationAliases) {
            if (!aliases.contains(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    private static void requireAlias(String alias, String message) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
