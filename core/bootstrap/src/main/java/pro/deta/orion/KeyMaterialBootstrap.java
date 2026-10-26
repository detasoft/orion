package pro.deta.orion;

import pro.deta.orion.bootstrap.config.BootstrapConfiguration;
import pro.deta.orion.bootstrap.config.KeyMaterialConfig;
import pro.deta.orion.bootstrap.config.ServerSigningConfig;
import pro.deta.orion.bootstrap.config.SigningKeyReferenceConfig;
import pro.deta.orion.event.type.OrionEventPublisher;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import pro.deta.orion.keymaterial.KeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialCreation;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialResourceResolver;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.util.ConfigurationContext;
import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves KMS bootstrap settings and initializes missing material through a committed modification. */
public final class KeyMaterialBootstrap {
    private KeyMaterialBootstrap() {
    }

    static KeyMaterialContentStore localStore(
            BootstrapConfiguration configuration,
            Map<String, String> environment) {
        if (configuration == null) {
            throw new IllegalArgumentException("Orion configuration must not be null");
        }
        KeyMaterialConfig material = configuration.getBootstrap().getKeyMaterial();
        requireMaterialConfig(material);
        Path baseDirectory = ConfigurationContext.baseDirectory(configuration, environment);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(environment);
        String location = resolveAgainstBaseDirectory(material.getLocation(), baseDirectory, "location");
        return resolver.resolveStore(location);
    }

    static OrionKeyMaterial open(
            BootstrapConfiguration configuration,
            Map<String, String> environment,
            KeyMaterialContentStore store,
            KeyMaterialCreation creation,
            OrionEventPublisher events) throws IOException, GeneralSecurityException {
        SigningMaterialSet signingMaterial;
        KeyMaterialOptions options;
        try {
            if (configuration == null) {
                throw new IllegalArgumentException("Orion configuration must not be null");
            }
            KeyMaterialConfig material = configuration.getBootstrap().getKeyMaterial();
            requireMaterialConfig(material);
            Path baseDirectory = ConfigurationContext.baseDirectory(configuration, environment);
            KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(environment);
            String password = resolveAgainstBaseDirectory(material.getPassword(), baseDirectory, "password");
            signingMaterial = signingMaterial(material);
            options = resolver.pkcs12Options(password);
        } catch (IOException | RuntimeException failure) {
            try {
                store.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        try (options) {
            OrionKeyMaterial keys = OrionKeyMaterial.open(store, options, events);
            try {
                if (!keys.hasDurableSnapshot()) {
                    if (!creation.allowed()) {
                        throw new GeneralSecurityException(
                                "Key material store is missing and creation was not requested");
                    }
                    if (!signingMaterial.verification().isEmpty()) {
                        throw new GeneralSecurityException(
                                "A new material store cannot contain retained server identities");
                    }
                    keys.withModification(modification -> {
                        modification.generateKey(
                                signingMaterial.active(), KeyMaterialConstants.RSA_KEY_SIZE_BITS);
                        modification.apply();
                    });
                }
                for (KeyMaterialDescriptor descriptor : signingMaterial.verificationIncludingActive()) {
                    keys.require(descriptor);
                }
                return keys;
            } catch (IOException | GeneralSecurityException | RuntimeException failure) {
                keys.close();
                throw failure;
            }
        }
    }

    public static SigningMaterialSet signingMaterial(KeyMaterialConfig material) {
        requireMaterialConfig(material);
        ServerSigningConfig signing = material.getServerSigning();
        if (signing == null) {
            throw new IllegalArgumentException("Server signing configuration must not be null");
        }
        KeyMaterialAlgorithm algorithm;
        try {
            algorithm = KeyMaterialAlgorithm.valueOf(required(signing.getAlgorithm(), "algorithm")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsupported server signing algorithm", failure);
        }

        if (algorithm != KeyMaterialAlgorithm.RSA) {
            throw new IllegalArgumentException("JWT server signing material must use RSA");
        }
        KeyMaterialDescriptor active = descriptor(signing.getActive(), algorithm, "active");
        List<KeyMaterialDescriptor> verification = new ArrayList<>();
        if (signing.getVerification() != null) {
            for (SigningKeyReferenceConfig reference : signing.getVerification()) {
                verification.add(descriptor(reference, algorithm, "verification"));
            }
        }
        return new SigningMaterialSet(active, verification);
    }

    private static KeyMaterialDescriptor descriptor(
            SigningKeyReferenceConfig reference,
            KeyMaterialAlgorithm algorithm,
            String role) {
        if (reference == null) {
            throw new IllegalArgumentException("Server signing " + role + " reference must not be null");
        }
        return new KeyMaterialDescriptor(
                required(reference.getAlias(), role + " alias"),
                Set.of(KeyMaterialPurpose.SERVER_SIGNING),
                algorithm,
                reference.getVersion());
    }

    private static String resolveAgainstBaseDirectory(
            String reference,
            Path baseDirectory,
            String label) {
        ResourceLocation location = ResourceLocation.parse(
                required(reference, "key material " + label), "Key material " + label);
        return switch (location.scheme()) {
            case ResourceScheme.Empty ignored -> baseDirectory.resolve(location.raw()).normalize().toString();
            case ResourceScheme.File ignored -> {
                String value = location.pathOrSchemeSpecificPart(
                        "Key material " + label + " file reference must include a path");
                Path path = Path.of(value);
                yield path.isAbsolute()
                        ? "file:" + path.normalize()
                        : "file:" + baseDirectory.resolve(path).normalize();
            }
            default -> location.raw();
        };
    }

    private static void requireMaterialConfig(KeyMaterialConfig material) {
        if (material == null) {
            throw new IllegalArgumentException("Key material configuration must not be null");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Orion key material " + label + " must not be empty");
        }
        return value;
    }
}
