package pro.deta.orion.transport.http;

import jakarta.servlet.ServletException;

import java.io.IOException;

public interface OrionHttpRoute {
    OrionHttpRouteDefinition definition();

    default void service(OrionHttpExchange exchange) throws IOException, ServletException {
        if (exchange.accepts(definition())) {
            handle(exchange);
        }
    }

    void handle(OrionHttpExchange exchange) throws IOException, ServletException;
}
