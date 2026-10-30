package pro.deta.orion.acme;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.shredzone.acme4j.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.config.OrionConfigurationModification;
import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.internal.ChangeMetadata;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter.ServiceLifecycle;
import pro.deta.orion.schema.orion.v2.Connection;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.keymaterial.KeyMaterialId;
import pro.deta.orion.util.LogScope;
import pro.deta.orion.util.Result;

/** Owns ACME settings, certificate issuance, and automatic renewal over configuration and key material. */
@Singleton
public class AcmeCertificateService implements ServiceLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(AcmeCertificateService.class);
    private static final int RSA_KEY_SIZE = KeyMaterialConstants.RSA_KEY_SIZE_BITS;

    private static final List<Preset> PRESETS = List.of(
            new Preset("letsencrypt", "Let's Encrypt", "https://acme-v02.api.letsencrypt.org/directory", false),
            new Preset("zerossl", "ZeroSSL", "https://acme.zerossl.com/v2/DV90", true),
            new Preset("google", "Google Trust Services", "https://dv.acme-v02.api.pki.goog/directory", true),
            new Preset("custom", "Other ACME server", "", false));

    private final OrionConfigurationEditor editor;
    private final OrionKeyMaterial keyMaterial;
    private final AcmeCertificateIssuer certificateIssuer;
    private final ConfigurationSecrets secrets;
    private final OrionExecutor executor;
    private final AtomicBoolean issuanceInProgress = new AtomicBoolean();
    private final ReentrantLock maintenanceExecution = new ReentrantLock();
    private volatile RenewalAttempt renewalAttempt;
    private volatile boolean maintenanceStopped;
    private volatile ScheduledFuture<?> maintenance;

    @Inject
    public AcmeCertificateService(
            OrionConfigurationEditor editor,
            OrionKeyMaterial keyMaterial,
            AcmeCertificateIssuer certificateIssuer,
            ConfigurationSecrets secrets,
            OrionExecutor executor) {
        this.editor = editor;
        this.keyMaterial = keyMaterial;
        this.certificateIssuer = certificateIssuer;
        this.secrets = secrets;
        this.executor = executor;
    }

    public View view() {
        return view(editor.current());
    }

    private View view(OrionDocumentRevision snapshot) {
        OrionAcmeConfiguration acme = snapshot.result()
                .valueOrFailure("Cannot read configuration document")
                .system().https()
                .flatMap(OrionHttpsConfiguration::acme).orElse(null);
        String directory = acme == null ? PRESETS.getFirst().directoryUrl() : directoryUrl(acme.directoryUrl());
        String provider = "custom";
        for (Preset preset : PRESETS) {
            if (preset.directoryUrl().equals(directory)) provider = preset.id();
        }
        String revision = ((OrionRevision.Stored) snapshot.revision()).value();
        return new View(revision, acme != null && acme.enabled(), provider, directory,
                acme == null || acme.accountEmail() == null ? "" : acme.accountEmail(),
                acme == null ? List.of() : acme.domains(),
                acme == null ? "" : acme.eabKeyId().orElse(""), acme != null && acme.eabSecret().isPresent(), PRESETS,
                renewalStatus(), acme == null ? null : acme.accountMaterial().orElse(null));
    }

    public View save(Settings settings, String userId) {
        if (settings == null) throw new IllegalArgumentException("ACME settings are required");
        try {
            if (settings.revision() == null || settings.revision().isBlank()) {
                throw new IllegalArgumentException("Configuration revision is required");
            }
            OrionConfigurationModification edit = editor.edit(new OrionRevision.Stored(settings.revision()),
                    new ChangeMetadata(new UserEmail(userId, ""), "Configure ACME certificate issuance"));
            try {
                edit.update(document -> {
                    OrionDocument candidate = updated(document, settings);
                    prepareMaterial(candidate.system().https().orElseThrow());
                    return candidate;
                });
                OrionDocument updated = edit.document();
                edit.apply();
                return view(new OrionDocumentRevision(Result.of(updated), edit.revision()));
            } finally {
                edit.discard();
            }
        } finally {
            if (settings.eabHmacKey() != null) Arrays.fill(settings.eabHmacKey(), '\0');
        }
    }

    private OrionDocument updated(OrionDocument document, Settings input) {
        Preset selected = null;
        for (Preset preset : PRESETS) {
            if (preset.id().equals(input.provider())) selected = preset;
        }
        if (selected == null) throw new IllegalArgumentException("Select an ACME provider");
        URI directory = URI.create(selected.id().equals("custom")
                ? required(input.directoryUrl(), "ACME directory URL") : selected.directoryUrl());
        if (!"https".equalsIgnoreCase(directory.getScheme()) || directory.getHost() == null
                || directory.getUserInfo() != null || directory.getFragment() != null) {
            throw new IllegalArgumentException("ACME directory must be an HTTPS URL without credentials or fragment");
        }
        String email = required(input.accountEmail(), "ACME account email");
        if (!email.contains("@") || email.contains("\n") || email.contains("\r")) {
            throw new IllegalArgumentException("ACME account email is invalid");
        }
        OrionHttpsConfiguration https = document.system().https().orElseGet(() -> new OrionHttpsConfiguration(
                false, "0.0.0.0", 8443, null, Optional.of(new KeyMaterialId("https-identity-v1", 1)),
                Optional.empty(), OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.empty()));
        OrionAcmeConfiguration previous = https.acme().orElse(null);
        KeyMaterialId requestedAccount = input.accountMaterial();
        if (requestedAccount != null) {
            try {
                keyMaterial.require(new KeyMaterialDescriptor(requestedAccount.alias(),
                        Set.of(KeyMaterialPurpose.ACME_ACCOUNT), KeyMaterialAlgorithm.RSA,
                        requestedAccount.version()));
            } catch (GeneralSecurityException invalid) {
                throw new IllegalArgumentException("Select an existing RSA ACME account key");
            }
        }
        Optional<String> kid = Optional.ofNullable(input.eabKeyId()).map(String::trim).filter(s -> !s.isEmpty());
        boolean sameAccount = previous != null && directoryUrl(previous.directoryUrl()).equals(directory.toString())
                && previous.eabKeyId().equals(kid)
                && (requestedAccount == null || previous.accountMaterial().filter(requestedAccount::equals).isPresent());
        Optional<String> secret = sameAccount ? previous.eabSecret() : Optional.empty();
        char[] key = input.eabHmacKey();
        boolean hasKey = key != null && key.length > 0;
        if ((selected.requiresEab() && kid.isEmpty()) || (hasKey && kid.isEmpty())
                || (kid.isPresent() && !hasKey && secret.isEmpty())) {
            throw new IllegalArgumentException("EAB requires a key ID and HMAC key for this provider/account");
        }
        if (hasKey) {
            if (key.length > 4096) throw new IllegalArgumentException("EAB HMAC key is too long");
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(new String(key));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("EAB HMAC key must be base64url encoded");
            }
            int keyBytes = decoded.length;
            Arrays.fill(decoded, (byte) 0);
            if (keyBytes < 32) throw new IllegalArgumentException("EAB HMAC key must contain at least 256 bits");
            if (secret.isPresent() && !sharedSecret(document, secret.orElseThrow())) {
                document = secrets.replaceSystem(document, secret.orElseThrow(), key);
            } else {
                secret = Optional.of("acme-eab-" + UUID.randomUUID());
                document = secrets.createSystem(document, secret.orElseThrow(), key);
            }
        }
        KeyMaterialId account = requestedAccount;
        if (account == null) {
            account = sameAccount && previous.accountMaterial().isPresent()
                    ? previous.accountMaterial().orElseThrow()
                    : new KeyMaterialId("acme-account-" + UUID.randomUUID(), 1);
        }
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(true, directory, email, input.domains(),
                previous == null ? null : previous.organization(), Optional.of(account),
                previous == null ? 60 : previous.authorizationTimeoutSeconds(),
                previous == null ? 60 : previous.orderTimeoutSeconds(), true,
                previous != null && previous.allowRequestedDomains(), kid, secret);
        OrionHttpsConfiguration configured = new OrionHttpsConfiguration(https.enabled(), https.address(),
                https.port(), https.publicUrl(), https.identity().isPresent() ? https.identity()
                        : Optional.of(new KeyMaterialId("https-identity-v1", 1)),
                https.serverIssuerTrustAnchor(), https.clientAuthentication(), https.clientTrustAnchors(),
                Optional.of(acme));
        return new OrionDocument(new OrionDocument.SystemConfiguration(document.system().accessControl(),
                Optional.of(configured), document.system().secrets(), document.system().proxies(),
                document.system().connections(),
                document.system().oidcProviders(), document.system().invitations()),
                document.organizations());
    }

    private static String directoryUrl(URI uri) {
        return new Session(uri).provider().resolve(uri).toString();
    }

    private static boolean sharedSecret(OrionDocument document, String id) {
        for (Connection connection : document.system().connections()) {
            if (connection.referencesSecret(id)) return true;
        }
        for (GitProxyBinding proxy : document.system().proxies()) {
            if (proxy.secret(document.system()).filter(id::equals).isPresent()) return true;
        }
        return false;
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
        KeyPair accountKeyPair;
        KeyPair domainKeyPair;
        try {
            prepareMaterial(settings.material());
            accountKeyPair = keyMaterial.keyPair(settings.material().account());
            domainKeyPair = keyMaterial.keyPair(settings.material().identity());
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
                    accountKeyPair,
                    domainKeyPair,
                    settings.domains(),
                    settings.organization(),
                    Duration.ofSeconds(settings.authorizationTimeoutSeconds()),
                    Duration.ofSeconds(settings.orderTimeoutSeconds()),
                    settings.agreeToTermsOfService(), settings.eabKeyId(), eabKey));
        } finally {
            if (eabKey != null) Arrays.fill(eabKey, '\0');
        }
        if (Thread.currentThread().isInterrupted() || (automatic && maintenanceStopped)
                || !settings.snapshot().system().https().equals(editor.current().result()
                        .valueOrFailure("Cannot read configuration document")
                        .system().https())) {
            throw new AcmeCertificateIssueException("Certificate issuance cancelled or ACME settings changed");
        }
        try {
            CertificateMaterial certificates = certificateMaterial(settings.material(), issued.certificateChain());
            keyMaterial.withModification(keyMaterial.revision(), modification -> {
                if (settings.material().issuerTrustAnchor().isPresent()) {
                    modification.setTrustedCertificate(settings.material().issuerTrustAnchor().orElseThrow(),
                            certificates.issuerTrustAnchor().orElseThrow());
                }
                modification.installCertificateChain(settings.material().identity(), certificates.chain(),
                        settings.material().issuerTrustAnchor());
                modification.apply();
            });
            return new IssuedAcmeCertificate(issued.domains(), certificates.chain());
        } catch (IOException | GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot store issued ACME certificate", failure);
        }
    }

    @Override
    public synchronized void onStart() {
        if (maintenance != null) throw new IllegalStateException("ACME maintenance is already running");
        maintenanceStopped = false;
        maintenance = executor.scheduleWithFixedDelay(this::runMaintenance,
                0, 60, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void onStop() throws InterruptedException {
        maintenanceStopped = true;
        if (maintenance == null) return;
        maintenance.cancel(true);
        if (!maintenanceExecution.tryLock(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("ACME maintenance has not stopped");
        }
        maintenanceExecution.unlock();
        maintenance = null;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isRunning() {
        ScheduledFuture<?> current = maintenance;
        return current != null && !current.isDone() && !maintenanceStopped;
    }

    private void runMaintenance() {
        try {
            maintenanceExecution.lockInterruptibly();
            try {
                maintainCertificate(Instant.now());
            } finally {
                maintenanceExecution.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void maintainCertificate(Instant now) {
        try (LogScope user = LogScope.user(null); LogScope task = LogScope.task("acme-certificate")) {
            if (maintenanceStopped) return;
            if (issuanceInProgress.compareAndSet(false, true)) {
                try {
                    IssueSettings settings = settingsFrom(IssueRequest.EMPTY);
                    Optional<List<X509Certificate>> chain =
                            keyMaterial.issuedCertificateChain(settings.material().identity());
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
        }
    }

    public RenewalStatus renewalStatus() {
        try {
            IssueSettings settings = settingsFrom(IssueRequest.EMPTY);
            Optional<List<X509Certificate>> chain =
                    keyMaterial.issuedCertificateChain(settings.material().identity());
            RenewalAttempt attempt = currentAttempt(settings);
            String state = !isRunning() ? "stopped"
                    : issuanceInProgress.get() ? "issuing"
                    : chain.isEmpty() ? "awaiting_certificate"
                    : attempt != null && !attempt.error().isEmpty() ? "retrying" : "scheduled";
            return new RenewalStatus(state, chain.map(c -> c.getFirst().getNotAfter().toInstant().toString())
                    .orElse(""), chain.map(c -> nextAttempt(settings, c.getFirst()).toString()).orElse(""),
                    attempt == null ? "" : attempt.started().toString(),
                    attempt == null || attempt.lastSuccess() == null ? "" : attempt.lastSuccess().toString(),
                    attempt == null ? "" : attempt.error(), "");
        } catch (ConfigurationUnavailableException disabled) {
            return new RenewalStatus("disabled", "", "", "", "", "", "");
        } catch (GeneralSecurityException | RuntimeException failure) {
            return new RenewalStatus("unavailable", "", "", "", "",
                    "Could not read certificate renewal status. Check ACME settings and key material.", "");
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
            return keyMaterial.issuedCertificateChain(settings.material().identity())
                    .map(chain -> new IssuedAcmeCertificate(settings.domains(), chain));
        } catch (GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot read issued ACME certificate", failure);
        }
    }

    private CertificateMaterial certificateMaterial(
            Material configuration,
            List<X509Certificate> issuedChain) throws GeneralSecurityException {
        List<X509Certificate> chain = new ArrayList<>(issuedChain);
        Optional<X509Certificate> issuer = Optional.empty();
        if (chain.size() > 1 && isTrustAnchor(chain.getLast())) {
            issuer = Optional.of(chain.removeLast());
        }
        if (configuration.issuerTrustAnchor().isPresent() && issuer.isEmpty()) {
            issuer = Optional.of(keyMaterial.trustedCertificate(
                    configuration.issuerTrustAnchor().orElseThrow()));
        }
        if (configuration.issuerTrustAnchor().isEmpty()) {
            issuer = Optional.empty();
        }
        return new CertificateMaterial(List.copyOf(chain), issuer);
    }

    private IssueSettings settingsFrom(IssueRequest request) {
        IssueRequest effectiveRequest = request == null ? IssueRequest.EMPTY : request;
        OrionDocument snapshot = editor.current().result()
                .valueOrFailure("Cannot read configuration document");
        OrionHttpsConfiguration https = snapshot
                .system()
                .https()
                .orElseThrow(() -> new ConfigurationUnavailableException("HTTPS configuration is not configured"));
        OrionAcmeConfiguration acme = https.acme()
                .filter(OrionAcmeConfiguration::enabled)
                .orElseThrow(() -> new ConfigurationUnavailableException("ACME configuration is not enabled"));

        if (acme.eabSecret().isPresent() && effectiveRequest.directoryUrl() != null
                && !effectiveRequest.directoryUrl().equals(acme.directoryUrl().toString())) {
            throw new ValidationException("Save ACME provider settings before changing the directory URL");
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

    private void prepareMaterial(OrionHttpsConfiguration https) {
        try {
            prepareMaterial(materialFrom(https));
        } catch (IOException | GeneralSecurityException failure) {
            throw new AcmeCertificateIssueException("Cannot prepare ACME key material", failure);
        }
    }

    private void prepareMaterial(Material configuration)
            throws IOException, GeneralSecurityException {
        keyMaterial.withModification(keyMaterial.revision(), modification -> {
            if (configuration.issuerTrustAnchor().isPresent()) {
                KeyMaterialDescriptor issuer = configuration.issuerTrustAnchor().orElseThrow();
                String alias = issuer.alias();
                if (alias.equalsIgnoreCase(configuration.account().alias())
                        || alias.equalsIgnoreCase(configuration.identity().alias())) {
                    throw new IllegalArgumentException("ACME key and issuer must use different aliases");
                }
                if (keyMaterial.contains(issuer.alias(), Set.of())) keyMaterial.require(issuer);
            }
            modification.generateKey(configuration.account(), RSA_KEY_SIZE);
            modification.generateKey(configuration.identity(), RSA_KEY_SIZE);
            modification.apply();
        });
    }

    private Material materialFrom(OrionHttpsConfiguration https) {
        OrionAcmeConfiguration acme = https.acme().orElseThrow();
        KeyMaterialDescriptor account = descriptor(
                acme.accountMaterial().orElseThrow(
                        () -> new ConfigurationUnavailableException("ACME account material is not configured")),
                KeyMaterialPurpose.ACME_ACCOUNT);
        KeyMaterialDescriptor identity = descriptor(
                https.identity().orElseThrow(
                        () -> new ConfigurationUnavailableException("ACME TLS identity material is not configured")),
                KeyMaterialPurpose.TLS_IDENTITY);
        Optional<KeyMaterialDescriptor> issuer = https.serverIssuerTrustAnchor()
                .map(AcmeCertificateService::trustedCertificate);
        return new Material(account, identity, issuer);
    }

    private static KeyMaterialDescriptor descriptor(
            KeyMaterialId reference,
            KeyMaterialPurpose purpose) {
        return new KeyMaterialDescriptor(
                reference.alias(),
                Set.of(purpose),
                KeyMaterialAlgorithm.RSA,
                reference.version());
    }

    private static KeyMaterialDescriptor trustedCertificate(
            KeyMaterialId reference) {
        return new KeyMaterialDescriptor(
                reference.alias(), Set.of(KeyMaterialPurpose.TRUST_ANCHOR),
                KeyMaterialAlgorithm.RSA,
                reference.version());
    }

    private static List<String> domainsFrom(IssueRequest request, OrionAcmeConfiguration configuration) {
        List<String> requestedDomains = validatedDomainsOrEmpty(request.domains());
        if (!requestedDomains.isEmpty()) {
            return requestedDomains;
        }
        List<String> configuredDomains = validatedDomainsOrEmpty(configuration.domains());
        if (configuredDomains.isEmpty()) {
            throw new ValidationException("At least one ACME domain is required");
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
        throw new ValidationException("Requested ACME domains are not allowed by configuration");
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
                throw new ValidationException("ACME domain is required");
            }
            result.add(domain);
        }
        return List.copyOf(result);
    }

    private static String firstNotBlank(String requested, String configured, String message) {
        String result = firstNonBlank(requested, configured);
        if (result == null) {
            throw new ValidationException(message);
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
            throw new ValidationException(message);
        }
        return result;
    }

    private static boolean boolOrDefault(Boolean requested, boolean configured) {
        return requested == null ? configured : requested;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    public record Preset(String id, String label, String directoryUrl, boolean requiresEab) {}

    public record View(String revision, boolean enabled, String provider, String directoryUrl, String accountEmail,
                       List<String> domains, String eabKeyId, boolean eabConfigured, List<Preset> presets,
                       RenewalStatus renewal, KeyMaterialId accountMaterial) {}

    public record Settings(String revision, String provider, String directoryUrl, String accountEmail,
                           List<String> domains, String eabKeyId, char[] eabHmacKey,
                           KeyMaterialId accountMaterial) {
        @Override
        public String toString() {
            return "AcmeSettings[credentials=<redacted>]";
        }
    }

    public record IssueRequest(
            String directoryUrl,
            String accountEmail,
            List<String> domains,
            String organization,
            Long authorizationTimeoutSeconds,
            Long orderTimeoutSeconds,
            Boolean agreeToTermsOfService) {
        public static final IssueRequest EMPTY = new IssueRequest(null, null, null, null, null, null, null);
    }

    private record IssueSettings(
            String directoryUrl,
            String accountEmail,
            List<String> domains,
            String organization,
            long authorizationTimeoutSeconds,
            long orderTimeoutSeconds,
            boolean agreeToTermsOfService,
            Material material,
            OrionDocument snapshot,
            String eabKeyId,
            String eabSecret) {
    }

    private record CertificateMaterial(
            List<X509Certificate> chain,
            Optional<X509Certificate> issuerTrustAnchor) {
    }

    private record Material(
            KeyMaterialDescriptor account,
            KeyMaterialDescriptor identity,
            Optional<KeyMaterialDescriptor> issuerTrustAnchor) {
    }

    public static final class ConfigurationUnavailableException extends RuntimeException {
        private ConfigurationUnavailableException(String message) {
            super(message);
        }
    }

    public static final class IssuanceBusyException extends RuntimeException {
        public IssuanceBusyException() {
        }
    }

    /** An invalid ACME request whose message is safe to return to the caller. */
    public static final class ValidationException extends IllegalArgumentException {
        private ValidationException(String message) {
            super(message);
        }
    }
}
