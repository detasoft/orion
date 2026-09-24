package pro.deta.orion.auth.check;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.UserId;
import pro.deta.orion.schema.orion.GrantAddress;
import pro.deta.orion.schema.orion.RoleAddress;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.ScopedRole;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Evaluates assigned grants from a validated immutable organization snapshot. Roles apply only inside
 * their assignment scope, including when they reuse ancestor roles or grants. Any matching assigned
 * deny overrides all allows. The caller supplies operation-specific expression matching; this class
 * owns scope inheritance and role traversal, not transport or operation policy.
 */
public final class ScopedAccess {
    private ScopedAccess() {
    }

    public static boolean allows(OrionDocument.Organization organization, UserId userId,
            ConfigurationScope target, Predicate<List<AccessControl.GrantExpression>> matches) {
        boolean allowed = false;
        for (AssignedGrant grant : assignedGrants(organization, userId, target, false)) {
            if (matches.test(grant.expressions())) {
                if (grant.effect() == ScopedGrant.Effect.DENY) return false;
                allowed = true;
            }
        }
        return allowed;
    }

    public record AssignedGrant(ScopedGrant.Effect effect, List<AccessControl.GrantExpression> expressions) {
        public AssignedGrant {
            expressions = List.copyOf(expressions);
        }
    }

    public static List<AssignedGrant> assignedGrants(OrionDocument.Organization organization, UserId userId,
            ConfigurationScope target, boolean allowMissingRepository) {
        if (!organization.id().equals(target.organizationId())) return List.of();
        AccessControl.User user = null;
        for (AccessControl.User candidate : organization.users()) {
            if (candidate.getId().equals(userId.value())) user = candidate;
        }
        if (user == null) return List.of();

        Map<RoleAddress, ScopedRole> roles = new HashMap<>();
        Map<GrantAddress, ScopedGrant> grants = new HashMap<>();
        ConfigurationScope organizationScope = ConfigurationScope.organization(organization.id());
        index(organizationScope, organization.roles(), organization.grants(), roles, grants);
        if (target.teamId().isPresent()) {
            OrionDocument.Team team = null;
            for (OrionDocument.Team candidate : organization.teams()) {
                if (candidate.id().equals(target.teamId().orElseThrow())) team = candidate;
            }
            if (team == null) return List.of();
            index(ConfigurationScope.team(organization.id(), team.id()), team.roles(), team.grants(), roles, grants);
            if (target.repositoryId().isPresent()) {
                OrionDocument.Repository repository = null;
                for (OrionDocument.Repository candidate : team.repositories()) {
                    if (candidate.id().equals(target.repositoryId().orElseThrow())) repository = candidate;
                }
                if (repository == null) {
                    if (!allowMissingRepository) return List.of();
                } else {
                    index(target, repository.roles(), repository.grants(), roles, grants);
                }
            }
        }

        List<AssignedGrant> assigned = new ArrayList<>();
        for (AccessControl.Grant direct : user.getGrants()) {
            assigned.add(new AssignedGrant(ScopedGrant.Effect.ALLOW, direct.getInfo()));
        }
        ArrayDeque<RoleAddress> pending = new ArrayDeque<>();
        for (String assignment : user.getRoles()) {
            RoleAddress address = RoleAddress.parse(assignment);
            if (address.scope().isSameOrAncestorOf(target)) pending.add(address);
        }
        Set<RoleAddress> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            RoleAddress address = pending.removeFirst();
            if (!visited.add(address)) continue;
            ScopedRole role = roles.get(address);
            if (role == null) return List.of();
            for (GrantAddress reference : role.grantReferences()) {
                ScopedGrant grant = grants.get(reference);
                if (grant == null) return List.of();
                assigned.add(new AssignedGrant(grant.effect(), grant.expressions()));
            }
            pending.addAll(role.roleReferences());
        }
        return List.copyOf(assigned);
    }

    private static void index(ConfigurationScope scope, List<ScopedRole> scopedRoles,
            List<ScopedGrant> scopedGrants, Map<RoleAddress, ScopedRole> roles,
            Map<GrantAddress, ScopedGrant> grants) {
        for (ScopedRole role : scopedRoles) roles.put(new RoleAddress(scope, role.id()), role);
        for (ScopedGrant grant : scopedGrants) grants.put(new GrantAddress(scope, grant.id()), grant);
    }
}
