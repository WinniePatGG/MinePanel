package de.winniepat.minePanel.config;

import org.bukkit.configuration.file.FileConfiguration;

public record WebPanelConfig(
        String host,
        int port,
        int sessionTtlMinutes,
        int bootstrapTokenLength,
        int oauthStateTtlMinutes,
        OAuthProviderConfig googleOAuth,
        OAuthProviderConfig discordOAuth
) {

    public static WebPanelConfig fromConfig(FileConfiguration config) {
        String host = config.getString("web.host", "127.0.0.1");
        int port = config.getInt("web.port", 8080);
        int sessionTtlMinutes = Math.max(5, config.getInt("web.sessionTtlMinutes", 120));
        int bootstrapTokenLength = Math.max(16, config.getInt("security.bootstrapTokenLength", 32));
        int oauthStateTtlMinutes = Math.max(2, config.getInt("integrations.oauth.stateTtlMinutes", 10));

        OAuthProviderConfig googleOAuth = parseProvider(config, "google", "MINEPANEL_OAUTH_GOOGLE");
        OAuthProviderConfig discordOAuth = parseProvider(config, "discord", "MINEPANEL_OAUTH_DISCORD");

        return new WebPanelConfig(host, port, sessionTtlMinutes, bootstrapTokenLength, oauthStateTtlMinutes, googleOAuth, discordOAuth);
    }

    private static OAuthProviderConfig parseProvider(FileConfiguration config, String key, String envPrefix) {
        String section = "integrations.oauth." + key + ".";
        boolean enabled = config.getBoolean(section + "enabled", false);
        String clientId = envOrDefault(envPrefix + "_CLIENT_ID", config.getString(section + "clientId", ""));
        String clientSecret = envOrDefault(envPrefix + "_CLIENT_SECRET", config.getString(section + "clientSecret", ""));
        String redirectUri = envOrDefault(envPrefix + "_REDIRECT_URI", config.getString(section + "redirectUri", ""));
        return new OAuthProviderConfig(enabled, sanitize(clientId), sanitize(clientSecret), sanitize(redirectUri));
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    public OAuthProviderConfig oauthProvider(String provider) {
        if (provider == null) {
            return OAuthProviderConfig.disabled();
        }
        String normalized = provider.trim().toLowerCase();
        if ("google".equals(normalized)) {
            return googleOAuth;
        }
        if ("discord".equals(normalized)) {
            return discordOAuth;
        }
        return OAuthProviderConfig.disabled();
    }

    public record OAuthProviderConfig(boolean enabled, String clientId, String clientSecret, String redirectUri) {
        public static OAuthProviderConfig disabled() {
            return new OAuthProviderConfig(false, "", "", "");
        }

        public boolean configured() {
            return enabled && !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
        }
    }
}

