package pro.deta.orion.transport.http;

import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.KeyMaterialAdministrationCapability;
import pro.deta.orion.schema.config.OrionConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.command.CommandCancellation;
import pro.deta.orion.command.CommandContext;
import pro.deta.orion.command.CommandFailureCode;
import pro.deta.orion.command.CommandLineParser;
import pro.deta.orion.command.CommandNode;
import pro.deta.orion.command.CommandPath;
import pro.deta.orion.command.CommandPresentation;
import pro.deta.orion.command.CommandRequest;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandRowQuery;
import pro.deta.orion.command.DefaultCommandDispatcher;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.orion.OrionDocument;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AcmeAdministrationTest {
    @Test
    void enforcesAdminAccessOnBothTransportsAndRedactsSshSecrets() throws Exception {
        DefaultCommandDispatcher dispatcher = dispatcher(new AcmeCommandCatalog(null, null));
        ObjectMapper mapper = new ObjectMapper();
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of(
                new OrionAdminAcmeConfigurationRoute(null, mapper),
                new OrionAdminKeyMaterialRoute(null, KeyMaterialAdministrationCapability.unavailable(), mapper),
                new OrionAdminAcmeCertificateRoute(null, mapper))), new OrionHttpResponseWriter(mapper));
        for (SecurityContext context : List.of(SecurityContext.createContext(),
                SecurityContext.createContext().withUserIdentity(new InternalUserImpl("reader", List.of())))) {
            for (String action : List.of("show", "configure", "issue")) {
                assertThat(dispatcher.dispatch(request("/acme " + action, context)))
                        .isInstanceOfSatisfying(CommandResult.Failure.class,
                                failure -> assertThat(failure.code()).isEqualTo(CommandFailureCode.ACCESS_DENIED));
            }
            for (String path : List.of("/api/admin/acme/configuration", "/api/admin/acme/certificate",
                    "/api/admin/key-material")) {
                for (String method : List.of("GET", "POST")) {
                    Response response = new Response();
                    servlet.service(httpRequest(method, path, "{}", context), response.proxy());
                    assertThat(response.status).isEqualTo(403);
                }
            }
        }
        assertThat(dispatcher.describe(request(
                "/acme configure provider=zerossl eab-hmac-key=private-secret", admin())).toString())
                .contains("<redacted>").doesNotContain("private-secret");
    }

    @Test
    void listsPresetsAndSettingsThroughUiApiAndSsh() throws Exception {
        OrionDesiredState desired = new OrionDesiredState();
        desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.of("revision"));
        AcmeConfigurationService configuration = new AcmeConfigurationService(desired, null, null,
                new AcmeCertificateService(new OrionConfiguration(), desired,
                        AcmeKeyMaterialCapability.unavailable(), null, null),
                ConfigurationMaterialCapability.unavailable(), new OrionConfiguration());
        ObjectMapper mapper = new ObjectMapper();
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of(
                new OrionAdminAcmeConfigurationRoute(configuration, mapper))), new OrionHttpResponseWriter(mapper));
        Response response = new Response();
        servlet.service(httpRequest("GET", "/api/admin/acme/configuration", "", admin()), response.proxy());
        assertThat(response.status).isEqualTo(200);
        assertThat(response.body.toString()).contains("letsencrypt", "zerossl", "google", "custom", "revision", "renewal", "disabled")
                .doesNotContain("eabHmacKey");
        assertThat(dispatcher(new AcmeCommandCatalog(configuration, null)).dispatch(request("/acme show", admin())))
                .isInstanceOfSatisfying(CommandResult.ObjectValue.class, result ->
                        assertThat(result.fields().get("renewalState").asText()).isEqualTo("disabled"));
    }

    static DefaultCommandDispatcher dispatcher(AcmeCommandCatalog catalog) {
        return new DefaultCommandDispatcher(new CommandLineParser(),
                CommandNode.builder().child("acme", catalog.commandTree()).build(), new CommandRowQuery());
    }

    static CommandRequest request(String line, SecurityContext context) {
        return new CommandRequest(line, new CommandContext(context, "request", "session", "source",
                CommandPath.root(), CommandPresentation.plain(), CommandCancellation.never(), Map.of()));
    }

    static SecurityContext admin() {
        AccessControl.Grant grant = new AccessControlDraft.Grant("admin", new ArrayList<>())
                .addKey(AccessControl.GrantKey.ADMIN, "true").toAccessControl();
        return SecurityContext.createContext().withUserIdentity(new InternalUserImpl("admin", List.of(grant)));
    }

    static HttpServletRequest httpRequest(String method, String path, String body, SecurityContext context) {
        ByteArrayInputStream bytes = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        ServletInputStream input = new ServletInputStream() {
            @Override public int read() { return bytes.read(); }
            @Override public boolean isFinished() { return bytes.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "getMethod" -> method;
                    case "getPathInfo" -> path;
                    case "getContentType" -> "application/json";
                    case "getInputStream" -> input;
                    case "getAttribute" -> context;
                    default -> throw new UnsupportedOperationException(called.toString());
                });
    }

    private static final class Response {
        private int status;
        private final StringWriter body = new StringWriter();

        HttpServletResponse proxy() {
            return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setStatus", "sendError" -> { status = (int) args[0]; yield null; }
                        case "setHeader", "setContentType" -> null;
                        case "getWriter" -> new PrintWriter(body);
                        default -> throw new UnsupportedOperationException(method.toString());
                    });
        }
    }
}
