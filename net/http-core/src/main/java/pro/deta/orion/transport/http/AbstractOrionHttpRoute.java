package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.ServletException;

import java.io.IOException;

import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.ANONYMOUS;

public abstract class AbstractOrionHttpRoute implements OrionHttpRoute {
    private final OrionHttpRouteDefinition definition;

    protected AbstractOrionHttpRoute(
            String urlPattern,
            OrionHttpRouteDefinition.Method... allowedMethods) {
        this(urlPattern, ANONYMOUS, allowedMethods);
    }

    protected AbstractOrionHttpRoute(
            String urlPattern,
            OrionHttpRouteDefinition.Authorization authorization,
            OrionHttpRouteDefinition.Method... allowedMethods) {
        definition = new OrionHttpRouteDefinition(urlPattern, authorization, allowedMethods);
    }

    @Override
    public OrionHttpRouteDefinition definition() {
        return definition;
    }

    @Override
    public void handle(OrionHttpExchange exchange) throws IOException, ServletException {
        HttpServletRequest request = exchange.request();
        OrionHttpResponse response = switch (exchange.method()) {
            case GET -> doGet(request);
            case HEAD -> doHead(request);
            case POST -> doPost(request);
            case PUT -> doPut(request);
            case DELETE -> doDelete(request);
            case PATCH -> doPatch(request);
        };
        exchange.send(response);
    }

    protected OrionHttpResponse doGet(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doHead(HttpServletRequest req) throws IOException {
        return doGet(req);
    }

    protected OrionHttpResponse doPut(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doDelete(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doPatch(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    private static OrionHttpResponse methodNotAllowed() {
        return OrionHttpResponse.empty(SC_METHOD_NOT_ALLOWED);
    }
}
