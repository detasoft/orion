package pro.deta.orion.transport.git.command.read;

import jakarta.inject.Inject;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

public final class DefaultRuntimeMetrics implements RuntimeMetrics {
    @Inject
    public DefaultRuntimeMetrics() {
    }

    @Override
    public OperatorDomainViews.SystemResourceView resources() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new OperatorDomainViews.SystemResourceView(
                Runtime.getRuntime().availableProcessors(),
                heap.getUsed(),
                heap.getCommitted(),
                Math.max(0, heap.getMax()));
    }
}
