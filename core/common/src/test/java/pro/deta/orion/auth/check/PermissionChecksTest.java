package pro.deta.orion.auth.check;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.acl.schema.AccessControl.TRUE_STRING;
import static pro.deta.orion.auth.SecurityContextHolder.getSc;
import static pro.deta.orion.auth.check.MatcherUtils.matchExpressionValue;
import static pro.deta.orion.auth.check.PermissionChecks.permissionChecker;

public class PermissionChecksTest {
    @Test
    public void matchInternalAsteriskSyntax() {
        // '*/orion', 'orion/*', '*/*', 'pre*/some'
        Assertions.assertTrue(matchExpressionValue("orion", "orion"));
        Assertions.assertTrue(matchExpressionValue("or*on", "orion"));
    }

    @Test
    public void writeAccessRequiresRepositoryWriteGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            AccessControl.Grant repositoryGrant = new AccessControl.Grant("repository-only", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "project");
            getSc().setUserIdentity(new InternalUserImpl("reader", List.of(repositoryGrant)));
            assertThatThrownBy(() -> permissionChecker().ALLOW_WRITE_ACCESS.assertThat("project"))
                    .isInstanceOf(OrionSecurityException.class);

            AccessControl.Grant writeGrant = new AccessControl.Grant("write", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.REPOSITORY, "project")
                    .addKey(AccessControl.GrantKey.WRITE, TRUE_STRING);
            getSc().setUserIdentity(new InternalUserImpl("writer", List.of(writeGrant)));

            assertThatCode(() -> permissionChecker().ALLOW_WRITE_ACCESS.assertThat("project"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> permissionChecker().ALLOW_WRITE_ACCESS.assertThat("other"))
                    .isInstanceOf(OrionSecurityException.class);
        }
    }

    @Test
    public void shutdownRequiresShutdownGrant() {
        try (SecurityContextHolder ignored = new SecurityContextHolder()) {
            getSc().setUserIdentity(new InternalUserImpl("regular", List.of()));
            assertThatThrownBy(() -> permissionChecker().ALLOW_TO_SHUTDOWN.assertThat("shutdown"))
                    .isInstanceOf(OrionSecurityException.class);

            AccessControl.Grant shutdownGrant = new AccessControl.Grant("shutdown", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.SHUTDOWN, TRUE_STRING);
            getSc().setUserIdentity(new InternalUserImpl("operator", List.of(shutdownGrant)));

            assertThatCode(() -> permissionChecker().ALLOW_TO_SHUTDOWN.assertThat("shutdown"))
                    .doesNotThrowAnyException();
        }
    }
}
