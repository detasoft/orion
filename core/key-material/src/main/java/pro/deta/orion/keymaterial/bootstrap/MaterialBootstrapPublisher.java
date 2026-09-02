package pro.deta.orion.keymaterial.bootstrap;

/**
 * Publishes a candidate atomically: a failure must leave the previously active runtime snapshot unchanged.
 */
@FunctionalInterface
public interface MaterialBootstrapPublisher<T> {
    MaterialBootstrapPublication publish(MaterialBootstrapCandidate<T> candidate);
}
