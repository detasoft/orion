package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryConfigurationTest {
    @Test
    void acceptsOneReservedPrimaryUpstream() {
        RepositoryRemote upstream = primaryUpstream();

        OrionDocument.Repository repository = new OrionDocument.Repository(
                new RepositoryId("project"),
                "Project",
                "refs/heads/main",
                RepositoryPolicy.safeDefaults(),
                List.of(upstream));

        assertThat(repository.remotes()).containsExactly(upstream);
    }

    @Test
    void rejectsDuplicateAliases() {
        RepositoryRemote upstream = primaryUpstream();

        assertThatThrownBy(() -> repository(List.of(upstream, upstream)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate remote alias: upstream");
    }

    @Test
    void reservesUpstreamForThePrimaryRole() {
        assertThatThrownBy(() -> remote(new RemoteAlias("origin"), RemoteRole.PRIMARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRIMARY remote");
        assertThatThrownBy(() -> remote(RemoteAlias.UPSTREAM, RemoteRole.OUTBOUND_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRIMARY remote");
    }

    @Test
    void rejectsUnsafeRemoteUris() {
        assertThatThrownBy(() -> remote(URI.create("http://github.com/acme/project.git")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> remote(URI.create("https://token@github.com/acme/project.git")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
        assertThatThrownBy(() -> remote(URI.create("https:///acme/project.git")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> remote(URI.create("https://github.com/acme/project.git#token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragment");
        assertThatThrownBy(() -> remote(URI.create("https://github.com/acme/project.git?token=secret")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void rejectsInvalidBranchAndRefMappings() {
        assertThatThrownBy(() -> new OrionDocument.Repository(
                new RepositoryId("project"),
                "Project",
                "main",
                RepositoryPolicy.safeDefaults(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical full ref");
        assertThatThrownBy(() -> new RemoteRefMapping("main", "refs/heads/main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical full ref");
        assertThatThrownBy(() -> new RemoteRefMapping("refs/heads/*", "refs/heads/main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcards must match");
        assertThatThrownBy(() -> new RemoteRefMapping("refs/heads/*", "refs/tags/*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preserve the ref namespace");
    }

    @Test
    void rejectsRefsThatGitCheckRefFormatRejects() {
        assertInvalidRef("refs/heads/.hidden");
        assertInvalidRef("refs/heads/feature/.hidden");
        assertInvalidRef("refs/heads/feature.lock");
        assertInvalidRef("refs/heads/feature.lock/nested");
        assertInvalidRef("refs/heads/control-\u0001");
        assertInvalidRef("refs/heads/delete-\u007f");
    }

    @Test
    void rejectsInvalidSecretReferences() {
        assertThatThrownBy(() -> new ConfigurationSecretReference(
                ConfigurationSecretReference.Scope.REPOSITORY,
                " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical lowercase identifier");
        assertThatThrownBy(() -> new ConfigurationSecretReference(
                ConfigurationSecretReference.Scope.REPOSITORY,
                "../github-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical lowercase identifier");
    }

    @Test
    void copiesMutableConfigurationCollections() {
        EnumSet<RemoteTrigger> triggers = EnumSet.of(RemoteTrigger.LOCAL_REF_UPDATE);
        List<RemoteRefMapping> mappings = new ArrayList<>(List.of(RemoteRefMapping.allBranches()));
        RepositoryRemote upstream = new RepositoryRemote(
                RemoteAlias.UPSTREAM,
                RemoteRole.PRIMARY,
                RemoteProvider.GITHUB,
                URI.create("https://github.com/acme/project.git"),
                credential(),
                triggers,
                mappings,
                RemoteUpdatePolicy.fastForwardOnly());
        List<RepositoryRemote> remotes = new ArrayList<>(List.of(upstream));
        OrionDocument.Repository repository = repository(remotes);

        triggers.clear();
        mappings.clear();
        remotes.clear();

        assertThat(upstream.triggers()).containsExactly(RemoteTrigger.LOCAL_REF_UPDATE);
        assertThat(upstream.refMappings()).containsExactly(RemoteRefMapping.allBranches());
        assertThat(repository.remotes()).containsExactly(upstream);
        assertThatThrownBy(() -> repository.remotes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static OrionDocument.Repository repository(List<RepositoryRemote> remotes) {
        return new OrionDocument.Repository(
                new RepositoryId("project"),
                "Project",
                "refs/heads/main",
                RepositoryPolicy.safeDefaults(),
                remotes);
    }

    private static RepositoryRemote primaryUpstream() {
        return remote(RemoteAlias.UPSTREAM, RemoteRole.PRIMARY);
    }

    private static RepositoryRemote remote(RemoteAlias alias, RemoteRole role) {
        return new RepositoryRemote(
                alias,
                role,
                RemoteProvider.GITHUB,
                URI.create("https://github.com/acme/project.git"),
                credential(),
                Set.of(
                        RemoteTrigger.STARTUP_RECONCILE,
                        RemoteTrigger.LOCAL_REF_UPDATE,
                        RemoteTrigger.PERIODIC_AUDIT),
                List.of(RemoteRefMapping.allBranches()),
                RemoteUpdatePolicy.fastForwardOnly());
    }

    private static RepositoryRemote remote(URI uri) {
        return new RepositoryRemote(
                RemoteAlias.UPSTREAM,
                RemoteRole.PRIMARY,
                RemoteProvider.GITHUB,
                uri,
                credential(),
                Set.of(RemoteTrigger.STARTUP_RECONCILE),
                List.of(RemoteRefMapping.allBranches()),
                RemoteUpdatePolicy.fastForwardOnly());
    }

    private static ConfigurationSecretReference credential() {
        return new ConfigurationSecretReference(
                ConfigurationSecretReference.Scope.REPOSITORY,
                "github-token");
    }

    private static void assertInvalidRef(String ref) {
        assertThatThrownBy(() -> new RemoteRefMapping(ref, ref))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical full ref");
    }
}
