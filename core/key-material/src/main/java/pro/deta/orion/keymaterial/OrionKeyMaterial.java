package pro.deta.orion.keymaterial;

import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class OrionKeyMaterial implements AutoCloseable {
    private final KeyMaterialService owner;
    private final KeyMaterialScope.Cluster clusterScope;
    private final ServerIdentityCapability serverIdentity;
    private final AcmeKeyMaterialCapability acme;
    private final TlsCapability tls;
    private boolean closed;

    private OrionKeyMaterial(
            KeyMaterialService owner,
            KeyMaterialScope.Cluster clusterScope,
            ServerIdentityCapability serverIdentity) {
        this.owner = owner;
        this.clusterScope = clusterScope;
        this.serverIdentity = serverIdentity;
        this.acme = acmeCapability();
        this.tls = tlsCapability();
    }

    public static OrionKeyMaterial open(
            KeyMaterialContentStore store,
            KeyMaterialOptions options,
            SigningMaterialSet signingMaterial,
            int activeKeySize) throws IOException, GeneralSecurityException {
        requireRsa(signingMaterial);
        KeyMaterialScope.Cluster clusterScope = requireClusterScope(signingMaterial.active().scope());
        KeyMaterialService service = KeyMaterialService.open(store, options);
        try {
            if (!service.hasDurableSnapshot()) {
                if (!signingMaterial.verification().isEmpty()) {
                    throw new GeneralSecurityException(
                            "A new material store cannot contain retained server identities");
                }
                service.generateKeyIfMissing(signingMaterial.active(), activeKeySize);
                service.save();
            }
            List<KeyMaterialDescriptor> descriptors = signingMaterial.verificationIncludingActive();
            KeyMaterialCapabilities capabilities = KeyMaterialCapabilities.open(service, descriptors);
            return new OrionKeyMaterial(
                    service,
                    clusterScope,
                    capabilities.serverIdentity(signingMaterial));
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            service.close();
            throw failure;
        }
    }

    public ServerIdentityCapability serverIdentity() {
        return serverIdentity;
    }

    public AcmeKeyMaterialCapability acme() {
        return acme;
    }

    public TlsCapability tls() {
        return tls;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        owner.close();
    }

    private AcmeKeyMaterialCapability acmeCapability() {
        return new AcmeKeyMaterialCapability() {
            @Override
            public AcmeKeyMaterial acquire(
                    AcmeMaterialConfiguration configuration,
                    int accountKeySize,
                    int domainKeySize) throws IOException, GeneralSecurityException {
                return select(configuration).acquire(accountKeySize, domainKeySize);
            }

            @Override
            public void installCertificateChain(
                    AcmeMaterialConfiguration configuration,
                    List<? extends Certificate> certificateChain,
                    Optional<X509Certificate> issuerTrustAnchor)
                    throws IOException, GeneralSecurityException {
                select(configuration).installCertificateChain(certificateChain, issuerTrustAnchor);
            }

            @Override
            public Optional<List<X509Certificate>> certificateChain(AcmeMaterialConfiguration configuration)
                    throws GeneralSecurityException {
                if (configuration == null) {
                    throw new IllegalArgumentException("ACME material configuration must not be null");
                }
                requireOwnerScope(configuration.identity().scope());
                if (!owner.containsAlias(configuration.identity().alias().value())) {
                    return Optional.empty();
                }
                List<X509Certificate> chain = select(configuration).certificateChain();
                if (chain.size() == 1 && isStorageCertificate(chain.getFirst())) {
                    return Optional.empty();
                }
                return Optional.of(chain);
            }

            @Override
            public Optional<X509Certificate> issuerTrustAnchor(
                    AcmeMaterialConfiguration configuration) throws GeneralSecurityException {
                return select(configuration).issuerTrustAnchor();
            }
        };
    }

    private TlsCapability tlsCapability() {
        return new TlsCapability() {
            @Override
            public List<X509Certificate> certificateChain(TlsMaterialConfiguration configuration)
                    throws GeneralSecurityException {
                return select(configuration).certificateChain();
            }

            @Override
            public Optional<X509Certificate> serverIssuerTrustAnchor(
                    TlsMaterialConfiguration configuration) throws GeneralSecurityException {
                return select(configuration).serverIssuerTrustAnchor();
            }

            @Override
            public SSLContext createContext(TlsMaterialConfiguration configuration)
                    throws GeneralSecurityException {
                SelectedTlsMaterial selected = select(configuration);
                List<X509Certificate> certificateChain = selected.certificateChain();
                if (certificateChain.size() == 1 && isStorageCertificate(certificateChain.getFirst())) {
                    throw new GeneralSecurityException(
                            "TLS identity must contain an issued certificate chain");
                }
                return selected.createContext();
            }
        };
    }

    private SelectedAcmeKeyMaterial select(AcmeMaterialConfiguration configuration)
            throws GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("ACME material configuration must not be null");
        }
        requireOwnerScope(configuration.account().scope());
        requireOwnerScope(configuration.identity().scope());
        configuration.issuerTrustAnchor().ifPresent(root -> requireOwnerScope(root.scope()));
        List<TrustedCertificateDescriptor> trustedCertificates = configuration
                .issuerTrustAnchor()
                .map(List::of)
                .orElseGet(List::of);
        return KeyMaterialCapabilities.open(
                        owner,
                        List.of(configuration.account(), configuration.identity()),
                        trustedCertificates)
                .acme(
                        configuration.account(),
                        configuration.identity(),
                        configuration.issuerTrustAnchor());
    }

    private SelectedTlsMaterial select(TlsMaterialConfiguration configuration)
            throws GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("TLS material configuration must not be null");
        }
        requireOwnerScope(configuration.identity().scope());
        configuration.serverIssuerTrustAnchor().ifPresent(root -> requireOwnerScope(root.scope()));
        for (TrustedCertificateDescriptor root : configuration.clientTrustAnchors()) {
            requireOwnerScope(root.scope());
        }
        List<TrustedCertificateDescriptor> trustedCertificates = new ArrayList<>();
        configuration.serverIssuerTrustAnchor().ifPresent(trustedCertificates::add);
        for (TrustedCertificateDescriptor root : configuration.clientTrustAnchors()) {
            if (!trustedCertificates.contains(root)) {
                trustedCertificates.add(root);
            }
        }
        return KeyMaterialCapabilities.open(
                        owner,
                        List.of(configuration.identity()),
                        trustedCertificates)
                .tls(
                        configuration.identity(),
                        configuration.serverIssuerTrustAnchor(),
                        configuration.clientTrustAnchors(),
                        configuration.clientAuthentication());
    }

    private void requireOwnerScope(KeyMaterialScope scope) {
        if (!clusterScope.equals(scope)) {
            throw new IllegalArgumentException("Key material reference does not belong to the owner cluster");
        }
    }

    private static boolean isStorageCertificate(X509Certificate certificate) {
        String subject = certificate.getSubjectX500Principal().getName(X500Principal.RFC2253);
        return certificate.getBasicConstraints() < 0
                && certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                && subject.contains("O=" + KeyMaterialConstants.STORAGE_CERTIFICATE_ORGANIZATION);
    }

    private static void requireRsa(SigningMaterialSet signingMaterial) {
        if (signingMaterial == null) {
            throw new IllegalArgumentException("Server signing material must not be null");
        }
        if (signingMaterial.active().algorithm() != KeyMaterialAlgorithm.RSA) {
            throw new IllegalArgumentException("JWT server identity material must use RSA");
        }
    }

    private static KeyMaterialScope.Cluster requireClusterScope(KeyMaterialScope scope) {
        if (!(scope instanceof KeyMaterialScope.Cluster cluster)) {
            throw new IllegalArgumentException("Orion key material owner requires cluster-scoped identity");
        }
        return cluster;
    }
}
