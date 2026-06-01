package pro.deta.orion.lifecycle.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleTaskIdTest {
    @Test
    void taskIdsHaveReadableNames() {
        assertThat(OrionLifecycleTasks.SSH_TRANSPORT_INIT.toString()).isEqualTo("SSH_TRANSPORT_INIT");
        assertThat(OrionLifecycleTasks.JGIT_RUNTIME_STOP.toString()).isEqualTo("JGIT_RUNTIME_STOP");
        assertThat(OrionLifecycleTasks.ACL_LOAD.toString()).isEqualTo("ACL_LOAD");
        assertThat(OrionLifecycleTasks.TRANSPORTS_START.toString()).isEqualTo("TRANSPORTS_START");
        assertThat(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_START.toString()).isEqualTo("TRANSPORT_LIFECYCLE_START");
        assertThat(OrionLifecycleTasks.TRANSPORTS_STOP.toString()).isEqualTo("TRANSPORTS_STOP");
        assertThat(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_STOP.toString()).isEqualTo("TRANSPORT_LIFECYCLE_STOP");
        assertThat(OrionLifecycleTasks.HTTP_TRANSPORT_STOP.toString()).isEqualTo("HTTP_TRANSPORT_STOP");
    }

    @Test
    void taskIdsRejectBlankNames() {
        assertThatThrownBy(() -> new LifecycleTaskId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
