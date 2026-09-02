package pro.deta.orion.agentd.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLaunchContextTest {
    @Test
    void createsFreshInstanceIdentityForEveryProcessContext() {
        AgentConfiguration configuration = AgentConfiguration.parse(new String[]{
                "--server", "https://agent.test",
                "--state-dir", "target/state",
                "--agent-id", "agent-1",
                "--generation", "7",
                "--launch-id", "10010203-0405-0607-0809-0a0b0c0d0e0f",
                "--agent-version", "1.0.0"
        });

        try (AgentLaunchContext first = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]));
             AgentLaunchContext second = AgentLaunchContext.create(configuration, new LaunchPermit(new byte[32]))) {
            assertThat(first.agentId()).isEqualTo(configuration.agentId());
            assertThat(first.generation()).isEqualTo(configuration.generation());
            assertThat(first.launchId()).isEqualTo(configuration.launchId());
            assertThat(first.instanceId()).isNotEqualTo(second.instanceId());
        }
    }
}
