package pro.deta.orion.comm.common;

import java.util.concurrent.ConcurrentLinkedQueue;

public class OrionDataQueue<T> {
    private final ConcurrentLinkedQueue<DataPacket<T>> queue = new ConcurrentLinkedQueue<>();

    public void add(DataPacket<T> packet) {
        queue.add(packet);
    }

    public DataPacket<T> poll() {
        return queue.poll();
    }

    public boolean hasData() {
        return !queue.isEmpty();
    }
}
