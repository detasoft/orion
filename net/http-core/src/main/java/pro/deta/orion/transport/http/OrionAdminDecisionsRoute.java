package pro.deta.orion.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.UserId;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.AUTHENTICATED;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.POST;

/**
 * Lists and answers pending decisions in the shared registry. The authenticated identity identifies
 * a system or organization principal; scoped access belongs to the registry. A successful answer records
 * the decision without claiming that the waiting operation has finished. Responses expose only request display data.
 */
public final class OrionAdminDecisionsRoute extends AbstractOrionHttpRoute {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private final DecisionRegistry registry;
    private final ObjectMapper mapper;

    @Inject
    public OrionAdminDecisionsRoute(DecisionRegistry registry, ObjectMapper mapper) {
        super(OrionAdminPaths.ADMIN + "/decisions", AUTHENTICATED, GET, POST);
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        PrincipalAddress actor = actor(request);
        if (actor == null) {
            return failure(403, "Authenticated user is required");
        }
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (DecisionRequest pending : registry.list(actor)) {
            decisions.add(Map.of(
                    "id", pending.id().toString(),
                    "createdAt", pending.createdAt().toString(),
                    "scope", pending.scope().map(ConfigurationScope::toString).orElse("system"),
                    "title", pending.title(),
                    "description", pending.description(),
                    "actions", pending.actions()));
        }
        return OrionHttpResponse.ok(Map.of("decisions", decisions)).withHeader("Cache-Control", "no-store");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) throws IOException {
        PrincipalAddress actor = actor(request);
        if (actor == null) {
            return failure(403, "Authenticated user is required");
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            return failure(415, "Expected application/json");
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return failure(413, "Decision answer is too large");
        }
        JsonNode answer;
        try {
            answer = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException exception) {
            return failure(400, "Invalid decision answer");
        }
        if (answer == null || !answer.isObject() || answer.size() != 2
                || !answer.path("id").isTextual() || !answer.path("action").isTextual()
                || answer.path("action").asText().isBlank()) {
            return failure(400, "Expected id and action strings");
        }
        UUID id;
        try {
            id = UUID.fromString(answer.path("id").asText());
        } catch (IllegalArgumentException exception) {
            return failure(400, "Invalid decision request ID");
        }
        Result<Decision> result = registry.decide(id, new Decision(answer.path("action").asText(), actor));
        return switch (result) {
            case Result.Success<Decision> ignored ->
                    OrionHttpResponse.empty(204).withHeader("Cache-Control", "no-store");
            case Result.Failure<Decision> rejected -> switch (rejected.code()) {
                case NOT_FOUND -> failure(404, "Decision request is unavailable");
                case NOT_SUPPORTED -> failure(400, "Decision action is unavailable");
                default -> failure(500, "Could not record decision");
            };
        };
    }

    private static PrincipalAddress actor(HttpServletRequest request) {
        Object attribute = request.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        if (!(attribute instanceof SecurityContext context)) {
            return null;
        }
        UserIdentity identity = context.getUserIdentity();
        if (identity == null || identity.isAnonymous() || identity.getUserId() == null) {
            return null;
        }
        try {
            UserId userId = new UserId(identity.getUserId());
            if (identity.getOrganizationId().isPresent()) {
                return new PrincipalAddress.OrganizationPrincipalAddress(
                        identity.getOrganizationId().orElseThrow(), userId);
            }
            return new PrincipalAddress.SystemPrincipalAddress(userId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static OrionHttpResponse failure(int status, String message) {
        return OrionHttpResponse.text(status, message).withHeader("Cache-Control", "no-store");
    }
}
