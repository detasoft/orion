package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AgentLifecycleTest {
    @Test
    void startsInDeclarationOrderAndStopsInReverseOrder() {
        List<String> events = new ArrayList<>();
        AgentLifecycle lifecycle = new AgentLifecycle(List.of(
                service("transport", events, null),
                service("sessions", events, null)));

        lifecycle.start();
        assertThat(lifecycle.state()).isEqualTo(AgentLifecycle.State.RUNNING);

        lifecycle.close();
        lifecycle.close();

        assertThat(events).containsExactly(
                "start transport",
                "start sessions",
                "close sessions",
                "close transport");
        assertThat(lifecycle.state()).isEqualTo(AgentLifecycle.State.TERMINATED);
    }

    @Test
    void rollsBackStartedServicesWhenLaterStartupFails() {
        List<String> events = new ArrayList<>();
        AgentService failure = service("sessions", events, new Exception("cannot discover sessions"));
        AgentLifecycle lifecycle = new AgentLifecycle(List.of(service("transport", events, null), failure));

        assertThatExceptionOfType(AgentStartupException.class)
                .isThrownBy(lifecycle::start)
                .withCauseInstanceOf(Exception.class)
                .withMessageContaining("startup failed");

        assertThat(events).containsExactly(
                "start transport",
                "start sessions",
                "close sessions",
                "close transport");
        assertThat(lifecycle.state()).isEqualTo(AgentLifecycle.State.FAILED);
    }

    @Test
    void closeBeforeStartTerminatesWithoutStartingServices() {
        List<String> events = new ArrayList<>();
        AgentLifecycle lifecycle = new AgentLifecycle(List.of(service("transport", events, null)));

        lifecycle.close();

        assertThat(events).isEmpty();
        assertThat(lifecycle.state()).isEqualTo(AgentLifecycle.State.TERMINATED);
    }

    private static AgentService service(String name, List<String> events, Exception startupFailure) {
        return new AgentService() {
            @Override
            public void start() throws Exception {
                events.add("start " + name);
                if (startupFailure != null) {
                    throw startupFailure;
                }
            }

            @Override
            public void close() {
                events.add("close " + name);
            }
        };
    }
}
