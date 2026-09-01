package pro.deta.orion.git.client;

import java.net.http.HttpRequest;

/**
 * Adds authentication or provider-specific headers to an outbound Git HTTP request.
 */
@FunctionalInterface
public interface GitHttpRequestConfigurer {
    void configure(HttpRequest.Builder request);

    static GitHttpRequestConfigurer none() {
        return request -> { };
    }
}
