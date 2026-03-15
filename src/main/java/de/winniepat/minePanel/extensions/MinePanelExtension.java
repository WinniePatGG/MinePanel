package de.winniepat.minePanel.extensions;

import java.util.List;

public interface MinePanelExtension {

    String id();

    String displayName();

    default void onLoad(ExtensionContext context) {
    }

    default void onEnable() {
    }

    default void onDisable() {
    }

    default void registerWebRoutes(ExtensionWebRegistry webRegistry) {
    }

    default List<ExtensionNavigationTab> navigationTabs() {
        return List.of();
    }
}

