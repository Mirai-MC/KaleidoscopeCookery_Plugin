package net.kaleidoscope.cookery.api;

import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import org.bukkit.entity.Player;

/** Call only after the corresponding action has succeeded. Unknown events are ignored. */
public final class KaleidoscopeAdvancements {
    private KaleidoscopeAdvancements() {}

    public static void recordEvent(Player player, String event) {
        KaleidoscopeCookeryPlugin plugin = KaleidoscopeCookeryPlugin.instance();
        if (plugin != null && player != null) {
            plugin.recordAdvancementEvent(player, event);
        }
    }

    public static void recordEvent(net.momirealms.craftengine.core.entity.player.Player player, String event) {
        if (player != null) recordEvent((Player) player.platformPlayer(), event);
    }
}
