package pro.deta.orion.auth.check.rule;

import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.AccessDecision;
import pro.deta.orion.auth.check.AccessRule;
import pro.deta.orion.auth.check.resource.BranchResource;
import pro.deta.orion.auth.check.resource.GitFetchResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.git.common.GitFetchAccessRequest;
import pro.deta.orion.git.common.GitObjectId;

import java.util.Map;

/**
 * Rules for git-upload-pack negotiation. The fetch resource is a child of a repository, and every resolved
 * wanted object is checked through a branch resource under that same repository.
 */
public final class GitFetchAccessRules {
    private static final AccessRule<GitFetchResource> EVERY_WANTED_OBJECT_ALLOWED =
            new NamedAccessRule<>("repository fetch", GitFetchAccessRules::evaluateEveryWantedObjectAllowed);

    private GitFetchAccessRules() {
    }

    public static AccessRule<GitFetchResource> everyWantedObjectAllowed() {
        return EVERY_WANTED_OBJECT_ALLOWED;
    }

    private static AccessDecision evaluateEveryWantedObjectAllowed(SecurityContext securityContext, GitFetchResource resource) {
        RepositoryResource repository = resource.parentResource();
        AccessDecision repositoryRead = RepositoryAccessRules.read().evaluate(securityContext, repository);
        if (!repositoryRead.allowed()) {
            return AccessDecision.deny("parent repository read denied: " + repositoryRead.reason());
        }

        GitFetchAccessRequest request = resource.request();
        Map<GitObjectId, String> wantedBranches = request.refResolver().resolveBranchNames(request.wants());
        for (GitObjectId want : request.wants()) {
            if (!wantedBranches.containsKey(want)) {
                return AccessDecision.deny("wanted object " + want.value() + " was not resolved in " + request.repositoryName());
            }
        }
        for (GitObjectId want : request.wants()) {
            String branchName = wantedBranches.get(want);
            AccessDecision branchDecision = BranchAccessRules.fetch().evaluate(
                    securityContext,
                    BranchResource.of(repository, branchName));
            if (!branchDecision.allowed()) {
                return AccessDecision.deny("wanted object " + want.value() + " denied on branch " + branchName + ": " + branchDecision.reason());
            }
        }
        return AccessDecision.allow("all wanted objects resolve to granted branches");
    }
}
