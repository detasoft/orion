package pro.deta.orion.keymaterial;

import pro.deta.orion.resource.reference.ResourceAddress;
import pro.deta.orion.resource.reference.ResourceReferenceResolver;
import pro.deta.orion.resource.reference.ResourceReferenceScope;
import pro.deta.orion.resource.reference.ResourceScheme;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

public final class KeyMaterialResourceResolver {
    private static final ResourceScheme ENV = ResourceScheme.of("env");
    private static final String BASE64_CONTENT_PREFIX = "base64,";

    private final ResourceReferenceScope scope;
    private final ResourceReferenceResolver resolver;
    private final boolean allowUnsafePasswordReferences;

    public KeyMaterialResourceResolver(ResourceReferenceScope scope) {
        this(scope, false);
    }

    private KeyMaterialResourceResolver(ResourceReferenceScope scope, boolean allowUnsafePasswordReferences) {
        if (scope == null) {
            throw new IllegalArgumentException("Resource resolver scope must not be null");
        }
        this.scope = scope;
        this.resolver = ResourceReferenceResolver.standard(scope);
        this.allowUnsafePasswordReferences = allowUnsafePasswordReferences;
    }

    public static KeyMaterialResourceResolver standard() {
        return new KeyMaterialResourceResolver(ResourceReferenceScope.empty());
    }

    public static KeyMaterialResourceResolver standard(Map<String, String> environment) {
        return new KeyMaterialResourceResolver(scope(environment));
    }

    public static KeyMaterialResourceResolver unsafe(ResourceReferenceScope scope) {
        return new KeyMaterialResourceResolver(scope, true);
    }

    public static KeyMaterialResourceResolver unsafe(Map<String, String> environment) {
        return unsafe(scope(environment));
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
            byte[] bytes = contentBytes(address.body());
            try {
                return new ReadOnlyKeyMaterialContentStore(bytes, "inline key material");
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        throw new IllegalArgumentException("Unsupported key material location reference scheme");
    }

    public KeyMaterialOptions pkcs12Options(String passwordReference) throws IOException {
        char[] password = resolvePassword(passwordReference);
        try {
            return KeyMaterialOptions.pkcs12(password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public char[] resolvePassword(String passwordReference) throws IOException {
        ResourceAddress address = resolveSensitiveAddress(passwordReference);
        if (address.hasScheme(ENV)) {
            return resolveEnvironment(address.body()).toCharArray();
        }
        if (address.hasScheme(ResourceScheme.FILE)) {
            return readProtectedPasswordFile(address.body());
        }
        if (address.scheme().isEmpty()) {
            requireUnsafePasswordReferences("Plaintext key material password references are disabled");
            return address.body().toCharArray();
        }
        if (address.hasScheme(ResourceScheme.CONTENT)) {
            requireUnsafePasswordReferences("Inline key material password references are disabled");
            byte[] bytes = contentBytes(address.body());
            try {
                return decodePassword(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        throw new IllegalArgumentException("Unsupported key material password reference scheme");
    }

    private char[] readProtectedPasswordFile(String value) throws IOException {
        Path path;
        try {
            path = KeyMaterialFileSecurity.normalizeLocation(Path.of(value));
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid protected key material password file reference");
        }
        KeyMaterialFileSecurity.validatePublicationDirectory(path.getParent());
        byte[] bytes = KeyMaterialFileSecurity.readOwnerOnlyFile(path, "Key material password file");
        try {
            return decodePassword(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private ResourceAddress resolveSensitiveAddress(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Key material password reference must not be empty");
        }
        try {
            return ResourceAddress.parse(resolver.resolve(reference, String.class));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid key material password reference");
        }
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

    private void requireUnsafePasswordReferences(String message) {
        if (!allowUnsafePasswordReferences) {
            throw new IllegalArgumentException(message);
        }
    }

    private static ResourceReferenceScope scope(Map<String, String> environment) {
        return ResourceReferenceScope.builder()
                .environment(environment)
                .build();
    }

    private static byte[] contentBytes(String value) {
        try {
            if (value.startsWith(BASE64_CONTENT_PREFIX)) {
                return Base64.getDecoder().decode(value.substring(BASE64_CONTENT_PREFIX.length()));
            }
            return value.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid inline key material content");
        }
    }

    private static char[] decodePassword(byte[] bytes) throws IOException {
        CharBuffer characters;
        try {
            characters = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException e) {
            throw new IOException("Key material password is not valid UTF-8");
        }
        char[] decoded = new char[characters.remaining()];
        characters.get(decoded);
        if (characters.hasArray()) {
            Arrays.fill(characters.array(), '\0');
        }
        int length = decoded.length;
        if (length > 0 && decoded[length - 1] == '\n') {
            length--;
            if (length > 0 && decoded[length - 1] == '\r') {
                length--;
            }
        }
        if (length == decoded.length) {
            return decoded;
        }
        char[] trimmed = Arrays.copyOf(decoded, length);
        Arrays.fill(decoded, '\0');
        return trimmed;
    }
}
