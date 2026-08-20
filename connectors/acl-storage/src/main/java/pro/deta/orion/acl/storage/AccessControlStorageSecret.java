package pro.deta.orion.acl.storage;

import pro.deta.orion.util.ResourceLocation;
import pro.deta.orion.util.ResourceScheme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class AccessControlStorageSecret {
    private AccessControlStorageSecret() {
    }

    static Path fileReference(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        ResourceLocation location = ResourceLocation.parse(value, name);
        return switch (location.scheme()) {
            case ResourceScheme.File ignored -> Path.of(location.pathOrSchemeSpecificPart(name + " must include a path"));
            default -> throw new IllegalArgumentException(name + " must use file: reference");
        };
    }

    static String optionalSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requiredSecret(name, value);
    }

    static String requiredSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.startsWith("env:")) {
            String variableName = value.substring("env:".length());
            String secret = System.getenv(variableName);
            if (secret == null) {
                throw new IllegalArgumentException(name + " environment variable is not set: " + variableName);
            }
            return secret;
        }
        if (value.startsWith("file:")) {
            try {
                return Files.readString(fileReference(name, value), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + name + " from " + value, e);
            }
        }
        throw new IllegalArgumentException(name + " must use env: or file: reference");
    }
}
