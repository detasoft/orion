package pro.deta.orion.keymaterial.bootstrap;

/**
 * Decrypts and validates a complete input pair before any runtime state becomes visible.
 */
@FunctionalInterface
public interface MaterialBootstrapCandidateFactory<M, C, R> {
    MaterialBootstrapPreparation<R> prepare(
            MaterialBootstrapInput<M> material,
            MaterialBootstrapInput<C> configuration);
}
