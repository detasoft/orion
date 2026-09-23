package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.OidcProvider;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RepositoryRemote;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-only provider editing; secrets stay encrypted and are omitted from all responses. */
public final class OrionAdminOidcRoute extends BaseAdminRoute {
    private final OrionDesiredState desired;
    private final OrionAccessControlServiceImpl acl;
    private final ConfigurationSecrets secrets;
    private final ObjectMapper mapper;

    @Inject
    public OrionAdminOidcRoute(OrionDesiredState desired, OrionAccessControlServiceImpl acl,
            ConfigurationSecrets secrets, ObjectMapper mapper) {
        super("/api/admin/oidc", OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.desired = desired;
        this.acl = acl;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        OrionDesiredState.Snapshot snapshot = desired.current();
        List<Map<String, Object>> organizations = new ArrayList<>();
        for (OrionDocument.Organization organization : snapshot.document().organizations()) {
            List<Map<String, Object>> providers = new ArrayList<>();
            for (OidcProvider provider : organization.oidcProviders()) {
                providers.add(Map.of("id", provider.id(), "issuer", provider.issuer().toString(),
                        "clientId", provider.clientId(), "idleTimeoutSeconds", provider.idleTimeoutSeconds(),
                        "reauthenticationTimeoutSeconds", provider.reauthenticationTimeoutSeconds()));
            }
            organizations.add(Map.of("id", organization.id().value(), "providers", providers));
        }
        return OrionHttpResponse.ok(Map.of("revision", snapshot.revision().orElse(""),
                "organizations", organizations)).withHeader("Cache-Control", "no-store");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) {
        char[] secret = null;
        byte[] bytes = null;
        try {
            if (request.getContentType() == null
                    || !request.getContentType().split(";", 2)[0].equalsIgnoreCase("application/json")) {
                return OrionHttpResponse.empty(415);
            }
            bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) return OrionHttpResponse.empty(413);
            JsonNode input = mapper.readTree(bytes);
            if (input == null || !input.isObject()) throw new IllegalArgumentException();
            OrganizationId id = new OrganizationId(input.path("organization").asText());
            String revision = input.path("revision").asText();
            OidcProvider provider = new OidcProvider(input.path("id").asText(),
                    URI.create(input.path("issuer").asText()), input.path("clientId").asText(), "placeholder",
                    timeout(input, "idleTimeoutSeconds", OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS),
                    timeout(input, "reauthenticationTimeoutSeconds", 0));
            secret = input.path("clientSecret").asText("").toCharArray();
            if (secret.length > 4096) throw new IllegalArgumentException();
            char[] suppliedSecret = secret;
            SecurityContext context = (SecurityContext) request.getAttribute(
                    OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            acl.updatePrimaryConfiguration(revision,
                    document -> save(document, id, provider, suppliedSecret),
                    new AccessControlSaveRequest("Configure organization OIDC provider",
                            new UserEmail(context.getUserIdentity().getUserId(), "")));
            return OrionHttpResponse.ok(Map.of("saved", true)).withHeader("Cache-Control", "no-store");
        } catch (AccessControlConcurrentUpdateException conflict) {
            return OrionHttpResponse.text(409, "Configuration changed. Reload providers and try again.");
        } catch (IllegalArgumentException invalid) {
            return OrionHttpResponse.text(400, "Check provider settings. New or changed clients require a secret.");
        } catch (Exception failure) {
            return OrionHttpResponse.text(503, "Could not save provider configuration.");
        } finally {
            if (secret != null) Arrays.fill(secret, '\0');
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static long timeout(JsonNode input, String field, long defaultValue) {
        if (!input.has(field)) return defaultValue;
        JsonNode value = input.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException();
        return value.longValue();
    }

    private OrionDocument save(OrionDocument document, OrganizationId id, OidcProvider input, char[] secret) {
        OrionDocument.Organization organization = null;
        for (OrionDocument.Organization candidate : document.organizations()) {
            if (candidate.id().equals(id)) organization = candidate;
        }
        if (organization == null) throw new IllegalArgumentException();
        OidcProvider previous = null;
        for (OidcProvider provider : organization.oidcProviders()) {
            if (provider.id().equals(input.id())) previous = provider;
        }
        boolean replacing = previous != null;
        if (secret.length == 0 && (!replacing || !previous.issuer().equals(input.issuer())
                || !previous.clientId().equals(input.clientId()))) {
            throw new IllegalArgumentException();
        }
        boolean newSecret = !replacing || sharedSecret(organization, previous);
        String secretId = newSecret ? "oidc-" + UUID.randomUUID() : previous.secret();
        if (secret.length != 0) {
            ConfigurationScope scope = ConfigurationScope.organization(id);
            document = newSecret ? secrets.create(document, scope, secretId, secret)
                    : secrets.replace(document, scope, secretId, secret);
        } else {
            secretId = previous.secret();
        }
        OidcProvider updated = new OidcProvider(input.id(), input.issuer(), input.clientId(), secretId,
                input.idleTimeoutSeconds(), input.reauthenticationTimeoutSeconds());
        List<OrionDocument.Organization> organizations = new ArrayList<>();
        for (OrionDocument.Organization candidate : document.organizations()) {
            if (!candidate.id().equals(id)) {
                organizations.add(candidate);
                continue;
            }
            List<OidcProvider> providers = new ArrayList<>(candidate.oidcProviders());
            if (replacing) providers.remove(previous);
            providers.add(updated);
            organizations.add(new OrionDocument.Organization(candidate.id(), candidate.displayName(),
                    candidate.users(), candidate.grants(), candidate.roles(), candidate.teams(), candidate.secrets(),
                    providers, candidate.invitations()));
        }
        return new OrionDocument(document.system(), organizations);
    }

    private static boolean sharedSecret(OrionDocument.Organization organization, OidcProvider previous) {
        for (OidcProvider provider : organization.oidcProviders()) {
            if (!provider.id().equals(previous.id()) && provider.secret().equals(previous.secret())) return true;
        }
        for (OrionDocument.Team team : organization.teams()) {
            for (OrionDocument.Repository repository : team.repositories()) {
                for (RepositoryRemote remote : repository.remotes()) {
                    ConfigurationSecretReference reference = remote.credential();
                    if (reference.scope() == ConfigurationSecretReference.Scope.ORGANIZATION
                            && reference.reference().equals(previous.secret())) return true;
                }
            }
        }
        return false;
    }
}
