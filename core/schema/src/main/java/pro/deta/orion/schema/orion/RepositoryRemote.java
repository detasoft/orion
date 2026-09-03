package pro.deta.orion.schema.orion;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record RepositoryRemote(
        RemoteAlias alias,
        RemoteRole role,
        RemoteProvider provider,
        URI uri,
        ConfigurationSecretReference credential,
        Set<RemoteTrigger> triggers,
        List<RemoteRefMapping> refMappings,
        RemoteUpdatePolicy updatePolicy) {
    public RepositoryRemote {
        Objects.requireNonNull(alias, "remote alias");
        Objects.requireNonNull(role, "remote role");
        Objects.requireNonNull(provider, "remote provider");
        uri = requireHttpsUri(uri);
        Objects.requireNonNull(credential, "remote credential");
        triggers = Set.copyOf(Objects.requireNonNull(triggers, "remote triggers"));
        refMappings = copyRefMappings(refMappings);
        Objects.requireNonNull(updatePolicy, "remote update policy");
        boolean upstream = RemoteAlias.UPSTREAM.equals(alias);
        if (upstream != (role == RemoteRole.PRIMARY)) {
            throw new IllegalArgumentException("the PRIMARY remote must use the reserved upstream alias");
        }
    }

    private static List<RemoteRefMapping> copyRefMappings(List<RemoteRefMapping> source) {
        List<RemoteRefMapping> mappings = new ArrayList<>(
                List.copyOf(Objects.requireNonNull(source, "remote ref mappings")));
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("remote ref mappings must not be empty");
        }
        mappings.sort(Comparator.comparing(RemoteRefMapping::source)
                .thenComparing(RemoteRefMapping::destination));
        return List.copyOf(mappings);
    }

    private static URI requireHttpsUri(URI value) {
        Objects.requireNonNull(value, "remote URI");
        String scheme = value.getScheme();
        if (scheme == null || !"https".equals(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("remote URI must use HTTPS");
        }
        if (value.getHost() == null || value.getHost().isBlank()) {
            throw new IllegalArgumentException("remote URI must include a host");
        }
        if (value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException(
                    "remote URI must not contain credentials, query, or fragment data");
        }
        return value;
    }
}
