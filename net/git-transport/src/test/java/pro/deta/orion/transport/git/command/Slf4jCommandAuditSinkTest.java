package pro.deta.orion.transport.git.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.command.audit.CommandAuditRecord;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Slf4jCommandAuditSinkTest {
    @Test
    void formattedCredentialAuditContainsOnlyRedactedPastedKey() {
        String pastedKey = "ssh-rsa secret-key-material";
        CommandAuditRecord record = new CommandAuditRecord(
                "alice",
                "request",
                "session",
                "source",
                "/auth/key",
                "add",
                Map.of("key", "<redacted>"),
                "MESSAGE",
                "SUCCESS",
                1,
                Map.of("transport", "ssh"));

        assertThat(Slf4jCommandAuditSink.format(record))
                .contains("key=<redacted>")
                .doesNotContain(pastedKey, "secret-key-material");
    }
}
