package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.util.Map;

public class OrionAdminCreateRepositoryRoute extends BaseAdminRoute {
    private final ObjectMapper objectMapper;
    private final NativeGitRepositoryProvider gitRepositoryProvider;

    @Inject
    public OrionAdminCreateRepositoryRoute(NativeGitRepositoryProvider gitRepositoryProvider, ObjectMapper objectMapper) {
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
        String repositoryName = normalizeRepositoryName(request.name());
        Result<NativeGitRepository> created = gitRepositoryProvider.create(repositoryName);
        boolean repositoryCreated = true;
        if (created instanceof Result.Failure<NativeGitRepository> failure
                && failure.code() != Result.FailureCode.FILE_ALREADY_EXISTS) {
            failure.valueOrFailure("Cannot create repository " + repositoryName);
        } else if (created instanceof Result.Failure<NativeGitRepository>) {
            repositoryCreated = false;
        }
        Map<String, Object> body = Map.of("status", "ok", "created", repositoryCreated);
        return repositoryCreated ? OrionHttpResponse.created(body) : OrionHttpResponse.ok(body);
    }

    private static String normalizeRepositoryName(String rawRepositoryName) {
        String repositoryName = rawRepositoryName;
        while (repositoryName.startsWith("/")) {
            repositoryName = repositoryName.substring(1);
        }
        repositoryName = repositoryName.replaceFirst("\\.git$", "");
        if (repositoryName.isBlank()
                || repositoryName.contains("\0")
                || repositoryName.contains("\\")
                || repositoryName.contains("..")) {
            throw new IllegalArgumentException("Invalid Git repository path");
        }
        return repositoryName;
    }

    public record AdminRepositoryRequest(String name) {
    }
}
