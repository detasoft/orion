package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.List;

public interface OrionHttpRoute {
    String urlPattern();

    String authorization();

    List<String> allowedMethods();

    OrionHttpResponse service(HttpServletRequest req) throws IOException;
}
