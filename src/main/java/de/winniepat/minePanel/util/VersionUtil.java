package de.winniepat.minePanel.util;

import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionUtil {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    private static final VersionUtil INSTANCE = new VersionUtil();

    private static final int NEW_SERIES_START = 26;

    private final int major;
    private final int minor;
    private final int patch;
    private final String rawVersion;
    private final String bukkitVersion;
    private final boolean isFolia;

    private VersionUtil() {
        this.rawVersion = extractMinecraftVersion(Bukkit.getVersion());
        this.bukkitVersion = Bukkit.getBukkitVersion();
        this.isFolia = detectFolia();

        VersionParts parts = parseVersion(rawVersion);
        this.major = parts.major;
        this.minor = parts.minor;
        this.patch = parts.patch;
    }

    private String extractMinecraftVersion(String versionString) {
        if (versionString != null && !versionString.isBlank()) {
            int mcIndex = versionString.indexOf("MC: ");
            if (mcIndex >= 0) {
                int start = mcIndex + "MC: ".length();
                int end = start;
                while (end < versionString.length()
                        && (Character.isDigit(versionString.charAt(end)) || versionString.charAt(end) == '.')) {
                    end++;
                }
                if (end > start) {
                    return versionString.substring(start, end);
                }
            }
        }
        String bukkit = Bukkit.getBukkitVersion();
        if (bukkit != null && !bukkit.isBlank()) {
            int dashIndex = bukkit.indexOf('-');
            return dashIndex > 0 ? bukkit.substring(0, dashIndex) : bukkit.trim();
        }
        return "1.16.3";
    }

    public static VersionUtil getInstance() {
        return INSTANCE;
    }

    private boolean detectFolia() {
        try {
            Class<?> serverClass = Bukkit.getServer().getClass();
            return serverClass.getMethod("getGlobalRegionScheduler") != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private VersionParts parseVersion(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (matcher.find()) {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
            return new VersionParts(major, minor, patch);
        }
        return new VersionParts(1, 16, 3);
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }

    public String getRawVersion() {
        return rawVersion;
    }

    public String getBukkitVersion() {
        return bukkitVersion;
    }

    public boolean isFolia() {
        return isFolia;
    }

    public boolean isNewSeries() {
        return this.major >= NEW_SERIES_START;
    }

    public boolean isVersion26Plus() {
        return isNewSeries();
    }

    public boolean isAtLeast(int major, int minor, int patch) {
        if (this.major != major) {
            return this.major > major;
        }
        if (this.minor != minor) {
            return this.minor > minor;
        }
        return this.patch >= patch;
    }

    public boolean isAtLeastMinor(int minor) {
        if (isNewSeries()) {
            return true;
        }
        return isAtLeast(1, minor, 0);
    }

    public boolean isMinorVersion(int minor) {
        return this.major == 1 && this.minor == minor;
    }

    public boolean isVersion1_16() {
        return isMinorVersion(16);
    }

    public boolean isVersion1_17() {
        return isMinorVersion(17);
    }

    public boolean isVersion1_18() {
        return isMinorVersion(18);
    }

    public boolean isVersion1_19() {
        return isMinorVersion(19);
    }

    public boolean isVersion1_20() {
        return isMinorVersion(20);
    }

    public boolean isVersion1_21Plus() {
        return isNewSeries() || (major == 1 && minor >= 21);
    }

    public boolean supportsGlobalRegionScheduler() {
        return isAtLeastMinor(18) && isFolia();
    }

    public boolean supportsAsyncScheduler() {
        return isAtLeastMinor(18) && isFolia();
    }

    public boolean supportsTPS() {
        return isAtLeastMinor(14);
    }

    public boolean supportsBrigadier() {
        return isAtLeastMinor(13);
    }

    public boolean supportsPluginLifecycleAPI() {
        return isAtLeast(1, 20, 5);
    }

    public boolean supportsAdventureComponents() {
        return isAtLeastMinor(16);
    }

    public boolean supportsRegistries() {
        return isAtLeast(1, 20, 5);
    }

    public boolean supportsDataComponents() {
        return isAtLeast(1, 20, 5);
    }

    public static List<String> getSupportedVersions() {
        return Arrays.asList(
            "1.16.3", "1.16.4", "1.16.5",
            "1.17", "1.17.1",
            "1.18", "1.18.1", "1.18.2",
            "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
            "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
            "26.1", "26.1.1", "26.1.2", "26.2", "26.3"
        );
    }

    public String getVersionString() {
        String serverType = isFolia ? "Folia" : "Paper";
        return String.format("%s %s (MC %s)", serverType, bukkitVersion, rawVersion);
    }

    private record VersionParts(int major, int minor, int patch) {}
}
