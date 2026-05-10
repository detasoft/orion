package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class AcmeHttpChallengeRoute extends AbstractOrionHttpRoute {
    public static final String CHALLENGE_PREFIX = "/.well-known/acme-challenge/";
    public static final String URL_PATTERN = CHALLENGE_PREFIX + "*";

    private final AcmeHttpChallengeService challengeService;

    @Inject
    public AcmeHttpChallengeRoute(AcmeHttpChallengeService challengeService) {
        super(URL_PATTERN, "GET");
        this.challengeService = challengeService;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        String token = tokenFrom(req);
        String authorization = challengeService.authorizationFor(token);
        if (authorization == null) {
            return OrionHttpResponse.empty(SC_NOT_FOUND);
        }
        return OrionHttpResponse.text(SC_OK, authorization);
    }

    private static String tokenFrom(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || !path.startsWith(CHALLENGE_PREFIX)) {
            return null;
        }
        return path.substring(CHALLENGE_PREFIX.length());
    }
}
