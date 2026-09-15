package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.schema.orion.GitProxyBinding;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** Projects system proxy bindings without exposing credentials, private cache identities, or upstream errors. */
public final class OrionAdminProxiesRoute extends BaseAdminRoute {
    private final OrionDesiredState desiredState;
    private final ProxyAwareNativeGitRepositoryProvider provider;

    @Inject
    public OrionAdminProxiesRoute(OrionDesiredState desiredState, ProxyAwareNativeGitRepositoryProvider provider) {
        super(OrionAdminPaths.PROXIES, OrionHttpRouteDefinition.Method.GET);
        this.desiredState = desiredState;
        this.provider = provider;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        var aliases = new ArrayList<AliasResponse>();
        for (GitProxyBinding binding : desiredState.current().document().system().proxies()) {
            var observation = provider.syncObservation(binding);
            aliases.add(new AliasResponse("system", binding.alias().value(), sanitizedUpstream(binding.upstream()),
                    binding.upstream().getScheme(), binding.ref(), null,
                    observation.status().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    observation.observedAt() == null ? null : observation.observedAt().toString()));
        }
        aliases.sort(Comparator.comparing(AliasResponse::alias));
        return OrionHttpResponse.ok(Map.of("aliases", aliases));
    }

    private static String sanitizedUpstream(URI upstream) {
        if (upstream.getRawUserInfo() == null) {
            return upstream.toASCIIString();
        }
        String authority = upstream.getRawAuthority();
        return upstream.getScheme() + "://" + authority.substring(authority.lastIndexOf('@') + 1)
                + upstream.getRawPath();
    }

    public record AliasResponse(String scope, String alias, String upstream, String transport, String ref,
            String endpoint, String status, String observedAt) {
    }
}
