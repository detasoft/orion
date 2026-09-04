package pro.deta.orion.transport.git.command.read;

@FunctionalInterface
public interface RuntimeMetrics {
    OperatorDomainViews.SystemResourceView resources();
}
