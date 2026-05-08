package pro.deta.orion.lifecycle.flow;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class LifecycleFlowTest {
    @Test
    void startupFlowDocumentsPhaseOrder() {
        assertThat(LifecycleFlow.STARTUP.steps())
                .extracting(LifecycleStep::from, LifecycleStep::success, LifecycleStep::failure)
                .containsExactly(
                        tuple(ApplicationState.INIT, ApplicationState.STARTING, ApplicationState.FAILED),
                        tuple(ApplicationState.STARTING, ApplicationState.UP, ApplicationState.FAILED));
    }

    @Test
    void shutdownFlowDocumentsPhaseOrder() {
        assertThat(LifecycleFlow.SHUTDOWN.steps())
                .extracting(LifecycleStep::from, LifecycleStep::success, LifecycleStep::failure)
                .containsExactly(
                        tuple(ApplicationState.UP, ApplicationState.BEGIN_SHUTDOWN, ApplicationState.FAILED),
                        tuple(ApplicationState.BEGIN_SHUTDOWN, ApplicationState.STOPPING, ApplicationState.FAILED),
                        tuple(ApplicationState.STOPPING, ApplicationState.OFF, ApplicationState.FAILED));
    }

    @Test
    void flowDescriptionIsReadable() {
        assertThat(LifecycleFlow.STARTUP.describe()).contains(
                "STARTUP:",
                "INIT -> STARTING",
                "STARTING -> UP",
                "INIT -> FAILED on failure",
                "STARTING -> FAILED on failure");
    }

    @Test
    void flowMustBeContinuous() {
        assertThatThrownBy(() -> new LifecycleFlow("BROKEN", List.of(
                LifecycleStep.from(ApplicationState.INIT)
                        .to(ApplicationState.STARTING)
                        .build(),
                LifecycleStep.from(ApplicationState.STOPPING)
                        .to(ApplicationState.OFF)
                        .build())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BROKEN")
                .hasMessageContaining("STARTING")
                .hasMessageContaining("STOPPING");
    }
}
