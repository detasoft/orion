package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.OrganizationAccounts;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.SystemInvitation;
import pro.deta.orion.schema.orion.v2.OidcProvider;
import pro.deta.orion.git.proxy.NativeGitRepositoryFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates email-bound invitations for system users and organization members. */
public final class OrionAdminInvitationsRoute extends BaseAdminRoute {
    private final OrganizationAccounts accounts;
    private final OrionAccessControlService configurationService;
    private final OrionOidcRoute oidc;
    private final ObjectMapper mapper;
    private final NativeGitRepositoryFactory repositories;

    @Inject
    public OrionAdminInvitationsRoute(OrganizationAccounts accounts, OrionAccessControlService configurationService,
            OrionOidcRoute oidc, ObjectMapper mapper, NativeGitRepositoryFactory repositories) {
        super("/api/admin/invitations", OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.accounts = accounts;
        this.configurationService = configurationService;
        this.oidc = oidc;
        this.mapper = mapper;
        this.repositories = repositories;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        List<Map<String, Object>> organizations = new ArrayList<>();
        for (OrionDocument.Organization organization : configurationService.configuration().result()
                .valueOrFailure("Cannot read configuration document")
                .organizations()) {
            organizations.add(Map.of("id", organization.id().value(), "name",
                    organization.displayName() == null ? organization.id().value() : organization.displayName(),
                    "oidcConfigured", !organization.oidcProviders().isEmpty()));
        }
        List<String> providers = new ArrayList<>();
        for (OidcProvider provider : editor.current().result()
                .valueOrFailure("Cannot read configuration document").system().oidcProviders()) {
            providers.add(provider.id());
        }
        return OrionHttpResponse.ok(Map.of("organizations", organizations, "systemProviders", providers,
                        "repositories", availableRepositories()))
                .withHeader("Cache-Control", "no-store");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) {
        try {
            String origin = oidc.publicOrigin().toString();
            byte[] body = request.getInputStream().readNBytes(65537);
            if (body.length > 65536) {
                return OrionHttpResponse.empty(413);
            }
            JsonNode input = mapper.readTree(body);
            if (input == null || !input.isObject()) throw new IllegalArgumentException("Invitation is required");
            SecurityContext context = (SecurityContext) request.getAttribute(
                    OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            UserEmail author = new UserEmail(context.getUserIdentity().getUserId(), "");
            String scope = input.path("scope").asText("organization");
            if (scope.equals("system")) {
                if (input.has("organization")) throw new IllegalArgumentException("Conflicting invitation scope");
                List<SystemInvitation.RepositoryAccess> repositories = new ArrayList<>();
                JsonNode selected = input.path("repositories");
                if (!selected.isMissingNode() && (!selected.isArray() || selected.size() > 100)) {
                    throw new IllegalArgumentException("Invalid repository grants");
                }
                for (JsonNode access : selected) {
                    if (!access.path("repository").isTextual() || !access.path("readWrite").isBoolean()) {
                        throw new IllegalArgumentException("Invalid repository grant");
                    }
                    repositories.add(new SystemInvitation.RepositoryAccess(access.path("repository").asText(),
                            access.path("readWrite").asBoolean()));
                }
                List<String> available = availableRepositories();
                for (SystemInvitation.RepositoryAccess access : repositories) {
                    if (!available.contains(access.repository())) {
                        throw new IllegalArgumentException("Select an available user repository");
                    }
                }
                OrganizationAccounts.InvitationLink invitation = accounts.inviteSystem(input.path("email").asText(),
                        input.path("provider").asText(), repositories, author);
                return OrionHttpResponse.created(Map.of("url", origin + "#invite=" + invitation.token()
                                + "&scope=system", "expiresAt", invitation.expiresAt()))
                        .withHeader("Cache-Control", "no-store");
            }
            if (!scope.equals("organization")) throw new IllegalArgumentException("Unknown invitation scope");
            OrganizationId id = new OrganizationId(input.path("organization").asText());
            OrganizationAccounts.InvitationLink invitation = accounts.invite(id, input.path("email").asText(),
                    author);
            return OrionHttpResponse.created(Map.of("url", origin + "#invite=" + invitation.token()
                            + "&organization=" + id.value(), "expiresAt", invitation.expiresAt()))
                    .withHeader("Cache-Control", "no-store");
        } catch (OrionConfigurationConcurrentUpdateException conflict) {
            return OrionHttpResponse.text(409, "Configuration changed. Please retry.");
        } catch (IllegalArgumentException invalid) {
            return OrionHttpResponse.text(400, "Check the email, OIDC provider and repository access.");
        } catch (Exception failure) {
            return OrionHttpResponse.text(503, "Cannot create invitation. Check public HTTPS configuration.");
        }
    }

    private List<String> availableRepositories() {
        List<String> available = new ArrayList<>();
        for (String name : repositories.provider().repositoryNames()) {
            if (!repositories.referencesBootstrapRepository(name)) available.add(name);
        }
        return available;
    }
}
