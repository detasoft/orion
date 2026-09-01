package pro.deta.orion.git.parser.wire.exchange;

import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyReceiveCommandTest {
    private static final GitObjectId OLD_ID = GitObjectId.of("1".repeat(40));
    private static final GitObjectId NEW_ID = GitObjectId.of("2".repeat(40));

    @Test
    void rejectsInvalidFullRefNameComponents() {
        for (String refName : List.of(
                "main",
                "refs/heads/.hidden",
                "refs/heads/topic..branch",
                "refs/heads/topic//branch",
                "refs/heads/topic@{one}",
                "refs/heads/topic.lock",
                "refs/heads/topic.")) {
            assertThatThrownBy(() -> new LegacyReceiveCommand(
                    OLD_ID,
                    NEW_ID,
                    refName))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ref name");
        }
    }
}
