package me.clip.placeholderapi.expansion;

import org.bukkit.OfflinePlayer;

/** Minimal test double used to verify the dependency-free generated bridge. */
public abstract class PlaceholderExpansion {
    public static Object registered;

    public abstract String getIdentifier();
    public abstract String getAuthor();
    public abstract String getVersion();

    public boolean persist() { return false; }
    public String onRequest(OfflinePlayer player, String parameter) { return null; }

    public boolean register() {
        registered = this;
        return true;
    }

    public boolean unregister() {
        registered = null;
        return true;
    }
}
