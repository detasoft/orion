package pro.deta.orion;

import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialConstants;
import pro.deta.orion.keymaterial.KeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialResourceResolver;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.ServerIdentityMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.config.KeyMaterialConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.ServerSigningConfig;
import pro.deta.orion.schema.config.SigningKeyReferenceConfig;
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

public final class ServerIdentityMaterialFactory {
    private ServerIdentityMaterialFactory() {
    }

    public static ServerIdentityMaterial open(
            OrionConfiguration configuration,
            Map<String, String> environment) throws IOException, GeneralSecurityException {
        if (configuration == null) {
            throw new IllegalArgumentException("Orion configuration must not be null");
        }
        KeyMaterialConfig material = configuration.getBootstrap().getKeyMaterial();
        requireMaterialConfig(material);
        Path baseDirectory = ConfigurationContext.baseDirectory(configuration, environment);
        KeyMaterialResourceResolver resolver = KeyMaterialResourceResolver.standard(environment);
        String location = resolveAgainstBaseDirectory(material.getLocation(), baseDirectory, "location");
        String password = resolveAgainstBaseDirectory(material.getPassword(), baseDirectory, "password");
        KeyMaterialContentStore store = resolver.resolveStore(location);
        SigningMaterialSet signingMaterial = signingMaterial(material);
        try (KeyMaterialOptions options = resolver.pkcs12Options(
                password, material.isCreateIfMissing())) {
            return ServerIdentityMaterial.open(
                    store,
                    options,
                    signingMaterial,
                    KeyMaterialConstants.RSA_KEY_SIZE_BITS);
        }
    }

    private static SigningMaterialSet signingMaterial(KeyMaterialConfig material) {
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
        KeyMaterialScope scope = KeyMaterialScope.cluster(required(material.getClusterId(), "cluster id"));
        KeyMaterialDescriptor active = descriptor(signing.getActive(), algorithm, scope, "active");
        List<KeyMaterialDescriptor> verification = new ArrayList<>();
        if (signing.getVerification() != null) {
            for (SigningKeyReferenceConfig reference : signing.getVerification()) {
                verification.add(descriptor(reference, algorithm, scope, "verification"));
            }
        }
        return new SigningMaterialSet(active, verification);
    }

    private static KeyMaterialDescriptor descriptor(
            SigningKeyReferenceConfig reference,
            KeyMaterialAlgorithm algorithm,
            KeyMaterialScope scope,
            String role) {
        if (reference == null) {
            throw new IllegalArgumentException("Server signing " + role + " reference must not be null");
        }
        return new KeyMaterialDescriptor(
                new KeyMaterialAlias(required(reference.getAlias(), role + " alias")),
                KeyMaterialPurpose.SERVER_SIGNING,
                algorithm,
                new KeyMaterialVersion(reference.getVersion()),
                scope);
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
            throw new IllegalArgumentException("Server identity " + label + " must not be empty");
        }
        return value;
    }
}
