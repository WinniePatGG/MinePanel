package de.winniepat.minePanel.config;

import org.bukkit.configuration.file.FileConfiguration;

public record WebPanelConfig(
        String host,
        int port,
        int sessionTtlMinutes,
        int bootstrapTokenLength
) {

    public static WebPanelConfig fromConfig(FileConfiguration config) {
        String host = config.getString("web.host", "127.0.0.1");
        int port = config.getInt("web.port", 8080);
        int sessionTtlMinutes = Math.max(5, config.getInt("web.sessionTtlMinutes", 120));
        int bootstrapTokenLength = Math.max(16, config.getInt("security.bootstrapTokenLength", 32));
        return new WebPanelConfig(host, port, sessionTtlMinutes, bootstrapTokenLength);
    }
}

