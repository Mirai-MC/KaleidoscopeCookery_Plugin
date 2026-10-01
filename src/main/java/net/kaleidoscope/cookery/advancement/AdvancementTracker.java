package net.kaleidoscope.cookery.advancement;

import org.bukkit.entity.Player;

/** Keeps the optional advancement API out of gameplay classes. */
public interface AdvancementTracker extends AutoCloseable {
    void reload();
    void recordEvent(Player player, String event);
    @Override
    void close();
}
