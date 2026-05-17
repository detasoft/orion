package pro.deta.orion.keymaterial;

import pro.deta.orion.resource.address.ImmutableReference;
import pro.deta.orion.resource.address.ResourceExpression;
import pro.deta.orion.resource.address.ResourceResolver;
import pro.deta.orion.resource.address.ResourceScheme;
import pro.deta.orion.resource.address.ReferenceResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class KeyMaterialResourceResolver {
    private final ResourceResolver resolver;
    private final ReferenceResolver referenceResolver;

    public KeyMaterialResourceResolver(ResourceResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("Resource resolver must not be null");
        }
        this.resolver = resolver;
        this.referenceResolver = new ReferenceResolver(resolver);
    }

    public static KeyMaterialResourceResolver standard() {
        return new KeyMaterialResourceResolver(ResourceResolver.standard());
    }

    public static KeyMaterialResourceResolver standard(Map<String, String> environment) {
        return new KeyMaterialResourceResolver(ResourceResolver.standard(environment));
    }

    public KeyMaterialContentStore resolveStore(String locationReference) {
        ImmutableReference reference = referenceResolver.resolveLocation(
                locationReference,
                "Key material location reference must not be empty");
        return new ReferenceKeyMaterialContentStore(reference);
    }

    public KeyMaterialOptions pkcs12Options(String passwordReference, boolean createIfMissing) throws IOException {
        char[] password = resolvePassword(passwordReference);
        try {
            return KeyMaterialOptions.pkcs12(password, createIfMissing);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public char[] resolvePassword(String passwordReference) throws IOException {
        ResourceExpression expression = parse(passwordReference, "Key material password reference must not be empty");
        String password;
        if (expression.hasEmptyScheme()
                || expression.hasScheme(ResourceScheme.ENV)
                || expression.hasScheme(ResourceScheme.CONTENT)) {
            ImmutableReference reference = resolver.resolve(expression, ImmutableReference.class);
            password = reference.readString()
                    .orElseThrow(() -> new IOException("Key material password reference did not resolve to content"));
        } else if (expression.hasScheme(ResourceScheme.FILE)) {
            Path path = resolver.resolve(expression, Path.class);
            password = removeSingleTrailingLineBreak(Files.readString(path, StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("Unsupported key material password reference: " + passwordReference);
        }
        return password.toCharArray();
    }

    private static ResourceExpression parse(String reference, String emptyMessage) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return ResourceExpression.parse(reference);
    }

    private static String removeSingleTrailingLineBreak(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
