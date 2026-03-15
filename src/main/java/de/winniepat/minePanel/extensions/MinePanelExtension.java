package de.winniepat.minePanel.extensions;

import java.util.List;

/**
 * Service Provider Interface (SPI) for MinePanel extensions.
 * <p>
 * Implementations are discovered through Java {@code ServiceLoader} and loaded by the
 * extension manager during MinePanel startup.
 * </p>
 * <p>
 * Typical lifecycle:
 * </p>
 * <ol>
 *     <li>{@link #onLoad(ExtensionContext)}</li>
 *     <li>{@link #onEnable()}</li>
 *     <li>{@link #registerWebRoutes(ExtensionWebRegistry)}</li>
 *     <li>{@link #navigationTabs()}</li>
 *     <li>{@link #onDisable()} on shutdown</li>
 * </ol>
 */
public interface MinePanelExtension {

    /**
     * Returns the stable, unique id of this extension.
     * <p>
     * The id is used for runtime bookkeeping and should remain unchanged across versions.
     * </p>
     *
     * @return extension id (for example {@code "reports"})
     */
    String id();

    /**
     * Returns the human-readable display name shown in UI/status views.
     *
     * @return extension display name
     */
    String displayName();

    /**
     * Called once when the extension is loaded and before it is enabled.
     * <p>
     * Use this for initialization that requires MinePanel services, such as setting up
     * database schema or caching dependencies from the provided context.
     * </p>
     *
     * @param context runtime context provided by MinePanel
     */
    default void onLoad(ExtensionContext context) {
    }

    /**
     * Called after {@link #onLoad(ExtensionContext)} when the extension should become active.
     * <p>
     * Use this for registering listeners, commands, or background tasks.
     * </p>
     */
    default void onEnable() {
    }

    /**
     * Called when MinePanel is disabling the extension.
     * <p>
     * Use this hook to release resources and stop tasks started in {@link #onEnable()}.
     * </p>
     */
    default void onDisable() {
    }

    /**
     * Allows the extension to register HTTP routes in the MinePanel web server.
     *
     * @param webRegistry route registration facade
     */
    default void registerWebRoutes(ExtensionWebRegistry webRegistry) {
    }

    /**
     * Returns sidebar navigation tabs contributed by this extension.
     *
     * @return list of extension tabs, or an empty list if none are contributed
     */
    default List<ExtensionNavigationTab> navigationTabs() {
        return List.of();
    }
}

