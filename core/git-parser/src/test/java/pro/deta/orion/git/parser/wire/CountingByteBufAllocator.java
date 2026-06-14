package pro.deta.orion.git.parser.wire;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class CountingByteBufAllocator extends AbstractByteBufAllocator {
    private int allocations;
    private int lastInitialCapacity;
    private int lastMaxCapacity;

    CountingByteBufAllocator() {
        super(false);
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        recordAllocation(initialCapacity, maxCapacity);
        return Unpooled.buffer(initialCapacity, maxCapacity);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        recordAllocation(initialCapacity, maxCapacity);
        return Unpooled.directBuffer(initialCapacity, maxCapacity);
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    int allocations() {
        return allocations;
    }

    int lastInitialCapacity() {
        return lastInitialCapacity;
    }

    int lastMaxCapacity() {
        return lastMaxCapacity;
    }

    private void recordAllocation(int initialCapacity, int maxCapacity) {
        allocations++;
        lastInitialCapacity = initialCapacity;
        lastMaxCapacity = maxCapacity;
    }
}
