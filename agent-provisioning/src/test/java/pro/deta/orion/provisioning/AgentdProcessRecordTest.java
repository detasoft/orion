package pro.deta.orion.provisioning;

import org.junit.jupiter.api.Test;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLaunchId;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AgentdProcessRecordTest {
    @Test
    void roundTripsPathsWithoutShellOrDelimiterLoss() {
        AgentdProcessIdentity identity = new AgentdProcessIdentity(
                73, 9_000, "123456", "/opt/orion releases/v='1'",
                "/opt/orion releases/v='1'/agentd", launchId(), new AgentGeneration(5));

        assertThat(AgentdProcessRecord.parse(AgentdProcessRecord.serialize(identity))).isEqualTo(identity);
    }

    @Test
    void rejectsDuplicateUnexpectedControlAndOversizedFields() {
        String valid = AgentdProcessRecord.serialize(identity());

        assertThatIllegalArgumentException().isThrownBy(() -> AgentdProcessRecord.parse(valid + "pid=73\n"));
        assertThatIllegalArgumentException().isThrownBy(() -> AgentdProcessRecord.parse(valid + "other=x\n"));
        assertThatIllegalArgumentException().isThrownBy(() -> AgentdProcessRecord.parse(valid.replace(
                "nativeStartToken=123456", "nativeStartToken=12\u00013456")));
        assertThatIllegalArgumentException().isThrownBy(() -> AgentdProcessRecord.parse("x".repeat(16_385)));
    }

    private static AgentdProcessIdentity identity() {
        return new AgentdProcessIdentity(
                73, 9_000, "123456", "/opt/orion/releases/1", "/opt/orion/releases/1/agentd",
                launchId(), new AgentGeneration(5));
    }

    private static AgentLaunchId launchId() {
        return new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f"));
    }
}
