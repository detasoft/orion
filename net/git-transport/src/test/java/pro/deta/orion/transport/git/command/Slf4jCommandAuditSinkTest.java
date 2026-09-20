package pro.deta.orion.transport.git.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.command.audit.CommandAuditRecord;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Slf4jCommandAuditSinkTest {
    @Test
    void ordinaryAuditKeepsItsExistingFormat() {
        CommandAuditRecord record = new CommandAuditRecord(
                "alice", "request", "session", "source", "/auth/key", "rm",
                Map.of("fingerprint", "SHA256:abc"), "MESSAGE", "SUCCESS", 42,
                Map.of("transport", "ssh"));

        assertThat(Slf4jCommandAuditSink.format(record)).isEqualTo(
                "Orion command audit user=alice request=request session=session source=source"
                        + " path=/auth/key action=rm parameters={fingerprint=SHA256:abc}"
                        + " result=MESSAGE/SUCCESS durationNanos=42 metadata={transport=ssh}");
    }

    @Test
    void escapesControlsInEveryFieldAndMapEntry() {
        String untrusted = "value\n\r\t\u001B\u0000\u007F\u0085\\n";
        String escaped = "value\\n\\r\\t\\u001B\\u0000\\u007F\\u0085\\\\n";
        CommandAuditRecord record = new CommandAuditRecord(
                untrusted, untrusted, untrusted, untrusted, untrusted, untrusted,
                Map.of(untrusted, untrusted), untrusted, untrusted, 1,
                Map.of(untrusted, untrusted));

        assertThat(Slf4jCommandAuditSink.format(record)).isEqualTo(
                "Orion command audit user=" + escaped
                        + " request=" + escaped + " session=" + escaped + " source=" + escaped
                        + " path=" + escaped + " action=" + escaped
                        + " parameters={" + escaped + "=" + escaped + "}"
                        + " result=" + escaped + "/" + escaped + " durationNanos=1"
                        + " metadata={" + escaped + "=" + escaped + "}");
    }

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
