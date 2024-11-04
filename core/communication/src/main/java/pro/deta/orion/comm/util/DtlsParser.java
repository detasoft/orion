package pro.deta.orion.comm.util;

import io.netty.buffer.ByteBuf;
import lombok.ToString;

public final class DtlsParser {

    public static final int DTLS_RECORD_HEADER_LEN = 13;
    public static final int DTLS_HANDSHAKE_HEADER_LEN = 12;

    @ToString
    public static class DtlsRecord {
        public final short contentType; // 0..255
        public final int version;       // 2 bytes
        public final int epoch;         // 2 bytes
        public final long sequenceNumber; // 6 bytes
        public final int length;        // 2 bytes
        private final HandshakeMessage handshakeMessage;

        DtlsRecord(short contentType, int version, int epoch, long sequenceNumber, int length, ByteBuf payload) {
            this.contentType = contentType;
            this.version = version;
            this.epoch = epoch;
            this.sequenceNumber = sequenceNumber;
            this.length = length;
            if (isHandshake())
                handshakeMessage = parseHandshake(payload);
            else handshakeMessage = null;
        }

        public boolean isPartial() {
            return (epoch & 0x8000) != 0;
        }

        public boolean isHandshake() {
            return contentType == 22;
        }
    }

    /**
     * @param msgType    1 byte
     * @param msgLength  3 bytes (total message length)
     * @param msgSeq     2 bytes
     * @param fragOffset 3 bytes
     * @param fragLength 3 bytes
     */
    public record HandshakeMessage(short msgType, int msgLength, int msgSeq, int fragOffset, int fragLength) { }

    /**
     * Parses single DTLS record из ByteBuf (from the current rx).
     * Returns DtlsRecord and moves readerIndex to the end of record.
     * Uses slice() for payload — no copy.
     */
    public static DtlsRecord parseRecord(ByteBuf buf) {
        if (buf.readableBytes() < DTLS_RECORD_HEADER_LEN) {
            throw new IllegalArgumentException("Not enough bytes for DTLS record header");
        }

        int start = buf.readerIndex();

        short contentType = buf.getUnsignedByte(start);           // 1 byte
        int version = buf.getUnsignedShort(start + 1);            // 2 bytes
        int epoch = buf.getUnsignedShort(start + 3);              // 2 bytes

        // sequenceNumber: 6 bytes at offsets start+5 .. start+10
        // read as (2 bytes << 32) | (4 bytes)
        long seqHigh = buf.getUnsignedShort(start + 5);           // 2 bytes
        long seqLow = buf.getUnsignedInt(start + 7);              // 4 bytes
        long sequenceNumber = (seqHigh << 32) | (seqLow & 0xFFFFFFFFL);

        int length = buf.getUnsignedShort(start + 11);            // 2 bytes

        if (buf.readableBytes() < DTLS_RECORD_HEADER_LEN + length) {
            throw new IllegalArgumentException("Not enough bytes for DTLS record payload");
        }

        // payload as view:
        ByteBuf payloadView = buf.slice(start + DTLS_RECORD_HEADER_LEN, length);

        // readerIndex moves forward
        buf.readerIndex(start);

        return new DtlsRecord(contentType, version, epoch, sequenceNumber, length, payloadView);
    }

    /**
     * Разбирает handshake сообщение из ByteBuf (payload view) — не меняет readerIndex у переданного буфера.
     * Возвращает HandshakeMessage (body — view на фрагмент).
     */
    public static HandshakeMessage parseHandshake(ByteBuf payload) {
        if (payload.readableBytes() < DTLS_HANDSHAKE_HEADER_LEN) {
            return null;
        }

        int start = payload.readerIndex();

        short msgType = payload.getUnsignedByte(start);            // 1
        // 3-byte length (big-endian)
        int msgLength = payload.getUnsignedMedium(start + 1);     // 3 bytes
        int msgSeq = payload.getUnsignedShort(start + 4);         // 2 bytes
        int fragOffset = payload.getUnsignedMedium(start + 6);    // 3 bytes
        int fragLength = payload.getUnsignedMedium(start + 9);    // 3 bytes


        return new HandshakeMessage(msgType, msgLength, msgSeq, fragOffset, fragLength);
    }
}
