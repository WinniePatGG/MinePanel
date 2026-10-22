package de.winniepat.minePanel.extensions;

/**
 * Optional extension contract for runtime settings updates from the panel.
 */
public interface ExtensionConfigurable {

    /**
     * Applies updated settings JSON immediately at runtime.
     *
     * @param settingsJson extension settings JSON payload
     */
    void onSettingsUpdated(String settingsJson);
}

