package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletException;

import java.io.IOException;
import java.util.List;

public interface OrionHttpRoute {
    String urlPattern();

    String authorization();

    List<String> allowedMethods();

    default void handle(HttpServletRequest req, HttpServletResponse resp, OrionHttpResponseWriter responseWriter) throws IOException, ServletException {
        responseWriter.write(resp, service(req));
    }

    default OrionHttpResponse service(HttpServletRequest req) throws IOException, ServletException {
        throw new UnsupportedOperationException("Route writes response directly");
    }
}
