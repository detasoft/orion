package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.GitRepositoryProvider;

import java.io.IOException;
import java.util.Map;

public class OrionAdminCreateRepositoryRoute extends BaseAdminRoute {
    private final ObjectMapper objectMapper;
    private final GitRepositoryProvider gitRepositoryProvider;

    @Inject
    public OrionAdminCreateRepositoryRoute(GitRepositoryProvider gitRepositoryProvider, ObjectMapper objectMapper) {
        super(OrionAdminPaths.REPOSITORIES, "POST");
        this.gitRepositoryProvider = gitRepositoryProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        AdminRepositoryRequest request = objectMapper.readValue(req.getInputStream(), AdminRepositoryRequest.class);
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Repository name is required");
        }
        gitRepositoryProvider.findOrCreate(request.name()).valueOrFailure("Cannot create repository " + request.name());
        return OrionHttpResponse.created(Map.of("status", "ok"));
    }

    public record AdminRepositoryRequest(String name) {
    }
}
