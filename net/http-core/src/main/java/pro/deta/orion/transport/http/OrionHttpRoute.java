package pro.deta.orion.transport.http;

import jakarta.servlet.ServletException;

import java.io.IOException;

public interface OrionHttpRoute {
    OrionHttpRouteDefinition definition();

    void handle(OrionHttpExchange exchange) throws IOException, ServletException;
}
