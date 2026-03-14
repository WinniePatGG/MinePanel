package de.winniepat.minePanel.logs;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ChatCaptureListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN_TEXT_SERIALIZER = PlainTextComponentSerializer.plainText();

    private final PanelLogger panelLogger;

    public ChatCaptureListener(PanelLogger panelLogger) {
        this.panelLogger = panelLogger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String message = PLAIN_TEXT_SERIALIZER.serialize(event.message());
        panelLogger.log("CHAT", event.getPlayer().getName(), message);
    }
}

