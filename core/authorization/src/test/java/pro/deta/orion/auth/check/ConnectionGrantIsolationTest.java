package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.v2.TeamId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionGrantIsolationTest {
    @Test
    void connectionActionsNeverAuthorizeRepositoryOrSystemAdministrationEvenWithMixedSelectors() {
        Grant grant = new Grant("connection", List.of(
                expression(GrantKey.CONNECTION, "*"),
                expression(GrantKey.REPOSITORY, "**"),
                expression(GrantKey.CREATE, "true"),
                expression(GrantKey.READ_WRITE, "true"),
                expression(GrantKey.ADMIN, "true"),
                expression(GrantKey.SHUTDOWN, "true")));
        SecurityContext system = SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("alice", List.of(grant)));
        assertThat(ApplicationAccessRules.admin().evaluate(system,
                ApplicationAdminResource.applicationAdmin()).allowed()).isFalse();
        assertThat(RepositoryAccessRules.create().evaluate(system,
                RepositoryResource.of("acme/team/new")).allowed()).isFalse();
        assertThat(ApplicationAccessRules.shutdown().evaluate(system,
                pro.deta.orion.auth.check.resource.ApplicationShutdownResource.applicationShutdown()).allowed()).isFalse();
        assertThat(pro.deta.orion.auth.check.rule.BranchAccessRules.push().evaluate(system,
                new pro.deta.orion.auth.check.resource.BranchResource(RepositoryResource.of("acme/team/new"),
                        "refs/heads/main")).allowed()).isFalse();
        User user = new User("alice", "", "", "", List.of(), List.of(), List.of(grant));
        OrionDocument.Organization organization = new OrionDocument.Organization(new OrganizationId("acme"), "",
                List.of(user), List.of(), List.of(), List.of(new OrionDocument.Team(new TeamId("team"), "",
                List.of(), List.of(), List.of())), List.of(), List.of(), List.of(), List.of());
        OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(organization));
        SecurityContext scoped = SecurityContext.createContext().withUserIdentity(
                new InternalUserImpl("alice", organization.id(), () -> document));
        assertThat(RepositoryAccessRules.create().evaluate(scoped,
                RepositoryResource.of("acme/team/new")).allowed()).isFalse();
    }

    private static GrantExpression expression(GrantKey key, String value) {
        return new GrantExpression(key, value);
    }
}
