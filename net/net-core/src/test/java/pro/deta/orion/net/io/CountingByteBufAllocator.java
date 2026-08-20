package pro.deta.orion.net.io;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class CountingByteBufAllocator extends AbstractByteBufAllocator {
    private int allocations;

    CountingByteBufAllocator() {
        super(false);
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        allocations++;
        return Unpooled.buffer(initialCapacity, maxCapacity);
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        allocations++;
        return Unpooled.directBuffer(initialCapacity, maxCapacity);
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    int allocations() {
        return allocations;
    }
}
