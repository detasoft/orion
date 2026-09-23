package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.OrganizationAccounts;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lets a system administrator create an email-bound invitation in any configured organization. */
public final class OrionAdminInvitationsRoute extends BaseAdminRoute {
    private final OrganizationAccounts accounts;
    private final OrionDesiredState desired;
    private final OrionOidcRoute oidc;
    private final ObjectMapper mapper;

    @Inject
    public OrionAdminInvitationsRoute(OrganizationAccounts accounts, OrionDesiredState desired,
            OrionOidcRoute oidc, ObjectMapper mapper) {
        super("/api/admin/invitations", OrionHttpRouteDefinition.Method.GET, OrionHttpRouteDefinition.Method.POST);
        this.accounts = accounts;
        this.desired = desired;
        this.oidc = oidc;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        List<Map<String, Object>> organizations = new ArrayList<>();
        for (OrionDocument.Organization organization : desired.current().document().organizations()) {
            organizations.add(Map.of("id", organization.id().value(), "name",
                    organization.displayName() == null ? organization.id().value() : organization.displayName(),
                    "oidcConfigured", !organization.oidcProviders().isEmpty()));
        }
        return OrionHttpResponse.ok(Map.of("organizations", organizations)).withHeader("Cache-Control", "no-store");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) {
        try {
            String origin = oidc.publicOrigin().toString();
            byte[] body = request.getInputStream().readNBytes(4097);
            if (body.length > 4096) {
                return OrionHttpResponse.empty(413);
            }
            JsonNode input = mapper.readTree(body);
            OrganizationId id = new OrganizationId(input.path("organization").asText());
            SecurityContext context = (SecurityContext) request.getAttribute(
                    OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            OrganizationAccounts.InvitationLink invitation = accounts.invite(id, input.path("email").asText(),
                    new UserEmail(context.getUserIdentity().getUserId(), ""));
            return OrionHttpResponse.created(Map.of("url", origin + "#invite=" + invitation.token()
                            + "&organization=" + id.value(), "expiresAt", invitation.expiresAt()))
                    .withHeader("Cache-Control", "no-store");
        } catch (AccessControlConcurrentUpdateException conflict) {
            return OrionHttpResponse.text(409, "Configuration changed. Please retry.");
        } catch (IllegalArgumentException invalid) {
            return OrionHttpResponse.text(400, "Check the email and organization's OIDC configuration.");
        } catch (Exception failure) {
            return OrionHttpResponse.text(503, "Cannot create invitation. Check public HTTPS configuration.");
        }
    }
}
