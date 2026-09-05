package pro.deta.orion.schema.orion;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record OrionAcmeConfiguration(
        boolean enabled,
        URI directoryUrl,
        String accountEmail,
        List<String> domains,
        String organization,
        Optional<OrionMaterialReference> accountMaterial,
        long authorizationTimeoutSeconds,
        long orderTimeoutSeconds,
        boolean agreeToTermsOfService,
        boolean allowRequestedDomains) {
    public OrionAcmeConfiguration {
        Objects.requireNonNull(directoryUrl, "ACME directory URL");
        accountMaterial = Objects.requireNonNullElseGet(accountMaterial, Optional::empty);
        domains = copyDomains(domains);
        if (authorizationTimeoutSeconds <= 0 || orderTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("ACME timeouts must be positive");
        }
        if (enabled && accountMaterial.isEmpty()) {
            throw new IllegalArgumentException("Enabled ACME requires account material");
        }
        if (enabled && domains.isEmpty()) {
            throw new IllegalArgumentException("Enabled ACME requires at least one domain");
        }
    }

    private static List<String> copyDomains(List<String> source) {
        List<String> domains = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        List<String> values = source == null ? List.of() : source;
        for (String domain : values) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("ACME domains must not be blank");
            }
            String value = domain.trim();
            if (!unique.add(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate ACME domain: " + value);
            }
            domains.add(value);
        }
        return List.copyOf(domains);
    }
}
