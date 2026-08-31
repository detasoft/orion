package pro.deta.orion.transport.git.auth;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;

import java.util.Objects;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public final class AuthenticatedRepositoryAccessHook
        implements GitNativeRepositoryAccessHook {
    private final SecurityContext securityContext;
    private final boolean strictRepositoryName;

    public AuthenticatedRepositoryAccessHook(
            SecurityContext securityContext) {
        this(securityContext, false);
    }

    public AuthenticatedRepositoryAccessHook(
            SecurityContext securityContext,
            boolean strictRepositoryName) {
        this.securityContext = Objects.requireNonNull(
                securityContext,
                "securityContext");
        this.strictRepositoryName = strictRepositoryName;
    }

    @Override
    public void beforeReceive(String repositoryName) {
        require(() -> accessEnforcer().require(
                securityContext,
                SubjectAccessRules.authenticated()));
    }

    @Override
    public void beforeRead(String repositoryName) {
        RepositoryResource repositoryResource =
                repositoryResource(
                        repositoryName,
                        strictRepositoryName);
        require(() -> accessEnforcer().require(
                securityContext,
                SubjectAccessRules.authenticated()));
        require(() -> accessEnforcer().require(
                securityContext,
                repositoryResource,
                RepositoryAccessRules.read()));
    }

    @Override
    public void beforeCreate(String repositoryName) {
        RepositoryResource repositoryResource =
                repositoryResource(
                        repositoryName,
                        strictRepositoryName);
        require(() -> accessEnforcer().require(
                securityContext,
                repositoryResource,
                RepositoryAccessRules.create()));
    }

    @Override
    public void beforeWrite(String repositoryName) {
        RepositoryResource repositoryResource =
                repositoryResource(
                        repositoryName,
                        strictRepositoryName);
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
            String repositoryName,
            boolean strictRepositoryName) {
        return RepositoryResource.of(normalizeRepositoryName(
                repositoryName,
                strictRepositoryName));
    }

    private static String normalizeRepositoryName(
            String repositoryName,
            boolean strictRepositoryName) {
        String normalized;
        if (strictRepositoryName) {
            normalized = repositoryName == null ? "" : repositoryName;
        } else {
            normalized = Objects.requireNonNull(
                    repositoryName,
                    "repositoryName");
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceFirst("\\.git$", "");
        if (strictRepositoryName
                && (normalized.contains("\0")
                || normalized.contains("\\")
                || normalized.contains(".."))) {
            throw new IllegalArgumentException(
                    "Invalid Git repository path");
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(strictRepositoryName
                    ? "Invalid Git repository path"
                    : "repositoryName must not be blank");
        }
        return normalized;
    }

    private interface AccessCheck {
        void require() throws OrionSecurityException;
    }
}
