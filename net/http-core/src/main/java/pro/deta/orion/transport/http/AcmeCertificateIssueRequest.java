package pro.deta.orion.transport.http;

import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public record AcmeCertificateIssueRequest(
        String directoryUrl,
        String accountEmail,
        KeyPair accountKeyPair,
        KeyPair domainKeyPair,
        List<String> domains,
        String organization,
        Duration authorizationTimeout,
        Duration orderTimeout,
        boolean agreeToTermsOfService,
        String eabKeyId,
        char[] eabHmacKey) {
    private static final Duration DEFAULT_AUTHORIZATION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_ORDER_TIMEOUT = Duration.ofSeconds(60);

    public AcmeCertificateIssueRequest {
        if ((eabKeyId == null) != (eabHmacKey == null)) {
            throw new IllegalArgumentException("EAB requires both key id and HMAC key");
        }
        requireNotBlank(directoryUrl, "ACME directory URL is required");
        requireNotBlank(accountEmail, "ACME account email is required");
        if (accountKeyPair == null) {
            throw new IllegalArgumentException("ACME account key pair is required");
        }
        if (domainKeyPair == null) {
            throw new IllegalArgumentException("ACME domain key pair is required");
        }
        domains = validatedDomains(domains);
        if (authorizationTimeout == null) {
            authorizationTimeout = DEFAULT_AUTHORIZATION_TIMEOUT;
        }
        if (orderTimeout == null) {
            orderTimeout = DEFAULT_ORDER_TIMEOUT;
        }
        if (authorizationTimeout.isZero() || authorizationTimeout.isNegative()) {
            throw new IllegalArgumentException("ACME authorization timeout must be positive");
        }
        if (orderTimeout.isZero() || orderTimeout.isNegative()) {
            throw new IllegalArgumentException("ACME order timeout must be positive");
        }
    }

    @Override
    public String toString() {
        return "AcmeCertificateIssueRequest[private material=<redacted>]";
    }

    private static List<String> validatedDomains(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            throw new IllegalArgumentException("At least one ACME domain is required");
        }
        List<String> result = new ArrayList<>();
        for (String domain : domains) {
            requireNotBlank(domain, "ACME domain is required");
            result.add(domain);
        }
        return List.copyOf(result);
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
