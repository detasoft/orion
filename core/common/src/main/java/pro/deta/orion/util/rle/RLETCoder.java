package pro.deta.orion.util.rle;

import lombok.Data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class RLETCoder {
    public static final Encoder ENCODER_SHORT = new Encoder(2);
    public static final Encoder ENCODER_INT = new Encoder(4);

    public static final class Encoder {
        private final int paddingSize;
        private final long maxValue;

        private Encoder(int paddingSize) {
            this.paddingSize = paddingSize;
            switch (paddingSize) {
                case 2 -> this.maxValue = Short.MAX_VALUE * 2 + 1;
                case 4 -> this.maxValue = Integer.MAX_VALUE * 2L + 1;
                default -> throw new IllegalArgumentException("Padding size not in (2,4): " + paddingSize);
            }
        }

        private void serialize(byte[] content, ByteBuffer bb) {
            int length = content.length;
            if (length > maxValue)
                throw new IllegalArgumentException("Content size more than allowed: " + length);
            if (paddingSize == 2)
                bb.putShort((short) (length & 0xFFFF));
            else if (paddingSize == 4)
                bb.putInt((int) (length & 0xFFFFFFFFl));
            bb.put(content);
        }
        
        private long readLength(ByteBuffer bb) {
            return switch (paddingSize) {
                case 2 -> Short.toUnsignedInt(bb.getShort());
                case 4 -> Integer.toUnsignedLong(bb.getInt());
                default -> throw new IllegalStateException("Unexpected value: " + paddingSize);
            };
        }

        private byte[] deserializeOnePart(ByteBuffer bb) {
            long length = readLength(bb);
            if ((int) length != length) {
                throw new UnsupportedOperationException("Not yet supported size more than integer.");
            }
            byte[] content = new byte[(int) length];
            bb.get(content);
            return content;
        }

        public byte[][] decodeRLET(ByteBuffer bb) {
            List<byte[]> result = new ArrayList<>();
            while (bb.hasRemaining()) {
                result.add(deserializeOnePart(bb));
            }
            return result.toArray(new byte[0][]);
        }

        public byte[][] decodeRLET(byte[] bb) {
            return decodeRLET(ByteBuffer.wrap(bb));
        }

        public byte[] encodeRLET(byte[]... parts) {
            int size = internalCalculateSize(parts);
            ByteBuffer bb = ByteBuffer.allocate(size);
            for (byte[] p: parts) {
                serialize(p, bb);
            }
            return bb.array();
        }

        private int internalCalculateSize(byte[][] parts) {
            int size = 0;
            for (byte[] p: parts) {
                size += p.length + paddingSize;
            }
            return size;
        }
    }
}


