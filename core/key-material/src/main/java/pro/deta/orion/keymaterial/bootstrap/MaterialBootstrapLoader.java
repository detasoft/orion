package pro.deta.orion.keymaterial.bootstrap;

import java.util.concurrent.CompletionStage;

/**
 * Starts an asynchronous load without depending on the other bootstrap input.
 */
@FunctionalInterface
public interface MaterialBootstrapLoader<T> {
    CompletionStage<MaterialBootstrapLoadResult<T>> load();
}
