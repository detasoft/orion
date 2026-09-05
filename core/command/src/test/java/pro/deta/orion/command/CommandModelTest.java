package pro.deta.orion.command;

import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.schema.acl.AccessControl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandModelTest {
    @Test
    void preservesRequiredImmutableRequestContext() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("client", "ssh");
        CommandContext context = new CommandContext(
                securityContext(),
                "request-1",
                "session-1",
                "192.0.2.5:2200",
                CommandPath.root(),
                CommandPresentation.plain(),
                CommandCancellation.never(),
                metadata);
        metadata.put("later", "ignored");

        CommandRequest request = new CommandRequest("/repository ls", context);

        assertThat(request.commandLine()).isEqualTo("/repository ls");
        assertThat(request.context().auditMetadata()).containsExactly(Map.entry("client", "ssh"));
        assertThatThrownBy(() -> request.context().auditMetadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingRequiredContextFields() {
        assertThatThrownBy(() -> new CommandContext(
                null,
                "request-1",
                "session-1",
                "source",
                CommandPath.root(),
                CommandPresentation.plain(),
                CommandCancellation.never(),
                Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("securityContext");
        assertThatThrownBy(() -> new CommandRequest(null, validContext()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("commandLine");
    }

    @Test
    void representsRootAbsoluteAndRelativePaths() {
        assertThat(CommandPath.root().toString()).isEqualTo("/");
        assertThat(CommandPath.absolute(List.of("organization", "acme")).toString())
                .isEqualTo("/organization/acme");
        assertThat(CommandPath.relative(List.of("repository", "demo")).toString())
                .isEqualTo("repository/demo");
        assertThatThrownBy(() -> CommandPath.absolute(List.of("repository", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> CommandPath.absolute(List.of(".")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized");
        assertThatThrownBy(() -> CommandPath.relative(List.of("..")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized");
    }

    @Test
    void defensivelyCopiesFiniteResultPayloadsAndPreservesObjectOrder() {
        List<CommandColumn> columns = new ArrayList<>(List.of(CommandColumn.text("name")));
        List<List<CommandValue>> values = new ArrayList<>();
        values.add(new ArrayList<>(List.of(CommandValue.text("alpha"))));
        Map<String, CommandValue> fields = new LinkedHashMap<>();
        fields.put("second", CommandValue.number(2));
        fields.put("first", CommandValue.text("1"));

        CommandResult.Rows rows = CommandResult.Rows.unqueried(columns, values);
        CommandResult.ObjectValue object = new CommandResult.ObjectValue(fields);
        columns.add(CommandColumn.text("ignored"));
        values.getFirst().add(CommandValue.text("ignored"));
        fields.put("ignored", CommandValue.text("ignored"));

        assertThat(rows.columns()).containsExactly(CommandColumn.text("name"));
        assertThat(rows.values()).containsExactly(List.of(CommandValue.text("alpha")));
        assertThatThrownBy(() -> rows.values().getFirst().add(CommandValue.text("no")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(object.fields()).containsExactly(
                Map.entry("second", CommandValue.number(2)),
                Map.entry("first", CommandValue.text("1")));
    }

    @Test
    void preservesTypedValuesAndPaginationMetadata() {
        CommandResult.Rows rows = new CommandResult.Rows(
                List.of(
                        CommandColumn.text("name"),
                        CommandColumn.number("size"),
                        CommandColumn.bool("active"),
                        CommandColumn.text("note")),
                List.of(List.of(
                        CommandValue.text("alpha"),
                        CommandValue.number(new BigDecimal("12.50")),
                        CommandValue.bool(true),
                        CommandValue.nullValue())),
                RowOutputFormat.JSON,
                Optional.of(new RowPage(2, 25, 31, OptionalInt.empty(), true)));

        assertThat(rows.values().getFirst()).containsExactly(
                new CommandValue.Text("alpha"),
                new CommandValue.Numeric(new BigDecimal("12.50")),
                new CommandValue.BooleanValue(true),
                CommandValue.nullValue());
        assertThat(rows.page()).contains(new RowPage(2, 25, 31, OptionalInt.empty(), true));
    }

    @Test
    void rejectsInvalidTypedRowShapesAndPageBounds() {
        assertThatThrownBy(() -> CommandResult.Rows.unqueried(
                List.of(CommandColumn.number("count")),
                List.of(List.of(CommandValue.text("wrong")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("count");
        assertThatThrownBy(() -> CommandResult.Rows.unqueried(
                List.of(CommandColumn.text("name")),
                List.of(List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column");
        assertThatThrownBy(() -> CommandResult.Rows.unqueried(
                List.of(CommandColumn.text("name"), CommandColumn.number("name")),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new RowPage(0, 10, 0, OptionalInt.empty(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
    }

    @Test
    void exposesAllDocumentedResultVariantsWithTypedReservations() {
        CommandResult.StreamHandle streamHandle = new CommandResult.StreamHandle() {};
        CommandResult.AttachmentHandle attachmentHandle = new CommandResult.AttachmentHandle() {};

        assertThat(new CommandResult.Message("ok")).isInstanceOf(CommandResult.class);
        assertThat(CommandResult.Rows.unqueried(
                List.of(CommandColumn.text("name")),
                List.of(List.of(CommandValue.text("repo")))))
                .isInstanceOf(CommandResult.class);
        assertThat(new CommandResult.ObjectValue(Map.of("state", CommandValue.text("running"))))
                .isInstanceOf(CommandResult.class);
        assertThat(new CommandResult.Stream(streamHandle).handle()).isSameAs(streamHandle);
        assertThat(new CommandResult.Attachment(attachmentHandle).handle()).isSameAs(attachmentHandle);
        assertThat(new CommandResult.Exit(7, "stopped").exitCode()).isEqualTo(7);
        assertThat(new CommandResult.Failure(
                CommandFailureCode.AMBIGUOUS_RESOURCE,
                "ambiguous",
                List.of("abc", "abd")).candidates()).containsExactly("abc", "abd");
    }

    private static CommandContext validContext() {
        return new CommandContext(
                securityContext(),
                "request-1",
                "session-1",
                "source",
                CommandPath.root(),
                CommandPresentation.plain(),
                CommandCancellation.never(),
                Map.of());
    }

    private static SecurityContext securityContext() {
        UserIdentity identity = new UserIdentity() {
            @Override
            public String getUserId() {
                return "operator";
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public List<AccessControl.Grant> getGrants() {
                return List.of();
            }
        };
        return SecurityContext.createContext().withUserIdentity(identity).withRequestId("request-1");
    }
}
