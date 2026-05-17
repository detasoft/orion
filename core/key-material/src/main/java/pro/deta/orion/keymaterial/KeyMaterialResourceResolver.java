package pro.deta.orion.keymaterial;

import pro.deta.orion.resource.address.ResourceContent;
import pro.deta.orion.resource.address.ResourceExpression;
import pro.deta.orion.resource.address.ResourceResolver;
import pro.deta.orion.resource.address.ResourceScheme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class KeyMaterialResourceResolver {
    private final ResourceResolver resolver;

    public KeyMaterialResourceResolver(ResourceResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("Resource resolver must not be null");
        }
        this.resolver = resolver;
    }

    public static KeyMaterialResourceResolver standard() {
        return new KeyMaterialResourceResolver(ResourceResolver.standard());
    }

    public static KeyMaterialResourceResolver standard(Map<String, String> environment) {
        return new KeyMaterialResourceResolver(ResourceResolver.standard(environment));
    }

    public KeyMaterialContentStore resolveStore(String locationReference) {
        ResourceExpression expression = parse(locationReference, "Key material location reference must not be empty");
        if (expression.hasScheme(ResourceScheme.ENV) && !expression.hasNested()) {
            return resolveStore(resolver.resolve(expression, String.class));
        }
        if (expression.hasScheme(ResourceScheme.CONTENT)) {
            ResourceContent content = resolver.resolve(expression, ResourceContent.class);
            return new ReadOnlyKeyMaterialContentStore(content.bytes(), content.sourceName());
        }
        if (expression.hasEmptyScheme() || expression.hasScheme(ResourceScheme.FILE)) {
            Path path = resolver.resolve(expression, Path.class);
            return new LocalKeyMaterialContentStore(path);
        }
        throw new IllegalArgumentException("Unsupported key material location reference: " + locationReference);
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
        if (expression.hasEmptyScheme()) {
            password = expression.directValue();
        } else if (expression.hasScheme(ResourceScheme.ENV) || expression.hasScheme(ResourceScheme.CONTENT)) {
            password = resolver.resolve(expression, String.class);
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
