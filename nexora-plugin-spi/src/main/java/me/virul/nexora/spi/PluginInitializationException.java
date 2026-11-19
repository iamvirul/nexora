package me.virul.nexora.spi;

/**
 * Thrown by {@link NexoraPlugin#initialize(PluginContext)} when a plugin cannot start. The plugin is
 * not activated.
 */
public class PluginInitializationException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message why the plugin could not start
     */
    public PluginInitializationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the error that caused it.
     *
     * @param message why the plugin could not start
     * @param cause   the underlying error
     */
    public PluginInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
