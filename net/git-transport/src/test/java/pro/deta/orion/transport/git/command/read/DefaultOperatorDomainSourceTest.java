package pro.deta.orion.transport.git.command.read;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.StateMachine;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.util.Result;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultOperatorDomainSourceTest {
    @Test
    void snapshotsRepositoriesWithStablePathSafeIdsAndOriginalNames() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        provider.create("internal/configuration");
        provider.create("demo");
        DefaultOperatorDomainSource source = source(provider, emptyRuntime(), () -> resources());

        OperatorQueryResult.AvailableSnapshot<OperatorDomainViews.RepositoryView> result =
                availableSnapshot(source.repositories());

        assertThat(result.value()).containsExactly(
                new OperatorDomainViews.RepositoryView(
                        "demo", Optional.of("demo"), "demo", "refs/heads/main", 0, Optional.empty()),
                new OperatorDomainViews.RepositoryView(
                        "internal%2Fconfiguration",
                        Optional.empty(),
                        "internal/configuration",
                        "refs/heads/main",
                        0,
                        Optional.empty()));
    }

    @Test
    void percentEncodesUtf8BytesWithoutFormEncoding() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        provider.create("space and/é");
        provider.create("quote'name");

        List<OperatorDomainViews.RepositoryView> views = availableSnapshot(source(
                provider, emptyRuntime(), DefaultOperatorDomainSourceTest::resources).repositories()).value();

        assertThat(views)
                .extracting(OperatorDomainViews.RepositoryView::id)
                .containsExactly("quote%27name", "space%20and%2F%C3%A9");
        assertThat(views).allSatisfy(view -> assertThat(view.name()).isEmpty());
    }

    @Test
    void encodesWholeNavigationOperatorRepositoryNames() {
        InMemoryNativeGitRepositoryProvider provider = new InMemoryNativeGitRepositoryProvider();
        provider.create(".");
        provider.create("..");

        assertThat(availableSnapshot(source(provider, emptyRuntime(), DefaultOperatorDomainSourceTest::resources)
                .repositories()).value())
                .extracting(OperatorDomainViews.RepositoryView::id)
                .containsExactly("%2E", "%2E%2E");
    }

    @Test
    void returnsOneFailureInsteadOfAPartialRepositorySnapshot() {
        NativeGitRepositoryProvider provider = new NativeGitRepositoryProvider() {
            @Override
            public List<String> repositoryNames() {
                return List.of("available", "broken");
            }

            @Override
            public boolean exists(String repositoryName) {
                return true;
            }

            @Override
            public Result<NativeGitRepository> find(String repositoryName) {
                if (repositoryName.equals("broken")) {
                    return new Result.Failure<>(Result.FailureCode.GENERAL, "sensitive detail");
                }
                return new InMemoryNativeGitRepositoryProvider().create(repositoryName);
            }

            @Override
            public Result<NativeGitRepository> create(String repositoryName) {
                throw new UnsupportedOperationException();
            }
        };

        OperatorQueryResult<List<OperatorDomainViews.RepositoryView>> result =
                source(provider, emptyRuntime(), DefaultOperatorDomainSourceTest::resources).repositories();

        assertThat(result).isInstanceOf(OperatorQueryResult.Failed.class);
        assertThat(result.toString()).doesNotContain("sensitive detail", "available");
    }

    @Test
    void providesMetricsAndRecursivelySortedLifecycleServices() {
        StateMachine zeta = StateMachineDefinition.define().name("Zeta Service").build().newStateMachine();
        StateMachine alpha = StateMachineDefinition.define().name("Alpha Service").build().newStateMachine();
        AggregateStateMachine runtime = new AggregateStateMachine(StateMachineDefinition.define()
                .name("Runtime Service")
                .child("zeta", zeta)
                .child("alpha", alpha)
                .build());
        DefaultOperatorDomainSource source = source(
                new InMemoryNativeGitRepositoryProvider(), runtime, DefaultOperatorDomainSourceTest::resources);

        assertThat(availableValue(source.systemResources()).value()).isEqualTo(resources());
        assertThat(availableSnapshot(source.services()).value())
                .extracting(OperatorDomainViews.ServiceView::id)
                .containsExactly("Runtime%20Service", "Runtime%20Service.alpha", "Runtime%20Service.zeta");
        assertThat(availableSnapshot(source.services()).value())
                .extracting(OperatorDomainViews.ServiceView::name)
                .containsExactly("Runtime Service", "Alpha Service", "Zeta Service");
    }

    @Test
    void lifecycleIdsDistinguishDottedKeysFromNestedKeys() {
        StateMachine nested = StateMachineDefinition.define()
                .name("nested")
                .child("bar", StateMachineDefinition.define().name("bar").build().newStateMachine())
                .build()
                .newStateMachine();
        AggregateStateMachine runtime = new AggregateStateMachine(StateMachineDefinition.define()
                .name("runtime")
                .child("foo", nested)
                .child("foo.bar", StateMachineDefinition.define().name("dotted").build().newStateMachine())
                .build());

        assertThat(availableSnapshot(source(
                new InMemoryNativeGitRepositoryProvider(),
                runtime,
                DefaultOperatorDomainSourceTest::resources).services()).value())
                .extracting(OperatorDomainViews.ServiceView::id)
                .containsExactly("runtime", "runtime.foo", "runtime.foo%2Ebar", "runtime.foo.bar");
    }

    @Test
    void lifecycleSnapshotUsesExactDirectChildrenAcrossRecursiveNameCollisions() {
        StateMachine nestedCollision = StateMachineDefinition.define()
                .name("nested-collision")
                .build()
                .newStateMachine();
        StateMachine branch = StateMachineDefinition.define()
                .name("branch")
                .child("target", nestedCollision)
                .build()
                .newStateMachine();
        StateMachine directTarget = StateMachineDefinition.define()
                .name("direct-target")
                .build()
                .newStateMachine();
        StateMachine sameAsRootName = StateMachineDefinition.define()
                .name("same-as-root-name")
                .build()
                .newStateMachine();
        AggregateStateMachine runtime = new AggregateStateMachine(StateMachineDefinition.define()
                .name("runtime")
                .child("branch", branch)
                .child("target", directTarget)
                .child("runtime", sameAsRootName)
                .build());

        assertThat(availableSnapshot(source(
                new InMemoryNativeGitRepositoryProvider(),
                runtime,
                DefaultOperatorDomainSourceTest::resources).services()).value())
                .extracting(OperatorDomainViews.ServiceView::id, OperatorDomainViews.ServiceView::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("runtime", "runtime"),
                        org.assertj.core.groups.Tuple.tuple("runtime.branch", "branch"),
                        org.assertj.core.groups.Tuple.tuple("runtime.branch.target", "nested-collision"),
                        org.assertj.core.groups.Tuple.tuple("runtime.runtime", "same-as-root-name"),
                        org.assertj.core.groups.Tuple.tuple("runtime.target", "direct-target"));
    }

    @Test
    void leavesUnfinishedDomainSourcesExplicitlyUnavailable() {
        DefaultOperatorDomainSource source = source(
                new InMemoryNativeGitRepositoryProvider(),
                emptyRuntime(),
                DefaultOperatorDomainSourceTest::resources);

        assertThat(source.organizations()).isEqualTo(new OperatorQueryResult.Unavailable<>("organization"));
        assertThat(source.organizationUsers("org"))
                .isEqualTo(new OperatorQueryResult.Unavailable<>("organization"));
        assertThat(source.organizationRepositories("org"))
                .isEqualTo(new OperatorQueryResult.Unavailable<>("organization"));
        assertThat(source.sessions()).isEqualTo(new OperatorQueryResult.Unavailable<>("session"));
        assertThat(source.proxies()).isEqualTo(new OperatorQueryResult.Unavailable<>("proxy"));
    }

    private static DefaultOperatorDomainSource source(
            NativeGitRepositoryProvider provider,
            AggregateStateMachine runtime,
            RuntimeMetrics metrics) {
        return new DefaultOperatorDomainSource(provider, runtime, metrics);
    }

    private static AggregateStateMachine emptyRuntime() {
        return new AggregateStateMachine(StateMachineDefinition.define().name("runtime").build());
    }

    private static OperatorDomainViews.SystemResourceView resources() {
        return new OperatorDomainViews.SystemResourceView(8, 100, 200, 300);
    }

    @SuppressWarnings("unchecked")
    private static <T> OperatorQueryResult.AvailableSnapshot<T> availableSnapshot(
            OperatorQueryResult<List<T>> result) {
        assertThat(result).isInstanceOf(OperatorQueryResult.AvailableSnapshot.class);
        return (OperatorQueryResult.AvailableSnapshot<T>) result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends OperatorQueryResult.ScalarValue>
            OperatorQueryResult.AvailableValue<T> availableValue(OperatorQueryResult<T> result) {
        assertThat(result).isInstanceOf(OperatorQueryResult.AvailableValue.class);
        return (OperatorQueryResult.AvailableValue<T>) result;
    }
}
