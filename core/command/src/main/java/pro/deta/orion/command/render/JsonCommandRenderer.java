package pro.deta.orion.command.render;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import pro.deta.orion.command.CommandResult;
import pro.deta.orion.command.CommandValue;
import pro.deta.orion.command.RowPage;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Objects;

public final class JsonCommandRenderer {
    private static final JsonFactory JSON = new JsonFactory();
    private static final CharacterEscapes TERMINAL_SAFE_ESCAPES = new TerminalSafeCharacterEscapes();

    public RenderedCommand render(CommandResult.Rows rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.page().isEmpty()) {
            return failed("JSON rows require pagination metadata");
        }
        try {
            StringWriter output = new StringWriter();
            try (JsonGenerator generator = JSON.createGenerator(output)) {
                generator.setCharacterEscapes(TERMINAL_SAFE_ESCAPES);
                write(generator, rows, rows.page().orElseThrow());
            }
            return new RenderedCommand(output + "\n", "", 0);
        } catch (IOException exception) {
            return failed("JSON result serialization failed");
        }
    }

    private static void write(JsonGenerator generator, CommandResult.Rows rows, RowPage page)
            throws IOException {
        generator.writeStartObject();
        generator.writeArrayFieldStart("columns");
        for (var column : rows.columns()) {
            generator.writeString(column.name());
        }
        generator.writeEndArray();
        generator.writeArrayFieldStart("rows");
        for (var row : rows.values()) {
            generator.writeStartObject();
            for (int index = 0; index < rows.columns().size(); index++) {
                generator.writeFieldName(rows.columns().get(index).name());
                writeValue(generator, row.get(index));
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeObjectFieldStart("page");
        generator.writeNumberField("number", page.number());
        generator.writeNumberField("size", page.size());
        generator.writeNumberField("matched", page.matched());
        if (page.next().isPresent()) {
            generator.writeNumberField("next", page.next().getAsInt());
        } else {
            generator.writeNullField("next");
        }
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private static void writeValue(JsonGenerator generator, CommandValue value) throws IOException {
        switch (value) {
            case CommandValue.Text text -> generator.writeString(text.value());
            case CommandValue.Numeric numeric -> generator.writeNumber(numeric.value());
            case CommandValue.BooleanValue booleanValue -> generator.writeBoolean(booleanValue.value());
            case CommandValue.NullValue ignored -> generator.writeNull();
        }
    }

    private static RenderedCommand failed(String message) {
        return new RenderedCommand("", "HANDLER_FAILED: " + message + "\n", 1);
    }

    private static final class TerminalSafeCharacterEscapes extends CharacterEscapes {
        private final int[] asciiEscapes = CharacterEscapes.standardAsciiEscapesForJSON();

        @Override
        public int[] getEscapeCodesForAscii() {
            return asciiEscapes;
        }

        @Override
        public SerializableString getEscapeSequence(int character) {
            if (character >= 0x7f && character <= 0x9f) {
                return new SerializedString(String.format("\\u%04X", character));
            }
            return null;
        }
    }
}
