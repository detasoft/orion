package pro.deta.orion.keymaterial;

import pro.deta.orion.resource.reference.ResourceAddress;
import pro.deta.orion.resource.reference.ResourceReferenceResolver;
import pro.deta.orion.resource.reference.ResourceReferenceScope;
import pro.deta.orion.resource.reference.ResourceScheme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

public final class KeyMaterialResourceResolver {
    private static final ResourceScheme ENV = ResourceScheme.of("env");
    private static final String BASE64_CONTENT_PREFIX = "base64,";

    private final ResourceReferenceScope scope;
    private final ResourceReferenceResolver resolver;

    public KeyMaterialResourceResolver(ResourceReferenceScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("Resource resolver scope must not be null");
        }
        this.scope = scope;
        this.resolver = ResourceReferenceResolver.standard(scope);
    }

    public static KeyMaterialResourceResolver standard() {
        return new KeyMaterialResourceResolver(ResourceReferenceScope.empty());
    }

    public static KeyMaterialResourceResolver standard(Map<String, String> environment) {
        return new KeyMaterialResourceResolver(ResourceReferenceScope.builder()
                .environment(environment)
                .build());
    }

    public KeyMaterialContentStore resolveStore(String locationReference) {
        ResourceAddress address = resolveAddress(
                locationReference,
                "Key material location reference must not be empty");
        if (address.scheme().isEmpty() || address.hasScheme(ResourceScheme.FILE)) {
            return new LocalKeyMaterialContentStore(Path.of(address.body()));
        }
        if (address.hasScheme(ENV)) {
            return resolveStore(resolveEnvironment(address.body()));
        }
        if (address.hasScheme(ResourceScheme.CONTENT)) {
            return new ReadOnlyKeyMaterialContentStore(contentBytes(address.body()), locationReference);
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
        ResourceAddress address = resolveAddress(passwordReference, "Key material password reference must not be empty");
        String password;
        if (address.scheme().isEmpty()) {
            password = address.body();
        } else if (address.hasScheme(ENV)) {
            password = resolveEnvironment(address.body());
        } else if (address.hasScheme(ResourceScheme.CONTENT)) {
            password = new String(contentBytes(address.body()), StandardCharsets.UTF_8);
        } else if (address.hasScheme(ResourceScheme.FILE)) {
            password = removeSingleTrailingLineBreak(Files.readString(Path.of(address.body()), StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("Unsupported key material password reference: " + passwordReference);
        }
        return password.toCharArray();
    }

    private ResourceAddress resolveAddress(String reference, String emptyMessage) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return ResourceAddress.parse(resolver.resolve(reference, String.class));
    }

    private String resolveEnvironment(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Environment reference must include a variable name");
        }
        return scope.variable(name)
                .orElseThrow(() -> new IllegalArgumentException("Environment variable is not set: " + name));
    }

    private static byte[] contentBytes(String value) {
        if (value.startsWith(BASE64_CONTENT_PREFIX)) {
            return Base64.getDecoder().decode(value.substring(BASE64_CONTENT_PREFIX.length()));
        }
        return value.getBytes(StandardCharsets.UTF_8);
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
