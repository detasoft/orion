package pro.deta.orion.lifecycle.task;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleTaskIdTest {
    @Test
    void taskIdsHaveReadableNames() {
        assertThat(OrionLifecycleTasks.JGIT_RUNTIME_STOP.toString()).isEqualTo("JGIT_RUNTIME_STOP");
        assertThat(OrionLifecycleTasks.ACL_LOAD.toString()).isEqualTo("ACL_LOAD");
        assertThat(OrionLifecycleTasks.TRANSPORTS_START.toString()).isEqualTo("TRANSPORTS_START");
        assertThat(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_START.toString()).isEqualTo("TRANSPORT_LIFECYCLE_START");
        assertThat(OrionLifecycleTasks.TRANSPORTS_STOP.toString()).isEqualTo("TRANSPORTS_STOP");
        assertThat(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_STOP.toString()).isEqualTo("TRANSPORT_LIFECYCLE_STOP");
    }

    @Test
    void exposesOnlyAggregateTransportLifecycleTaskIds() {
        assertThat(Arrays.stream(OrionLifecycleTasks.class.getDeclaredFields())
                .filter(field -> field.getType().equals(LifecycleTaskId.class))
                .map(Field::getName))
                .contains("TRANSPORTS_START", "TRANSPORT_LIFECYCLE_START",
                        "TRANSPORTS_STOP", "TRANSPORT_LIFECYCLE_STOP")
                .doesNotContain(
                        "SSH_TRANSPORT_INIT",
                        "HTTP_TRANSPORT_START",
                        "GIT_TRANSPORT_START",
                        "SSH_TRANSPORT_START",
                        "HTTP_TRANSPORT_STOP",
                        "GIT_TRANSPORT_STOP",
                        "SSH_TRANSPORT_STOP");
    }

    @Test
    void taskIdsRejectBlankNames() {
        assertThatThrownBy(() -> new LifecycleTaskId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
