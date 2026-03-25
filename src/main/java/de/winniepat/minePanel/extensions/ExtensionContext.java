package de.winniepat.minePanel.extensions;

import de.winniepat.minePanel.MinePanel;
import de.winniepat.minePanel.logs.PanelLogger;
import de.winniepat.minePanel.persistence.Database;
import de.winniepat.minePanel.persistence.KnownPlayerRepository;
import de.winniepat.minePanel.persistence.PlayerActivityRepository;
import de.winniepat.minePanel.util.ServerSchedulerBridge;

public record ExtensionContext(
        MinePanel plugin,
        Database database,
        PanelLogger panelLogger,
        KnownPlayerRepository knownPlayerRepository,
        PlayerActivityRepository playerActivityRepository,
        ExtensionCommandRegistry commandRegistry,
        ServerSchedulerBridge schedulerBridge
) {
}

