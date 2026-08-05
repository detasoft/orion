package pro.deta.orion.transport.git.netty;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;

import java.util.Objects;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public final class AuthenticatedNativeRepositoryAccessHook
        implements GitNativeRepositoryAccessHook {
    private final SecurityContext securityContext;

    public AuthenticatedNativeRepositoryAccessHook(
            SecurityContext securityContext) {
        this.securityContext = Objects.requireNonNull(
                securityContext,
                "securityContext");
    }

    @Override
    public void beforeReceive(String repositoryName) {
        require(() -> accessEnforcer().require(
                securityContext,
                SubjectAccessRules.authenticated()));
    }

    @Override
    public void beforeCreate(String repositoryName) {
        RepositoryResource repositoryResource =
                repositoryResource(repositoryName);
        require(() -> accessEnforcer().require(
                securityContext,
                repositoryResource,
                RepositoryAccessRules.create()));
    }

    @Override
    public void beforeWrite(String repositoryName) {
        RepositoryResource repositoryResource =
                repositoryResource(repositoryName);
        require(() -> accessEnforcer().require(
                securityContext,
                repositoryResource,
                RepositoryAccessRules.write()));
    }

    private static void require(AccessCheck accessCheck) {
        try {
            accessCheck.require();
        } catch (OrionSecurityException e) {
            throw new AccessDeniedException(e.getMessage(), e);
        }
    }

    private static RepositoryResource repositoryResource(
            String repositoryName) {
        return RepositoryResource.of(normalizeRepositoryName(repositoryName));
    }

    private static String normalizeRepositoryName(String repositoryName) {
        String normalized = Objects.requireNonNull(
                repositoryName,
                "repositoryName");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceFirst("\\.git$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "repositoryName must not be blank");
        }
        return normalized;
    }

    private interface AccessCheck {
        void require() throws OrionSecurityException;
    }
}
