package pro.deta.orion.git.proxy;

import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.GitProxyBinding.CredentialKind;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record BootstrapGitLocation(
        URI remoteUri,
        String refName,
        CredentialKind credentialKind,
        String credentialReference,
        String credentialUsername,
        Path knownHosts,
        String proxyName,
        String safeDescription) {
    static final String CACHE_PREFIX = "bootstrap/proxy-";
    private static final String PREFIX = "git+";
    private static final Set<String> REMOTE_SCHEMES = Set.of(
            "git+ssh", "git+http", "git+https", "git+file");
    private static final Set<String> ALLOWED_PARAMETERS = Set.of("ref", "branch");

    BootstrapGitLocation {
        Objects.requireNonNull(remoteUri, "remoteUri");
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(credentialKind, "credentialKind");
        Objects.requireNonNull(proxyName, "proxyName");
        Objects.requireNonNull(safeDescription, "safeDescription");
    }

    boolean isBindingCompatibleWith(BootstrapGitLocation other) {
        return other != null
                && canonicalIdentity(remoteUri).equals(canonicalIdentity(other.remoteUri))
                && refName.equals(other.refName)
                && credentialKind == other.credentialKind
                && Objects.equals(credentialReference, other.credentialReference)
                && Objects.equals(credentialUsername, other.credentialUsername)
                && Objects.equals(knownHosts, other.knownHosts);
    }

    static boolean isRemote(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int separator = value.indexOf(':');
        return separator > 0
                && REMOTE_SCHEMES.contains(value.substring(0, separator).toLowerCase(Locale.ROOT));
    }

    static BootstrapGitLocation parse(BootstrapSourceConfig config) {
        Objects.requireNonNull(config, "config");
        URI source = parseUri(Objects.requireNonNull(config.getLocation(), "location"));
        String scheme = source.getScheme().toLowerCase(Locale.ROOT);
        if (!REMOTE_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("Unsupported remote Git bootstrap scheme");
        }
        if (source.getRawFragment() != null) {
            throw new IllegalArgumentException("Remote Git bootstrap URI must not contain a fragment");
        }
        String userInfo = source.getUserInfo();
        boolean sshUsername = "git+ssh".equals(scheme)
                && userInfo != null
                && !userInfo.isBlank()
                && !userInfo.contains(":");
        if (userInfo != null && !sshUsername) {
            throw new IllegalArgumentException("Remote Git bootstrap URI must not contain credentials");
        }
        Map<String, String> parameters = query(source);
        if (!ALLOWED_PARAMETERS.containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("Remote Git bootstrap URI contains unsupported parameters");
        }
        Map<String, String> auth = Objects.requireNonNull(config.getAuth(), "auth");
        boolean fileTransport = "git+file".equals(scheme);
        CredentialKind credentialKind = parseCredentialKind(
                auth.get("credentialKind"),
                fileTransport);
        credentialKind.requireTransport(scheme.substring(PREFIX.length()));
        String credential = credentialKind == CredentialKind.NONE
                ? absentCredential(auth.get("credential"))
                : externalReference(auth.get("credential"), "Remote Git credential");
        String credentialUsername = credentialUsername(auth, credentialKind);
        String selectedRef = firstNonBlank(
                parameters.get("ref"),
                parameters.get("branch"),
                config.selectedRef());
        String refName = refName(selectedRef);
        URI remote = transportUri(source, scheme.substring(PREFIX.length()));
        String safe = safeSource(source);
        Path knownHosts = optionalFileReference(auth.get("knownHosts"), "Remote Git known-hosts file");
        return new BootstrapGitLocation(
                remote,
                refName,
                credentialKind,
                credential,
                credentialUsername,
                knownHosts,
                CACHE_PREFIX + sha256(canonicalIdentity(remote) + "#" + refName),
                safe);
    }

    private static String canonicalIdentity(URI remote) {
        return GitProxyBinding.canonicalUpstream(remote).toASCIIString();
    }

    private static CredentialKind parseCredentialKind(String value, boolean fileTransport) {
        if (value == null || value.isBlank()) {
            if (fileTransport) {
                return CredentialKind.NONE;
            }
            throw new IllegalArgumentException("Remote Git credential kind must be configured");
        }
        try {
            return CredentialKind.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported remote Git credential kind");
        }
    }

    private static String absentCredential(String value) {
        if (value != null && !value.isBlank()) {
            throw new IllegalArgumentException("Remote Git credential kind does not match transport");
        }
        return null;
    }

    private static String credentialUsername(
            Map<String, String> auth,
            CredentialKind credentialKind) {
        String username = auth.get("credentialUsername");
        if (credentialKind == CredentialKind.HTTP_BASIC) {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("Remote Git basic credential username must be configured");
            }
            if (username.indexOf(':') >= 0
                    || username.indexOf('\n') >= 0
                    || username.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Remote Git basic credential username is invalid");
            }
            return username;
        }
        if (username != null && !username.isBlank()) {
            throw new IllegalArgumentException("Remote Git credential username does not match credential kind");
        }
        return null;
    }

    private static URI parseUri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid remote Git bootstrap URI");
        }
    }

    private static URI transportUri(URI source, String scheme) {
        return withoutQueryAndFragment(source, scheme);
    }

    private static String safeSource(URI source) {
        return withoutQueryAndFragment(source, source.getScheme().toLowerCase(Locale.ROOT))
                .toASCIIString();
    }

    private static URI withoutQueryAndFragment(URI source, String scheme) {
        String value = source.toASCIIString();
        int end = value.length();
        int query = value.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return URI.create(
                scheme.toLowerCase(Locale.ROOT)
                        + value.substring(value.indexOf(':'), end));
    }

    private static String externalReference(String value, String name) {
        if (value == null || !(value.startsWith("env:") || value.startsWith("file:"))) {
            throw new IllegalArgumentException(name + " must use env: or file:");
        }
        return value;
    }

    private static Path optionalFileReference(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.startsWith("file:")) {
            throw new IllegalArgumentException(name + " must use file:");
        }
        try {
            return Path.of(URI.create(value)).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid " + name.toLowerCase(Locale.ROOT));
        }
    }

    private static String repositoryPath(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute()
                || path.startsWith("..")
                || path.toString().equals(".")) {
            throw new IllegalArgumentException(name + " must stay inside the repository");
        }
        return path.toString().replace('\\', '/');
    }

    private static String refName(String selectedRef) {
        try {
            return GitProxyBinding.canonicalRef(selectedRef);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Remote Git bootstrap ref must be a full Git ref name");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalArgumentException("Remote Git bootstrap ref must not be empty");
    }

    private static Map<String, String> query(URI source) {
        String rawQuery = source.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            values.put(decode(key), decode(value));
        }
        return Map.copyOf(values);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
