package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

final class SessionRecordCodec {
    static final int MAX_RECORD_BYTES = 1_048_576;
    private static final int MAGIC = 0x4f525345;
    private static final int VERSION = 1;
    private static final int MAX_TEXT_BYTES = 262_144;

    static String fileName(SessionId sessionId) {
        try {
            byte[] value = sessionId.value().getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest) + ".session";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    byte[] encode(SessionRecord record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeText(output, record.agentId().value());
            writeDescriptor(output, record.reported());
            output.writeBoolean(record.outcome().isPresent());
            if (record.outcome().isPresent()) {
                SessionRecord.Outcome outcome = record.outcome().orElseThrow();
                output.writeInt(outcome.state().wireCode());
                writeText(output, outcome.detail());
            }
        }
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_RECORD_BYTES) {
            throw new IOException("Encoded session record exceeds the size limit");
        }
        return encoded;
    }

    SessionRecord decode(byte[] bytes) throws SessionRegistryFileOperations.StoredRecordException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw corrupt("Session record has an invalid header", null);
            }
            if (input.readInt() != VERSION) {
                throw corrupt("Session record has an unsupported version", null);
            }
            AgentId agentId = new AgentId(readText(input));
            SessionDescriptor reported = readDescriptor(input);
            Optional<SessionRecord.Outcome> outcome = input.readBoolean()
                    ? Optional.of(new SessionRecord.Outcome(readState(input), readText(input)))
                    : Optional.empty();
            if (input.available() != 0) {
                throw corrupt("Session record has trailing bytes", null);
            }
            return new SessionRecord(agentId, reported, outcome);
        } catch (SessionRegistryFileOperations.StoredRecordException failure) {
            throw failure;
        } catch (EOFException failure) {
            throw corrupt("Session record is truncated", failure);
        } catch (IOException | IllegalArgumentException failure) {
            throw corrupt("Session record contains invalid fields", failure);
        }
    }

    private void writeDescriptor(DataOutputStream output, SessionDescriptor descriptor)
            throws IOException {
        writeText(output, descriptor.sessionId().value());
        output.writeInt(descriptor.state().wireCode());
        output.writeBoolean(descriptor.firstAvailableEventId().isPresent());
        if (descriptor.firstAvailableEventId().isPresent()) {
            output.writeLong(descriptor.firstAvailableEventId().orElseThrow().value());
            output.writeLong(descriptor.lastAvailableEventId().orElseThrow().value());
        }
        writeText(output, descriptor.detail());
    }

    private SessionDescriptor readDescriptor(DataInputStream input) throws IOException {
        SessionId sessionId = new SessionId(readText(input));
        AgentMessage.SessionState state = readState(input);
        Optional<EventId> first = Optional.empty();
        Optional<EventId> last = Optional.empty();
        if (input.readBoolean()) {
            first = Optional.of(new EventId(input.readLong()));
            last = Optional.of(new EventId(input.readLong()));
        }
        return new SessionDescriptor(sessionId, state, first, last, readText(input));
    }

    private AgentMessage.SessionState readState(DataInputStream input) throws IOException {
        AgentMessage.SessionState state = AgentMessage.SessionState.fromWireCode(input.readInt());
        if (state == null) {
            throw corrupt("Session record contains an unknown state", null);
        }
        return state;
    }

    private void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_TEXT_BYTES) {
            throw new IOException("Session record text exceeds the size limit");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES) {
            throw corrupt("Session record text length is invalid", null);
        }
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new EOFException("Session record text is truncated");
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw corrupt("Session record text is not valid UTF-8", failure);
        }
    }

    private SessionRegistryFileOperations.StoredRecordException corrupt(
            String message,
            Throwable cause) {
        return new SessionRegistryFileOperations.StoredRecordException(message, cause);
    }
}
