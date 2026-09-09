package pro.deta.orion.transport.git.auth;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.BranchResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.BranchAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;
import pro.deta.orion.git.parser.wire.GitNativeRepositoryAccessHook;

import java.util.List;
import java.util.Objects;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public final class AuthenticatedRepositoryAccessHook
        implements GitNativeRepositoryAccessHook {
    private static final String BRANCH_REF_PREFIX = "refs/heads/";

    private final SecurityContext securityContext;

    public AuthenticatedRepositoryAccessHook(
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
    public void beforeRead(String repositoryName) {
        RepositoryResource repositoryResource = RepositoryResource.of(repositoryName);
        require(() -> accessEnforcer().require(
                securityContext,
                SubjectAccessRules.authenticated()));
        require(() -> accessEnforcer().require(
                securityContext,
                repositoryResource,
                RepositoryAccessRules.read()));
    }

    @Override
    public void beforeFetch(
            String repositoryName,
            List<String> branchNames) {
        Objects.requireNonNull(branchNames, "branchNames");
        if (branchNames.isEmpty()) {
            throw new AccessDeniedException(
                    "Requested Git object does not resolve to a reachable branch",
                    null);
        }
        RepositoryResource repositoryResource = RepositoryResource.of(repositoryName);
        AccessDeniedException denied = null;
        for (String branchName : branchNames) {
            try {
                require(() -> accessEnforcer().require(
                        securityContext,
                        BranchResource.of(repositoryResource, branchName),
                        BranchAccessRules.fetch()));
                return;
            } catch (AccessDeniedException error) {
                denied = error;
            }
        }
        throw denied;
    }

    @Override
    public void beforeCreate(String repositoryName) {
        RepositoryResource repositoryResource = RepositoryResource.of(repositoryName);
        require(() -> accessEnforcer().require(
                securityContext,
                repositoryResource,
                RepositoryAccessRules.create()));
    }

    @Override
    public void beforeWrite(String repositoryName) {
        RepositoryResource repositoryResource = RepositoryResource.of(repositoryName);
        require(() -> accessEnforcer().require(
                securityContext,
                repositoryResource,
                RepositoryAccessRules.write()));
    }

    @Override
    public void beforeUpdate(
            String repositoryName,
            String refName,
            boolean force) {
        RepositoryResource repositoryResource = RepositoryResource.of(repositoryName);
        if (refName.startsWith(BRANCH_REF_PREFIX)) {
            String branchName = refName.substring(BRANCH_REF_PREFIX.length());
            require(() -> accessEnforcer().require(
                    securityContext,
                    BranchResource.of(repositoryResource, branchName),
                    BranchAccessRules.push()));
        }
        if (force) {
            require(() -> accessEnforcer().require(
                    securityContext,
                    repositoryResource,
                    RepositoryAccessRules.force()));
        }
    }

    private static void require(AccessCheck accessCheck) {
        try {
            accessCheck.require();
        } catch (OrionSecurityException e) {
            throw new AccessDeniedException(e.getMessage(), e);
        }
    }

    private interface AccessCheck {
        void require() throws OrionSecurityException;
    }
}
