package pro.deta.orion.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

public abstract class ResourceLocation {
    private final String raw;
    private final URI uri;

    protected ResourceLocation(String raw, URI uri) {
        this.raw = raw;
        this.uri = uri;
    }

    public static ResourceLocation parse(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        try {
            return new ParsedResourceLocation(raw, new URI(raw));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid " + messageName(name) + ": " + raw, e);
        }
    }

    public static ResourceLocation from(URI uri) {
        return new ParsedResourceLocation(uri.toString(), uri);
    }

    public String raw() {
        return raw;
    }

    public URI uri() {
        return uri;
    }

    public ResourceScheme scheme() {
        return ResourceScheme.fromNullable(uri.getScheme());
    }

    public String host() {
        return uri.getHost();
    }

    public String path() {
        return uri.getPath();
    }

    public String schemeSpecificPart() {
        return uri.getSchemeSpecificPart();
    }

    public String pathOrSchemeSpecificPart(String emptyMessage) {
        if (path() != null && !path().isBlank()) {
            return path();
        }
        if (!OrionUtils.isNullOrEmpty(schemeSpecificPart())) {
            return schemeSpecificPart();
        }
        throw new IllegalArgumentException(emptyMessage);
    }

    public ResourceLocation withScheme(String replacementScheme) {
        ResourceScheme normalizedScheme = ResourceScheme.from(replacementScheme);
        String originalScheme = uri.getScheme();
        if (OrionUtils.isNullOrEmpty(originalScheme)) {
            throw new IllegalArgumentException("Resource location does not have a scheme: " + raw);
        }
        return parse(normalizedScheme.value() + raw.substring(originalScheme.length()), "Resource location");
    }

    public String normalizedRelativePath() {
        StringBuilder relativePath = new StringBuilder();
        if (host() != null && !host().isBlank()) {
            relativePath.append(host());
        }
        if (path() != null && !path().isBlank()) {
            if (!relativePath.isEmpty()) {
                relativePath.append("/");
            }
            relativePath.append(stripLeadingSlashes(path()));
        }
        if (relativePath.isEmpty() && schemeSpecificPart() != null) {
            relativePath.append(stripLeadingSlashes(schemeSpecificPart()));
        }
        return Path.of(relativePath.toString()).normalize().toString();
    }

    private static String stripLeadingSlashes(String value) {
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private static String messageName(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static final class ParsedResourceLocation extends ResourceLocation {
        private ParsedResourceLocation(String raw, URI uri) {
            super(raw, uri);
        }
    }
}
