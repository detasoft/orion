package pro.deta.orion.schema.orion;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A system-owned transparent Git proxy; its secret resolves only in the system secret collection. */
public record GitProxyBinding(
        RemoteAlias alias,
        URI upstream,
        String ref,
        GitCredentialKind credentialKind,
        Optional<String> secret,
        Optional<String> username,
        Optional<URI> knownHosts) {
    public static final String REPOSITORY_PREFIX = "proxy/system/";

    public String publicRepositoryName() {
        return REPOSITORY_PREFIX + alias.value();
    }

    public GitProxyBinding {
        Objects.requireNonNull(alias, "proxy alias");
        upstream = canonicalUpstream(upstream);
        ref = canonicalRef(ref);
        Objects.requireNonNull(credentialKind, "proxy credential kind");
        secret = Objects.requireNonNull(secret, "proxy secret")
                .map(value -> IdentifierRules.requireCanonical(value, "proxy secret"));
        username = Objects.requireNonNull(username, "proxy username");
        knownHosts = Objects.requireNonNull(knownHosts, "proxy known-hosts file");
        credentialKind.requireTransport(upstream.getScheme());
        if ((credentialKind == GitCredentialKind.NONE) != secret.isEmpty()) {
            throw new IllegalArgumentException("Proxy secret does not match credential kind");
        }
        if (credentialKind == GitCredentialKind.PASSWORD && !"ssh".equals(upstream.getScheme())) {
            if (username.isEmpty() || username.orElseThrow().isBlank()
                    || username.orElseThrow().indexOf(':') >= 0
                    || username.orElseThrow().indexOf('\n') >= 0
                    || username.orElseThrow().indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Proxy basic credential username is invalid");
            }
        } else if (username.isPresent()) {
            throw new IllegalArgumentException("Proxy username does not match credential kind");
        }
        if (knownHosts.isPresent()) {
            if (!"ssh".equals(upstream.getScheme())) {
                throw new IllegalArgumentException("Proxy known-hosts file requires SSH");
            }
            if (!"file".equalsIgnoreCase(knownHosts.orElseThrow().getScheme())) {
                throw new IllegalArgumentException("Proxy known-hosts file must use a local file URI");
            }
            try {
                knownHosts = Optional.of(Path.of(knownHosts.orElseThrow()).toAbsolutePath().normalize().toUri());
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Proxy known-hosts file must use a local file URI");
            }
        }
    }

    public static URI canonicalUpstream(URI value) {
        Objects.requireNonNull(value, "proxy upstream");
        String scheme = value.getScheme() == null ? "" : value.getScheme().toLowerCase(Locale.ROOT);
        String user = value.getUserInfo();
        if (!Set.of("http", "https", "ssh", "file").contains(scheme)
                || value.getRawQuery() != null || value.getRawFragment() != null
                || (user != null && (!"ssh".equals(scheme) || user.isBlank() || user.contains(":")))) {
            throw new IllegalArgumentException("Invalid proxy upstream URI");
        }
        if ("file".equals(scheme)) {
            try {
                return Path.of(value).toAbsolutePath().normalize().toUri();
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Invalid proxy upstream URI");
            }
        }
        URI normalized = value.normalize();
        String host = normalized.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Proxy upstream URI must include a host");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int port = normalized.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)
                || ("ssh".equals(scheme) && port == 22)) {
            port = -1;
        }
        String authority = (user == null ? "" : normalized.getRawUserInfo() + "@") + host
                + (port < 0 ? "" : ":" + port);
        return URI.create(scheme + "://" + authority + normalized.getRawPath());
    }

    public static String canonicalRef(String selected) {
        if (selected == null || selected.isBlank() || "HEAD".equals(selected)) {
            throw new IllegalArgumentException("Invalid proxy ref");
        }
        String ref = selected.startsWith("refs/") ? selected : "refs/heads/" + selected;
        if (ref.endsWith("/") || ref.endsWith(".") || ref.contains("//") || ref.contains("..")
                || ref.contains("@{")) {
            throw new IllegalArgumentException("Invalid proxy ref");
        }
        for (String component : ref.split("/")) {
            if (component.startsWith(".") || component.endsWith(".lock")) {
                throw new IllegalArgumentException("Invalid proxy ref");
            }
        }
        for (int index = 0; index < ref.length(); index++) {
            char character = ref.charAt(index);
            if (character <= 0x20 || character >= 0x7f || "~^:?*[\\".indexOf(character) >= 0) {
                throw new IllegalArgumentException("Invalid proxy ref");
            }
        }
        return ref;
    }
}
