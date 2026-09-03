package pro.deta.orion.agent.protocol;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class CborSequenceParser<T> {
    private static final int INITIAL_BUFFER_BYTES = 8 * 1024;

    private final AgentProtocolLimits limits;
    private final ItemDecoder<T> itemDecoder;
    private final CborItemScanner scanner;
    private byte[] buffer;
    private int start;
    private int end;
    private boolean poisoned;

    CborSequenceParser(AgentProtocolLimits limits, ItemDecoder<T> itemDecoder) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.itemDecoder = Objects.requireNonNull(itemDecoder, "itemDecoder");
        scanner = new CborItemScanner(limits);
        buffer = new byte[Math.min(INITIAL_BUFFER_BYTES, limits.maxMessageBytes())];
    }

    SequenceDecodeResult<T> accept(ByteBuffer input) {
        Objects.requireNonNull(input, "input");
        requireUsable();
        List<SequenceDecodeResult.Outcome<T>> outcomes = new ArrayList<>();
        while (input.hasRemaining()) {
            if (end == buffer.length) {
                compact();
                if (end == buffer.length && !grow()) {
                    return terminal(input, outcomes, limitFailure());
                }
            }
            int copied = Math.min(input.remaining(), buffer.length - end);
            input.get(buffer, end, copied);
            end += copied;
            SequenceDecodeIssue.Terminal terminal = decodeAvailable(outcomes);
            if (terminal != null) {
                input.position(input.limit());
                return new SequenceDecodeResult<>(outcomes, Optional.of(terminal));
            }
        }
        compact();
        return new SequenceDecodeResult<>(outcomes, Optional.empty());
    }

    SequenceDecodeResult<T> finish() {
        requireUsable();
        if (pendingBytes() == 0) {
            return new SequenceDecodeResult<>(List.of(), Optional.empty());
        }
        AgentProtocolException exception = new AgentProtocolException(
                AgentProtocolException.Reason.MALFORMED_CBOR,
                "CBOR sequence ends with an incomplete item");
        poisoned = true;
        return new SequenceDecodeResult<>(
                List.of(),
                Optional.of(new SequenceDecodeIssue.Terminal(exception, pendingBytes())));
    }

    void reset() {
        start = 0;
        end = 0;
        poisoned = false;
        scanner.reset(0);
    }

    int pendingBytes() {
        return end - start;
    }

    private SequenceDecodeIssue.Terminal decodeAvailable(List<SequenceDecodeResult.Outcome<T>> outcomes) {
        while (start < end) {
            final int itemEnd;
            try {
                itemEnd = scanner.scan(buffer, end);
            } catch (AgentProtocolException exception) {
                poisoned = true;
                return new SequenceDecodeIssue.Terminal(exception, pendingBytes());
            }
            if (itemEnd < 0) {
                if (pendingBytes() == limits.maxMessageBytes()) {
                    poisoned = true;
                    return new SequenceDecodeIssue.Terminal(limitFailure(), pendingBytes());
                }
                return null;
            }
            try {
                outcomes.add(new SequenceDecodeResult.Decoded<>(itemDecoder.decode(buffer, start, itemEnd)));
            } catch (AgentProtocolException exception) {
                outcomes.add(new SequenceDecodeResult.Rejected<>(
                        new SequenceDecodeIssue.Recoverable(exception, itemEnd - start)));
            }
            start = itemEnd;
            scanner.reset(start);
        }
        return null;
    }

    private SequenceDecodeResult<T> terminal(
            ByteBuffer input,
            List<SequenceDecodeResult.Outcome<T>> outcomes,
            AgentProtocolException exception
    ) {
        poisoned = true;
        input.position(input.limit());
        return new SequenceDecodeResult<>(
                outcomes,
                Optional.of(new SequenceDecodeIssue.Terminal(exception, pendingBytes())));
    }

    private void compact() {
        if (start == 0) {
            return;
        }
        int pending = pendingBytes();
        System.arraycopy(buffer, start, buffer, 0, pending);
        scanner.shift(start);
        start = 0;
        end = pending;
    }

    private boolean grow() {
        if (buffer.length == limits.maxMessageBytes()) {
            return false;
        }
        int newLength = Math.min(limits.maxMessageBytes(), Math.max(buffer.length + 1, buffer.length * 2));
        buffer = Arrays.copyOf(buffer, newLength);
        return true;
    }

    private AgentProtocolException limitFailure() {
        return new AgentProtocolException(
                AgentProtocolException.Reason.LIMIT_EXCEEDED,
                "Incomplete CBOR item reached configured limit");
    }

    private void requireUsable() {
        if (poisoned) {
            throw new IllegalStateException("CBOR sequence decoder must be reset after a terminal failure");
        }
    }

    @FunctionalInterface
    interface ItemDecoder<T> {
        T decode(byte[] bytes, int from, int to) throws AgentProtocolException;
    }
}
