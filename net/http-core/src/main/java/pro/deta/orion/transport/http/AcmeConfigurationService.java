package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.shredzone.acme4j.Session;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.bootstrap.config.OrionConfiguration;
import pro.deta.orion.schema.orion.v2.OrionAcmeConfiguration;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.v2.OrionMaterialReference;
import pro.deta.orion.schema.orion.v2.Connection;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persists ACME settings for both transports; credential reads expose only their presence. */
@Singleton
public final class AcmeConfigurationService {
    private static final List<Preset> PRESETS = List.of(
            new Preset("letsencrypt", "Let's Encrypt", "https://acme-v02.api.letsencrypt.org/directory", false),
            new Preset("zerossl", "ZeroSSL", "https://acme.zerossl.com/v2/DV90", true),
            new Preset("google", "Google Trust Services", "https://dv.acme-v02.api.pki.goog/directory", true),
            new Preset("custom", "Other ACME server", "", false));
    private final OrionDesiredState desired;
    private final ConfigurationSecrets secrets;
    private final OrionConfigurationEditor editor;
    private final AcmeCertificateService certificates;
    private final ConfigurationMaterialCapability material;
    private final KeyMaterialScope scope;

    @Inject
    public AcmeConfigurationService(OrionDesiredState desired, ConfigurationSecrets secrets,
            OrionConfigurationEditor editor, AcmeCertificateService certificates,
            ConfigurationMaterialCapability material, OrionConfiguration bootstrap) {
        this.desired = desired;
        this.secrets = secrets;
        this.editor = editor;
        this.certificates = certificates;
        this.material = material;
        this.scope = KeyMaterialScope.cluster(bootstrap.getBootstrap().getKeyMaterial().getClusterId());
    }

    public View view() {
        OrionDesiredState.Snapshot snapshot = desired.current();
        OrionAcmeConfiguration acme = snapshot.document().system().https()
                .flatMap(OrionHttpsConfiguration::acme).orElse(null);
        String directory = acme == null ? PRESETS.getFirst().directoryUrl() : directoryUrl(acme.directoryUrl());
        String provider = "custom";
        for (Preset preset : PRESETS) {
            if (preset.directoryUrl().equals(directory)) provider = preset.id();
        }
        return new View(snapshot.revision().orElse(""), acme != null && acme.enabled(), provider, directory,
                acme == null || acme.accountEmail() == null ? "" : acme.accountEmail(), acme == null ? List.of() : acme.domains(),
                acme == null ? "" : acme.eabKeyId().orElse(""), acme != null && acme.eabSecret().isPresent(), PRESETS,
                certificates.renewalStatus(), acme == null ? null : acme.accountMaterial().orElse(null));
    }

    public View save(Settings settings, String userId) {
        if (settings == null) throw new IllegalArgumentException("ACME settings are required");
        try {
            if (settings.revision() == null || settings.revision().isBlank()) {
                throw new IllegalArgumentException("Configuration revision is required");
            }
            editor.edit(settings.revision()).update(document -> {
                OrionDocument candidate = updated(document, settings);
                certificates.prepareMaterial(candidate.system().https().orElseThrow());
                return candidate;
            }).apply("Configure ACME certificate issuance", new UserEmail(userId, ""));
            return view();
        } finally {
            if (settings.eabHmacKey() != null) Arrays.fill(settings.eabHmacKey(), '\0');
        }
    }

    OrionDocument updated(OrionDocument document, Settings input) {
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
                false, "0.0.0.0", 8443, null, Optional.of(new OrionMaterialReference("https-identity-v1", 1)),
                Optional.empty(), OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.empty()));
        OrionAcmeConfiguration previous = https.acme().orElse(null);
        OrionMaterialReference requestedAccount = input.accountMaterial();
        if (requestedAccount != null) {
            try {
                material.require(new KeyMaterialDescriptor(new KeyMaterialAlias(requestedAccount.alias()),
                        KeyMaterialPurpose.ACME_ACCOUNT, KeyMaterialAlgorithm.RSA,
                        new KeyMaterialVersion(requestedAccount.version()), scope));
            } catch (GeneralSecurityException invalid) {
                throw new IllegalArgumentException("Select an existing RSA ACME account key from this cluster");
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
        OrionMaterialReference account = requestedAccount;
        if (account == null) {
            account = sameAccount && previous.accountMaterial().isPresent()
                    ? previous.accountMaterial().orElseThrow()
                    : new OrionMaterialReference("acme-account-" + UUID.randomUUID(), 1);
        }
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(true, directory, email, input.domains(),
                previous == null ? null : previous.organization(), Optional.of(account),
                previous == null ? 60 : previous.authorizationTimeoutSeconds(),
                previous == null ? 60 : previous.orderTimeoutSeconds(), true,
                previous != null && previous.allowRequestedDomains(), kid, secret);
        OrionHttpsConfiguration configured = new OrionHttpsConfiguration(https.enabled(), https.address(),
                https.port(), https.publicUrl(), https.identity().isPresent() ? https.identity()
                        : Optional.of(new OrionMaterialReference("https-identity-v1", 1)),
                https.serverIssuerTrustAnchor(), https.clientAuthentication(), https.clientTrustAnchors(),
                Optional.of(acme));
        return new OrionDocument(new OrionDocument.SystemConfiguration(document.system().accessControl(),
                Optional.of(configured), document.system().secrets(), document.system().proxies(), document.system().connections()),
                document.organizations());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String directoryUrl(URI uri) {
        return new Session(uri).provider().resolve(uri).toString();
    }

    public record Preset(String id, String label, String directoryUrl, boolean requiresEab) {}

    public record View(String revision, boolean enabled, String provider, String directoryUrl, String accountEmail,
                       List<String> domains, String eabKeyId, boolean eabConfigured, List<Preset> presets,
                       AcmeCertificateService.RenewalStatus renewal, OrionMaterialReference accountMaterial) {}

    public record Settings(String revision, String provider, String directoryUrl, String accountEmail,
                           List<String> domains, String eabKeyId, char[] eabHmacKey,
                           OrionMaterialReference accountMaterial) {
        @Override
        public String toString() {
            return "AcmeSettings[credentials=<redacted>]";
        }
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

}
