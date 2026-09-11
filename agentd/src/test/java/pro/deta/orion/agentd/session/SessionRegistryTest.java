package pro.deta.orion.agentd.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {
    @Test
    void distinguishesConstructionTimeEmptyStateFromTheFirstCompletedScan() {
        SessionRegistry registry = new SessionRegistry();

        assertThat(registry.readySnapshot().toCompletableFuture()).isNotDone();

        DiscoverySnapshot completed = DiscoverySnapshot.empty();
        registry.replace(completed);

        assertThat(registry.readySnapshot().toCompletableFuture()).isCompletedWithValue(completed);
    }

    @Test
    void publishesOrderedReplacementsAfterReadinessAndStopsAClosedObserver() {
        SessionRegistry registry = new SessionRegistry();
        List<DiscoverySnapshot> observed = new ArrayList<>();
        SessionRegistry.Observation observation = registry.observe((previous, next) -> observed.add(next));

        registry.replace(DiscoverySnapshot.empty());
        DiscoverySnapshot second = new DiscoverySnapshot(Map.of(), Map.of(
                "second", new DiscoveryIssue(
                        java.nio.file.Path.of("second"), DiscoveryIssue.Kind.DEGRADED, "second")));
        DiscoverySnapshot third = new DiscoverySnapshot(Map.of(), Map.of(
                "third", new DiscoveryIssue(
                        java.nio.file.Path.of("third"), DiscoveryIssue.Kind.DEGRADED, "third")));
        registry.replace(second);
        registry.replace(third);
        observation.close();
        registry.replace(DiscoverySnapshot.empty());

        assertThat(observed).containsExactly(second, third);
    }
}
