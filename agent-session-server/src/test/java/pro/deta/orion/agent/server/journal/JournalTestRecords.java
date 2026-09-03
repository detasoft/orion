package pro.deta.orion.agent.server.journal;

import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class JournalTestRecords {
    private static final SessionEventCodec CODEC = new SessionEventCodec(AgentProtocolLimits.defaults());

    private JournalTestRecords() {
    }

    static SessionEventRecord event(long id) throws AgentProtocolException {
        byte[] encoded = CODEC.encode(
                new EventId(id),
                new SessionEventPayload.PtyOutput(ProtocolBytes.copyOf(new byte[]{(byte) id})));
        return CODEC.decode(encoded);
    }

    static SessionEventRecord opaqueEvent(long id) throws AgentProtocolException {
        byte[] encoded = CODEC.encodeOpaque(
                new EventId(id),
                0x7ffe,
                ProtocolBytes.copyOf(new byte[]{0x42, (byte) 0xde, (byte) 0xad}),
                List.of(ProtocolBytes.copyOf(new byte[]{0x66, 'f', 'u', 't', 'u', 'r', 'e'})));
        return CODEC.decode(encoded);
    }

    static void writeSegment(Path root, String sessionId, int number, SessionEventRecord... records)
            throws IOException {
        Path sessionDirectory = Files.createDirectories(root.resolve(sessionId));
        Files.write(sessionDirectory.resolve("%08d.cbor".formatted(number)), encoded(records));
    }

    static void writeCompressedSegment(
            Path root,
            String sessionId,
            int number,
            SessionEventRecord... records) throws IOException {
        Path sessionDirectory = Files.createDirectories(root.resolve(sessionId));
        try (OutputStream output = new ZstdCompressorOutputStream(Files.newOutputStream(
                sessionDirectory.resolve("%08d.cbor.zst".formatted(number))))) {
            output.write(encoded(records));
        }
    }

    static byte[] encoded(SessionEventRecord... records) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (SessionEventRecord record : records) {
            output.writeBytes(record.encodedRecord().toByteArray());
        }
        return output.toByteArray();
    }
}
