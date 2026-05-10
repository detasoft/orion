package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.shredzone.acme4j.util.KeyPairUtils;
import pro.deta.orion.config.schema.AcmeConfig;
import pro.deta.orion.config.schema.OrionConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Singleton
public class AcmeCertificateService {
    private static final int RSA_KEY_SIZE = 2048;

    private final OrionConfiguration configuration;
    private final AcmeCertificateIssuer certificateIssuer;

    @Inject
    public AcmeCertificateService(OrionConfiguration configuration, AcmeCertificateIssuer certificateIssuer) {
        this.configuration = configuration;
        this.certificateIssuer = certificateIssuer;
    }

    public IssuedAcmeCertificate issue(IssueRequest request) throws IOException {
        IssueSettings settings = settingsFrom(request);
        KeyPair accountKeyPair = readOrCreateKeyPair(settings.accountKeyPath());
        KeyPair domainKeyPair = readOrCreateKeyPair(settings.domainKeyPath());
        IssuedAcmeCertificate certificate = certificateIssuer.issue(new AcmeCertificateIssueRequest(
                settings.directoryUrl(),
                settings.accountEmail(),
                accountKeyPair,
                domainKeyPair,
                settings.domains(),
                settings.organization(),
                Duration.ofSeconds(settings.authorizationTimeoutSeconds()),
                Duration.ofSeconds(settings.orderTimeoutSeconds()),
                settings.agreeToTermsOfService()));
        if (settings.persist()) {
            writeNginxCertificate(settings.certificatePath(), certificate);
        }
        return certificate;
    }

    public Optional<String> savedNginxCertificate() throws IOException {
        Path certificatePath = resolveConfiguredPath(acmeConfig().getCertificatePath(), "ACME certificate path is required");
        if (!Files.exists(certificatePath)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(certificatePath, StandardCharsets.UTF_8));
    }

    private IssueSettings settingsFrom(IssueRequest request) {
        IssueRequest effectiveRequest = request == null ? IssueRequest.EMPTY : request;
        AcmeConfig config = acmeConfig();

        List<String> domains = domainsFrom(effectiveRequest, config);
        requireRequestedDomainsAllowed(domains, effectiveRequest, config);

        return new IssueSettings(
                firstNotBlank(effectiveRequest.directoryUrl(), config.getDirectoryUrl(), "ACME directory URL is required"),
                firstNotBlank(effectiveRequest.accountEmail(), config.getAccountEmail(), "ACME account email is required"),
                domains,
                firstNonBlank(effectiveRequest.organization(), config.getOrganization()),
                secondsOrDefault(
                        effectiveRequest.authorizationTimeoutSeconds(),
                        config.getAuthorizationTimeoutSeconds(),
                        "ACME authorization timeout must be positive"),
                secondsOrDefault(
                        effectiveRequest.orderTimeoutSeconds(),
                        config.getOrderTimeoutSeconds(),
                        "ACME order timeout must be positive"),
                boolOrDefault(effectiveRequest.agreeToTermsOfService(), config.isAgreeToTermsOfService()),
                boolOrDefault(effectiveRequest.persist(), false),
                resolveConfiguredPath(config.getAccountKeyPath(), "ACME account key path is required"),
                resolveConfiguredPath(config.getDomainKeyPath(), "ACME domain key path is required"),
                resolveConfiguredPath(config.getCertificatePath(), "ACME certificate path is required"));
    }

    private AcmeConfig acmeConfig() {
        return configuration.getTransport().getHttps().getAcme();
    }

    private List<String> domainsFrom(IssueRequest request, AcmeConfig config) {
        List<String> requestedDomains = validatedDomainsOrEmpty(request.domains());
        if (!requestedDomains.isEmpty()) {
            return requestedDomains;
        }
        List<String> configuredDomains = validatedDomainsOrEmpty(config.getDomains());
        if (configuredDomains.isEmpty()) {
            throw new IllegalArgumentException("At least one ACME domain is required");
        }
        return configuredDomains;
    }

    private static void requireRequestedDomainsAllowed(
            List<String> domains,
            IssueRequest request,
            AcmeConfig config) {
        List<String> requestedDomains = validatedDomainsOrEmpty(request.domains());
        if (requestedDomains.isEmpty()) {
            return;
        }
        List<String> configuredDomains = validatedDomainsOrEmpty(config.getDomains());
        if (config.isAllowRequestedDomains() || requestedDomains.equals(configuredDomains)) {
            return;
        }
        throw new IllegalArgumentException("Requested ACME domains are not allowed by configuration");
    }

    private Path resolveConfiguredPath(String path, String message) {
        String configuredPath = firstNotBlank(null, path, message);
        Path result = Path.of(configuredPath);
        if (result.isAbsolute()) {
            return result;
        }
        String baseDir = configuration.getBootstrap().getBaseDir();
        if (baseDir == null || baseDir.isBlank()) {
            return result;
        }
        return Path.of(baseDir).resolve(result);
    }

    private KeyPair readOrCreateKeyPair(Path path) throws IOException {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                return KeyPairUtils.readKeyPair(reader);
            }
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        KeyPair keyPair = generateKeyPair();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            KeyPairUtils.writeKeyPair(keyPair, writer);
        }
        return keyPair;
    }

    private void writeNginxCertificate(Path path, IssuedAcmeCertificate certificate) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, certificate.nginxPem(), StandardCharsets.UTF_8);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot generate ACME key pair", e);
        }
    }

    private static List<String> validatedDomainsOrEmpty(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) {
                throw new IllegalArgumentException("ACME domain is required");
            }
            result.add(domain);
        }
        return List.copyOf(result);
    }

    private static String firstNotBlank(String requested, String configured, String message) {
        String result = firstNonBlank(requested, configured);
        if (result == null) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    private static String firstNonBlank(String requested, String configured) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return null;
    }

    private static long secondsOrDefault(Long requested, long configured, String message) {
        long result = requested == null ? configured : requested;
        if (result <= 0) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    private static boolean boolOrDefault(Boolean requested, boolean configured) {
        return requested == null ? configured : requested;
    }

    public record IssueRequest(
            String directoryUrl,
            String accountEmail,
            List<String> domains,
            String organization,
            Long authorizationTimeoutSeconds,
            Long orderTimeoutSeconds,
            Boolean agreeToTermsOfService,
            Boolean persist) {
        static final IssueRequest EMPTY = new IssueRequest(null, null, null, null, null, null, null, null);
    }

    private record IssueSettings(
            String directoryUrl,
            String accountEmail,
            List<String> domains,
            String organization,
            long authorizationTimeoutSeconds,
            long orderTimeoutSeconds,
            boolean agreeToTermsOfService,
            boolean persist,
            Path accountKeyPath,
            Path domainKeyPath,
            Path certificatePath) {
    }
}
