package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class AcmeHttpChallengeService {
    private final Map<String, String> authorizations = new ConcurrentHashMap<>();

    @Inject
    public AcmeHttpChallengeService() {
    }

    public void registerChallenge(String token, String authorization) {
        validateToken(token);
        if (authorization == null || authorization.isBlank()) {
            throw new IllegalArgumentException("ACME challenge authorization is required");
        }
        authorizations.put(token, authorization);
    }

    public void removeChallenge(String token) {
        validateToken(token);
        authorizations.remove(token);
    }

    public String authorizationFor(String token) {
        if (!validToken(token)) {
            return null;
        }
        return authorizations.get(token);
    }

    private static void validateToken(String token) {
        if (!validToken(token)) {
            throw new IllegalArgumentException("ACME challenge token is required");
        }
    }

    private static boolean validToken(String token) {
        return token != null && !token.isBlank() && !token.contains("/");
    }
}
