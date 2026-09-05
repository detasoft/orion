package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.AcmeKeyMaterial;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.AcmeMaterialConfiguration;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.TrustedCertificateDescriptor;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Singleton
public class AcmeCertificateService {
    private static final int RSA_KEY_SIZE = KeyMaterialConstants.RSA_KEY_SIZE_BITS;

    private final String clusterId;
    private final OrionDesiredState desiredState;
    private final AcmeKeyMaterialCapability keyMaterial;
    private final AcmeCertificateIssuer certificateIssuer;

    @Inject
    public AcmeCertificateService(
            OrionConfiguration bootstrapConfiguration,
            OrionDesiredState desiredState,
            AcmeKeyMaterialCapability keyMaterial,
            AcmeCertificateIssuer certificateIssuer) {
        this.clusterId = required(
                bootstrapConfiguration.getBootstrap().getKeyMaterial().getClusterId(),
                "Key material cluster id is required");
        this.desiredState = desiredState;
        this.keyMaterial = keyMaterial;
        this.certificateIssuer = certificateIssuer;
    }

    public IssuedAcmeCertificate issue(IssueRequest request) {
        IssueSettings settings = settingsFrom(request);
        AcmeKeyMaterial keys;
        try {
            keys = keyMaterial.acquire(settings.material(), RSA_KEY_SIZE, RSA_KEY_SIZE);
        } catch (IOException | GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot acquire ACME key material", failure);
        }
        IssuedAcmeCertificate issued = certificateIssuer.issue(new AcmeCertificateIssueRequest(
                settings.directoryUrl(),
                settings.accountEmail(),
                keys.accountKeyPair(),
                keys.domainKeyPair(),
                settings.domains(),
                settings.organization(),
                Duration.ofSeconds(settings.authorizationTimeoutSeconds()),
                Duration.ofSeconds(settings.orderTimeoutSeconds()),
                settings.agreeToTermsOfService()));
        try {
            CertificateMaterial certificates = certificateMaterial(settings.material(), issued.certificateChain());
            keyMaterial.installCertificateChain(
                    settings.material(),
                    certificates.chain(),
                    certificates.issuerTrustAnchor());
            return new IssuedAcmeCertificate(issued.domains(), certificates.chain());
        } catch (IOException | GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot store issued ACME certificate", failure);
        }
    }

    public Optional<IssuedAcmeCertificate> savedCertificate() {
        IssueSettings settings = settingsFrom(IssueRequest.EMPTY);
        try {
            return keyMaterial.certificateChain(settings.material())
                    .map(chain -> new IssuedAcmeCertificate(settings.domains(), chain));
        } catch (GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot read issued ACME certificate", failure);
        }
    }

    private CertificateMaterial certificateMaterial(
            AcmeMaterialConfiguration configuration,
            List<X509Certificate> issuedChain) throws GeneralSecurityException {
        List<X509Certificate> chain = new ArrayList<>(issuedChain);
        Optional<X509Certificate> issuer = Optional.empty();
        if (chain.size() > 1 && isTrustAnchor(chain.getLast())) {
            issuer = Optional.of(chain.removeLast());
        }
        if (configuration.issuerTrustAnchor().isPresent() && issuer.isEmpty()) {
            issuer = keyMaterial.issuerTrustAnchor(configuration);
        }
        if (configuration.issuerTrustAnchor().isEmpty()) {
            issuer = Optional.empty();
        }
        return new CertificateMaterial(List.copyOf(chain), issuer);
    }

    private IssueSettings settingsFrom(IssueRequest request) {
        IssueRequest effectiveRequest = request == null ? IssueRequest.EMPTY : request;
        OrionHttpsConfiguration https = desiredState.current()
                .document()
                .system()
                .https()
                .orElseThrow(() -> new IllegalStateException("HTTPS desired state is not configured"));
        OrionAcmeConfiguration acme = https.acme()
                .filter(OrionAcmeConfiguration::enabled)
                .orElseThrow(() -> new IllegalStateException("ACME desired state is not enabled"));

        List<String> domains = domainsFrom(effectiveRequest, acme);
        requireRequestedDomainsAllowed(domains, effectiveRequest, acme);
        KeyMaterialScope scope = KeyMaterialScope.cluster(clusterId);
        KeyMaterialDescriptor account = descriptor(
                acme.accountMaterial().orElseThrow(
                        () -> new IllegalStateException("ACME account material is not configured")),
                KeyMaterialPurpose.ACME_ACCOUNT,
                scope);
        KeyMaterialDescriptor identity = descriptor(
                https.identity().orElseThrow(
                        () -> new IllegalStateException("ACME TLS identity material is not configured")),
                KeyMaterialPurpose.TLS_IDENTITY,
                scope);
        Optional<TrustedCertificateDescriptor> issuer = https.serverIssuerTrustAnchor()
                .map(reference -> trustedCertificate(reference, scope));
        return new IssueSettings(
                firstNotBlank(
                        effectiveRequest.directoryUrl(),
                        acme.directoryUrl().toString(),
                        "ACME directory URL is required"),
                firstNotBlank(
                        effectiveRequest.accountEmail(),
                        acme.accountEmail(),
                        "ACME account email is required"),
                domains,
                firstNonBlank(effectiveRequest.organization(), acme.organization()),
                secondsOrDefault(
                        effectiveRequest.authorizationTimeoutSeconds(),
                        acme.authorizationTimeoutSeconds(),
                        "ACME authorization timeout must be positive"),
                secondsOrDefault(
                        effectiveRequest.orderTimeoutSeconds(),
                        acme.orderTimeoutSeconds(),
                        "ACME order timeout must be positive"),
                boolOrDefault(
                        effectiveRequest.agreeToTermsOfService(),
                        acme.agreeToTermsOfService()),
                new AcmeMaterialConfiguration(account, identity, issuer));
    }

    private static KeyMaterialDescriptor descriptor(
            OrionMaterialReference reference,
            KeyMaterialPurpose purpose,
            KeyMaterialScope scope) {
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(reference.alias()),
                purpose,
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(reference.version()),
                scope);
    }

    private static TrustedCertificateDescriptor trustedCertificate(
            OrionMaterialReference reference,
            KeyMaterialScope scope) {
        return new TrustedCertificateDescriptor(
                new KeyMaterialAlias(reference.alias()),
                KeyMaterialAlgorithm.RSA,
                new KeyMaterialVersion(reference.version()),
                scope);
    }

    private static List<String> domainsFrom(IssueRequest request, OrionAcmeConfiguration configuration) {
        List<String> requestedDomains = validatedDomainsOrEmpty(request.domains());
        if (!requestedDomains.isEmpty()) {
            return requestedDomains;
        }
        List<String> configuredDomains = validatedDomainsOrEmpty(configuration.domains());
        if (configuredDomains.isEmpty()) {
            throw new IllegalArgumentException("At least one ACME domain is required");
        }
        return configuredDomains;
    }

    private static void requireRequestedDomainsAllowed(
            List<String> domains,
            IssueRequest request,
            OrionAcmeConfiguration configuration) {
        List<String> requestedDomains = validatedDomainsOrEmpty(request.domains());
        if (requestedDomains.isEmpty()) {
            return;
        }
        List<String> configuredDomains = validatedDomainsOrEmpty(configuration.domains());
        if (configuration.allowRequestedDomains() || requestedDomains.equals(configuredDomains)) {
            return;
        }
        throw new IllegalArgumentException("Requested ACME domains are not allowed by configuration");
    }

    private static boolean isTrustAnchor(X509Certificate certificate) {
        if (certificate.getBasicConstraints() < 0
                || !certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException | RuntimeException failure) {
            return false;
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

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public record IssueRequest(
            String directoryUrl,
            String accountEmail,
            List<String> domains,
            String organization,
            Long authorizationTimeoutSeconds,
            Long orderTimeoutSeconds,
            Boolean agreeToTermsOfService) {
        static final IssueRequest EMPTY = new IssueRequest(null, null, null, null, null, null, null);
    }

    private record IssueSettings(
            String directoryUrl,
            String accountEmail,
            List<String> domains,
            String organization,
            long authorizationTimeoutSeconds,
            long orderTimeoutSeconds,
            boolean agreeToTermsOfService,
            AcmeMaterialConfiguration material) {
    }

    private record CertificateMaterial(
            List<X509Certificate> chain,
            Optional<X509Certificate> issuerTrustAnchor) {
    }
}
