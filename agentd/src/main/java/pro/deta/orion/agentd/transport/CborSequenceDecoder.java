package pro.deta.orion.agentd.transport;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded incremental CBOR Sequence splitter that preserves each encoded item unchanged. */
final class CborSequenceDecoder {
    private final int maximumBytes;
    private final int maximumDepth;
    private final ArrayDeque<Container> containers = new ArrayDeque<>();
    private byte[] pending = new byte[64];
    private int size;
    private int scan;

    CborSequenceDecoder(int maximumBytes) {
        this(maximumBytes, 64);
    }

    CborSequenceDecoder(int maximumBytes, int maximumDepth) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        if (maximumDepth < 1) {
            throw new IllegalArgumentException("maximumDepth must be positive");
        }
        this.maximumBytes = maximumBytes;
        this.maximumDepth = maximumDepth;
        pending = new byte[Math.min(pending.length, maximumBytes)];
    }

    synchronized List<byte[]> accept(ByteBuffer input) {
        ByteBuffer source = input.slice();
        List<byte[]> result = new ArrayList<>();
        while (source.hasRemaining()) {
            if (size == maximumBytes) {
                throw new IllegalArgumentException("CBOR item exceeds bound");
            }
            int copied = Math.min(source.remaining(), maximumBytes - size);
            ensureCapacity(size + copied);
            source.get(pending, size, copied);
            size += copied;
            parseAvailable(result);
        }
        return result;
    }

    synchronized void finish() {
        if (size != 0) {
            throw new IllegalArgumentException("truncated CBOR item");
        }
    }

    synchronized void reset() {
        size = 0;
        scan = 0;
        containers.clear();
    }

    private void parseAvailable(List<byte[]> result) {
        while (scan < size) {
            int start = scan;
            int first = pending[start] & 0xff;
            int major = first >>> 5;
            int additional = first & 0x1f;
            if (additional == 31) {
                indefinite(major, result);
                continue;
            }
            if (additional >= 28) {
                throw new IllegalArgumentException("reserved CBOR additional information");
            }
            int width = additional < 24 ? 0 : 1 << (additional - 24);
            if (size - start < 1 + width) {
                return;
            }
            long value = additional < 24 ? additional : unsigned(start + 1, width);
            if (value < 0 && major >= 2 && major <= 5) {
                throw new IllegalArgumentException("CBOR length exceeds bound");
            }
            int content = start + 1 + width;
            requireValidStringChunk(major);
            if (major == 2 || major == 3) {
                requireFits(content, value, "CBOR item exceeds bound");
                if (value > size - content) {
                    return;
                }
                scan = content + (int) value;
                completeValue(result);
            } else if (major == 4 || major == 5) {
                long count = value;
                if (major == 5) {
                    if (count > Long.MAX_VALUE / 2) {
                        throw new IllegalArgumentException("CBOR map length exceeds bound");
                    }
                    count *= 2;
                }
                requireFits(content, count, "CBOR collection exceeds bound");
                scan = content;
                if (count == 0) {
                    completeValue(result);
                } else {
                    push(Container.definite(count));
                }
            } else if (major == 6) {
                requireFits(content, 1, "CBOR tag exceeds bound");
                scan = content;
                push(Container.definite(1));
            } else {
                scan = content;
                completeValue(result);
            }
        }
    }

    private void completeValue(List<byte[]> result) {
        while (!containers.isEmpty()) {
            Container container = containers.peekLast();
            if (container.indefinite()) {
                container.childCompleted();
                return;
            }
            container.childCompleted();
            if (container.remaining != 0) {
                return;
            }
            containers.removeLast();
        }
        result.add(Arrays.copyOf(pending, scan));
        int leftover = size - scan;
        System.arraycopy(pending, scan, pending, 0, leftover);
        size = leftover;
        scan = 0;
    }

    private void indefinite(int major, List<byte[]> result) {
        if (major == 7) {
            closeIndefinite(result);
            return;
        }
        if (major < 2 || major > 5) {
            throw new IllegalArgumentException("invalid indefinite CBOR item");
        }
        if (!containers.isEmpty()) {
            int parentMajor = containers.peekLast().major;
            if (parentMajor == 2 || parentMajor == 3) {
                throw new IllegalArgumentException("indefinite CBOR string chunks must be definite");
            }
        }
        requireValidStringChunk(major);
        scan++;
        requireFits(scan, 1, "CBOR indefinite item exceeds bound");
        push(Container.indefinite(major));
    }

    private void closeIndefinite(List<byte[]> result) {
        if (containers.isEmpty() || !containers.peekLast().indefinite()) {
            throw new IllegalArgumentException("unmatched CBOR break");
        }
        Container container = containers.removeLast();
        if (container.major == 5 && (container.children & 1) != 0) {
            throw new IllegalArgumentException("indefinite CBOR map has an unmatched key");
        }
        scan++;
        completeValue(result);
    }

    private void requireValidStringChunk(int major) {
        if (containers.isEmpty()) {
            return;
        }
        Container container = containers.peekLast();
        if ((container.major == 2 || container.major == 3) && container.major != major) {
            throw new IllegalArgumentException("invalid chunk in indefinite CBOR string");
        }
    }

    private void push(Container container) {
        if (containers.size() == maximumDepth) {
            throw new IllegalArgumentException("CBOR nesting exceeds bound");
        }
        containers.addLast(container);
    }

    private long unsigned(int offset, int width) {
        long value = 0;
        for (int i = 0; i < width; i++) {
            value = value << 8 | (pending[offset + i] & 0xffL);
        }
        return value;
    }

    private void requireFits(int encodedBytes, long minimumRemaining, String message) {
        if (minimumRemaining > maximumBytes - encodedBytes) {
            throw new IllegalArgumentException(message);
        }
    }

    private void ensureCapacity(int wanted) {
        if (pending.length >= wanted) {
            return;
        }
        int capacity = Math.min(maximumBytes, Math.max(wanted, pending.length * 2));
        pending = Arrays.copyOf(pending, capacity);
    }

    private static final class Container {
        private final int major;
        private long remaining;
        private long children;

        private Container(int major, long remaining) {
            this.major = major;
            this.remaining = remaining;
        }

        private static Container definite(long remaining) {
            return new Container(-1, remaining);
        }

        private static Container indefinite(int major) {
            return new Container(major, -1);
        }

        private boolean indefinite() {
            return remaining < 0;
        }

        private void childCompleted() {
            if (indefinite()) {
                children++;
            } else {
                remaining--;
            }
        }
    }
}
