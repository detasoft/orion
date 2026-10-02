package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.util.LogScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.schema.orion.v2.OrionDocument;
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
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrionMaterialReference;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
public class AcmeCertificateService {
    private static final Logger LOG = LoggerFactory.getLogger(AcmeCertificateService.class);
    private static final int RSA_KEY_SIZE = KeyMaterialConstants.RSA_KEY_SIZE_BITS;

    private final String clusterId;
    private final OrionDesiredState desiredState;
    private final AcmeKeyMaterialCapability keyMaterial;
    private final AcmeCertificateIssuer certificateIssuer;
    private final ConfigurationSecrets secrets;
    private final AtomicBoolean issuanceInProgress = new AtomicBoolean();
    private volatile RenewalAttempt renewalAttempt;
    private volatile String activationError = "";
    private volatile boolean maintenanceStopped;
    private volatile ScheduledExecutorService maintenance;

    @Inject
    public AcmeCertificateService(
            OrionConfiguration bootstrapConfiguration,
            OrionDesiredState desiredState,
            AcmeKeyMaterialCapability keyMaterial,
            AcmeCertificateIssuer certificateIssuer,
            ConfigurationSecrets secrets) {
        this.clusterId = required(
                bootstrapConfiguration.getBootstrap().getKeyMaterial().getClusterId(),
                "Key material cluster id is required");
        this.desiredState = desiredState;
        this.keyMaterial = keyMaterial;
        this.certificateIssuer = certificateIssuer;
        this.secrets = secrets;
    }

    public IssuedAcmeCertificate issue(IssueRequest request) {
        if (!issuanceInProgress.compareAndSet(false, true)) {
            throw new IssuanceBusyException();
        }
        try (LogScope ignored = LogScope.task("acme-certificate")) {
            LOG.info("Starting ACME certificate issuance");
            return issueRecorded(settingsFrom(request), Instant.now(), false);
        } finally {
            issuanceInProgress.set(false);
        }
    }

    private IssuedAcmeCertificate issueRecorded(IssueSettings settings, Instant now, boolean automatic) {
        RenewalAttempt previous = currentAttempt(settings);
        Instant lastSuccess = previous == null ? null : previous.lastSuccess();
        renewalAttempt = new RenewalAttempt(settings.snapshot().system().https(), now, lastSuccess, "");
        try {
            IssuedAcmeCertificate issued = issueAdmitted(settings, automatic);
            renewalAttempt = new RenewalAttempt(settings.snapshot().system().https(), now, now, "");
            LOG.info("ACME certificate issued and saved: domains={}, expiresAt={}",
                    settings.domains(), issued.certificateChain().getFirst().getNotAfter().toInstant());
            return issued;
        } catch (RuntimeException failure) {
            renewalAttempt = new RenewalAttempt(settings.snapshot().system().https(), now, lastSuccess,
                    "Certificate renewal failed. Check ACME settings and CA availability.");
            if (!automatic) LOG.warn("ACME certificate issuance failed; check settings and CA availability");
            throw failure;
        }
    }

    private IssuedAcmeCertificate issueAdmitted(IssueSettings settings, boolean automatic) {
        AcmeKeyMaterial keys;
        try {
            keys = keyMaterial.acquire(settings.material(), RSA_KEY_SIZE, RSA_KEY_SIZE);
        } catch (IOException | GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot acquire ACME key material", failure);
        }
        char[] eabKey = settings.eabSecret() == null ? null
                : secrets.resolveSystem(settings.snapshot(), settings.eabSecret());
        IssuedAcmeCertificate issued;
        try {
            issued = certificateIssuer.issue(new AcmeCertificateIssueRequest(
                    settings.directoryUrl(),
                    settings.accountEmail(),
                    keys.accountKeyPair(),
                    keys.domainKeyPair(),
                    settings.domains(),
                    settings.organization(),
                    Duration.ofSeconds(settings.authorizationTimeoutSeconds()),
                    Duration.ofSeconds(settings.orderTimeoutSeconds()),
                    settings.agreeToTermsOfService(), settings.eabKeyId(), eabKey));
        } finally {
            if (eabKey != null) Arrays.fill(eabKey, '\0');
        }
        if (Thread.currentThread().isInterrupted() || (automatic && maintenanceStopped)
                || !settings.snapshot().system().https().equals(desiredState.current().document().system().https())) {
            throw new AcmeCertificateIssueException("Certificate issuance cancelled or ACME settings changed");
        }
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

    synchronized void startMaintenance(Runnable activate) {
        if (maintenance != null) throw new IllegalStateException("ACME maintenance is already running");
        maintenanceStopped = false;
        maintenance = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("acme-renewal").factory());
        maintenance.scheduleWithFixedDelay(() -> maintainCertificate(Instant.now(), activate),
                0, 60, TimeUnit.SECONDS);
    }

    synchronized void stopMaintenance() throws InterruptedException {
        maintenanceStopped = true;
        if (maintenance == null) return;
        maintenance.shutdownNow();
        if (!maintenance.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("ACME maintenance has not stopped");
        }
        maintenance = null;
    }

    void maintainCertificate(Instant now, Runnable activate) {
        try (LogScope user = LogScope.user(null); LogScope task = LogScope.task("acme-certificate")) {
            if (maintenanceStopped) return;
            if (issuanceInProgress.compareAndSet(false, true)) {
                try {
                    IssueSettings settings = settingsFrom(IssueRequest.EMPTY);
                    Optional<List<X509Certificate>> chain = keyMaterial.certificateChain(settings.material());
                    if (chain.isPresent() && !now.isBefore(nextAttempt(settings, chain.orElseThrow().getFirst()))) {
                        LOG.warn("Starting ACME certificate renewal: domains={}, expiresAt={}, attemptAt={}",
                                settings.domains(), chain.orElseThrow().getFirst().getNotAfter().toInstant(), now);
                        issueRecorded(settings, now, true);
                    }
                } catch (ConfigurationUnavailableException disabled) {
                    // Disabled or unconfigured ACME does not initiate issuance.
                } catch (GeneralSecurityException | RuntimeException failure) {
                    // The saved chain remains usable; status exposes a safe error and the next retry.
                    LOG.warn("ACME renewal check failed; it will be retried automatically");
                } finally {
                    issuanceInProgress.set(false);
                }
            }
            if (!maintenanceStopped && !Thread.currentThread().isInterrupted()) {
                try {
                    activate.run();
                    activationError = "";
                } catch (RuntimeException failure) {
                    activationError = "Could not activate the saved certificate. Retrying automatically.";
                    LOG.warn(activationError);
                }
            }
        }
    }

    public RenewalStatus renewalStatus() {
        try {
            IssueSettings settings = settingsFrom(IssueRequest.EMPTY);
            Optional<List<X509Certificate>> chain = keyMaterial.certificateChain(settings.material());
            RenewalAttempt attempt = currentAttempt(settings);
            String state = maintenance == null || maintenanceStopped ? "stopped"
                    : issuanceInProgress.get() ? "issuing"
                    : chain.isEmpty() ? "awaiting_certificate"
                    : attempt != null && !attempt.error().isEmpty() ? "retrying" : "scheduled";
            return new RenewalStatus(state, chain.map(c -> c.getFirst().getNotAfter().toInstant().toString())
                    .orElse(""), chain.map(c -> nextAttempt(settings, c.getFirst()).toString()).orElse(""),
                    attempt == null ? "" : attempt.started().toString(),
                    attempt == null || attempt.lastSuccess() == null ? "" : attempt.lastSuccess().toString(),
                    attempt == null ? "" : attempt.error(), activationError);
        } catch (ConfigurationUnavailableException disabled) {
            return new RenewalStatus("disabled", "", "", "", "", "", activationError);
        } catch (GeneralSecurityException | RuntimeException failure) {
            return new RenewalStatus("unavailable", "", "", "", "",
                    "Could not read certificate renewal status. Check ACME settings and key material.", activationError);
        }
    }

    private Instant nextAttempt(IssueSettings settings, X509Certificate leaf) {
        Duration remaining = Duration.between(leaf.getNotBefore().toInstant(), leaf.getNotAfter().toInstant())
                .dividedBy(3);
        if (remaining.compareTo(Duration.ofDays(30)) > 0) remaining = Duration.ofDays(30);
        Instant due = leaf.getNotAfter().toInstant().minus(remaining);
        RenewalAttempt attempt = currentAttempt(settings);
        if (attempt != null && due.isBefore(attempt.started().plus(Duration.ofHours(1)))) {
            due = attempt.started().plus(Duration.ofHours(1));
        }
        return due;
    }

    private RenewalAttempt currentAttempt(IssueSettings settings) {
        RenewalAttempt attempt = renewalAttempt;
        return attempt != null && attempt.configuration().equals(settings.snapshot().system().https()) ? attempt : null;
    }

    public record RenewalStatus(String state, String expiresAt, String nextAttempt, String lastAttempt,
                                String lastSuccess, String message, String activationError) {}

    private record RenewalAttempt(Optional<OrionHttpsConfiguration> configuration, Instant started,
                                  Instant lastSuccess, String error) {}

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
        OrionDocument snapshot = desiredState.current().document();
        OrionHttpsConfiguration https = snapshot
                .system()
                .https()
                .orElseThrow(() -> new ConfigurationUnavailableException("HTTPS desired state is not configured"));
        OrionAcmeConfiguration acme = https.acme()
                .filter(OrionAcmeConfiguration::enabled)
                .orElseThrow(() -> new ConfigurationUnavailableException("ACME desired state is not enabled"));

        if (acme.eabSecret().isPresent() && effectiveRequest.directoryUrl() != null
                && !effectiveRequest.directoryUrl().equals(acme.directoryUrl().toString())) {
            throw new HttpRequestValidationException("Save ACME provider settings before changing the directory URL");
        }

        List<String> domains = domainsFrom(effectiveRequest, acme);
        requireRequestedDomainsAllowed(domains, effectiveRequest, acme);
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
                materialFrom(https), snapshot,
                acme.eabKeyId().orElse(null), acme.eabSecret().orElse(null));
    }

    void prepareMaterial(OrionHttpsConfiguration https) {
        try {
            keyMaterial.acquire(materialFrom(https), RSA_KEY_SIZE, RSA_KEY_SIZE);
        } catch (IOException | GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot prepare ACME key material", failure);
        }
    }

    private AcmeMaterialConfiguration materialFrom(OrionHttpsConfiguration https) {
        OrionAcmeConfiguration acme = https.acme().orElseThrow();
        KeyMaterialScope scope = KeyMaterialScope.cluster(clusterId);
        KeyMaterialDescriptor account = descriptor(
                acme.accountMaterial().orElseThrow(
                        () -> new ConfigurationUnavailableException("ACME account material is not configured")),
                KeyMaterialPurpose.ACME_ACCOUNT,
                scope);
        KeyMaterialDescriptor identity = descriptor(
                https.identity().orElseThrow(
                        () -> new ConfigurationUnavailableException("ACME TLS identity material is not configured")),
                KeyMaterialPurpose.TLS_IDENTITY,
                scope);
        Optional<TrustedCertificateDescriptor> issuer = https.serverIssuerTrustAnchor()
                .map(reference -> trustedCertificate(reference, scope));
        return new AcmeMaterialConfiguration(account, identity, issuer);
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
            throw new HttpRequestValidationException("At least one ACME domain is required");
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
        throw new HttpRequestValidationException("Requested ACME domains are not allowed by configuration");
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
                throw new HttpRequestValidationException("ACME domain is required");
            }
            result.add(domain);
        }
        return List.copyOf(result);
    }

    private static String firstNotBlank(String requested, String configured, String message) {
        String result = firstNonBlank(requested, configured);
        if (result == null) {
            throw new HttpRequestValidationException(message);
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
            throw new HttpRequestValidationException(message);
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
            AcmeMaterialConfiguration material,
            OrionDocument snapshot,
            String eabKeyId,
            String eabSecret) {
    }

    private record CertificateMaterial(
            List<X509Certificate> chain,
            Optional<X509Certificate> issuerTrustAnchor) {
    }

    static final class ConfigurationUnavailableException extends RuntimeException {
        private ConfigurationUnavailableException(String message) {
            super(message);
        }
    }

    static final class IssuanceBusyException extends RuntimeException {
    }
}
