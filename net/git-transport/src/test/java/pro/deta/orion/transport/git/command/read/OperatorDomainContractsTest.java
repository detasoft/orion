package pro.deta.orion.transport.git.command.read;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorDomainContractsTest {
    @Test
    void queryResultsCopySnapshotsAndValidateAvailabilityMetadata() {
        List<String> mutable = new ArrayList<>(List.of("first"));
        OperatorQueryResult.AvailableSnapshot<String> available =
                new OperatorQueryResult.AvailableSnapshot<>(mutable);
        OperatorDomainViews.SystemResourceView resources =
                new OperatorDomainViews.SystemResourceView(1, 0, 0, 0);
        OperatorQueryResult.AvailableValue<OperatorDomainViews.SystemResourceView> scalar =
                new OperatorQueryResult.AvailableValue<>(resources);
        RuntimeException cause = new RuntimeException("secret detail");
        OperatorQueryResult.Failed<String> failed = new OperatorQueryResult.Failed<>("repository", cause);

        mutable.add("second");

        assertThat(available.value()).containsExactly("first");
        assertThatThrownBy(() -> available.value().add("third"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(scalar.value()).isEqualTo(resources);
        assertThat(available.toString()).isEqualTo("AvailableSnapshot");
        assertThat(scalar.toString()).isEqualTo("AvailableValue");
        assertThat(available.toString()).doesNotContain("first", "1");
        assertThat(scalar.toString()).doesNotContain("processors", "1");
        assertThat(failed.toString()).doesNotContain("secret detail");
        assertThatThrownBy(() -> new OperatorQueryResult.AvailableSnapshot<String>(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OperatorQueryResult.AvailableValue<OperatorDomainViews.SystemResourceView>(
                null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OperatorQueryResult.Unavailable<>(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatorQueryResult.Failed<>("repository", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void domainViewsValidateRequiredValuesAndCounts() {
        OperatorDomainViews.RepositoryView repository = new OperatorDomainViews.RepositoryView(
                "repo", Optional.of("demo"), "internal/demo", "refs/heads/main", 2, Optional.of("org"));
        OperatorDomainViews.OrganizationView organization =
                new OperatorDomainViews.OrganizationView("org", Optional.of("acme"));
        OperatorDomainViews.UserView user =
                new OperatorDomainViews.UserView("user", Optional.empty(), "org", "principal");
        OperatorDomainViews.SessionView session = new OperatorDomainViews.SessionView(
                "session", Optional.empty(), "RUNNING", "principal", Optional.of("internal/demo"));
        OperatorDomainViews.ProxyView proxy = new OperatorDomainViews.ProxyView(
                "proxy", Optional.empty(), "READY", Optional.of("internal/demo"), "origin");
        OperatorDomainViews.SystemResourceView resources =
                new OperatorDomainViews.SystemResourceView(4, 1, 2, 3);
        OperatorDomainViews.ServiceView service =
                new OperatorDomainViews.ServiceView("runtime.git", "git", "NEW", "NEW", false);

        assertThat(List.of(repository, organization, user, session, proxy, resources, service)).hasSize(7);
        assertThatThrownBy(() -> new OperatorDomainViews.RepositoryView(
                " ", Optional.empty(), "repo", "head", 0, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatorDomainViews.RepositoryView(
                "id", Optional.empty(), "repo", "head", -1, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatorDomainViews.SystemResourceView(0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatorDomainViews.SessionView(
                "id", null, "state", "owner", Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void dynamicViewIdentifiersAndAliasesMustBeAddressablePathSegments() {
        for (String invalid : List.of(".", "..", "has/slash", "two words", "quote\"", "quote'", "escape\\")) {
            assertThatThrownBy(() -> repository(invalid, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> organization(invalid, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> session(invalid, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> proxy(invalid, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> user(invalid, Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String invalidAlias : List.of(".", "..", "has/slash", "two words", "quote\"", "escape\\")) {
            Optional<String> alias = Optional.of(invalidAlias);
            assertThatThrownBy(() -> repository("repo", alias)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> organization("org", alias)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> session("session", alias)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> proxy("proxy", alias)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> user("user", alias)).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(repository("%2E", Optional.empty()).id()).isEqualTo("%2E");
        assertThat(organization("org%2Facme", Optional.of("acme")).name()).contains("acme");
    }

    private static OperatorDomainViews.RepositoryView repository(String id, Optional<String> name) {
        return new OperatorDomainViews.RepositoryView(
                id, name, "repository", "refs/heads/main", 0, Optional.empty());
    }

    private static OperatorDomainViews.OrganizationView organization(String id, Optional<String> name) {
        return new OperatorDomainViews.OrganizationView(id, name);
    }

    private static OperatorDomainViews.SessionView session(String id, Optional<String> name) {
        return new OperatorDomainViews.SessionView(
                id, name, "RUNNING", "owner", Optional.empty());
    }

    private static OperatorDomainViews.ProxyView proxy(String id, Optional<String> name) {
        return new OperatorDomainViews.ProxyView(id, name, "READY", Optional.empty(), "origin");
    }

    private static OperatorDomainViews.UserView user(String id, Optional<String> name) {
        return new OperatorDomainViews.UserView(id, name, "org", "principal");
    }

    private static OperatorDomainViews.ServiceView service(String id) {
        return new OperatorDomainViews.ServiceView(id, "service", "NEW", "NEW", false);
    }
}
