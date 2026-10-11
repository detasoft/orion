package pro.deta.orion.config;

/**
 * The configuration was saved, but its synchronous activation failed.
 */
public final class ActivationFailedException extends IllegalStateException {
    public ActivationFailedException(RuntimeException cause) {
        super("Configuration was saved but could not be activated", cause);
    }
}
