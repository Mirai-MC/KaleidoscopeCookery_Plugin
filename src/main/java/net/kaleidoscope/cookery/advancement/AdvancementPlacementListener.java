package net.kaleidoscope.cookery.advancement;

import net.kaleidoscope.cookery.api.KaleidoscopeAdvancements;
import net.kaleidoscope.cookery.block.entity.ScarecrowController;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.kaleidoscope.cookery.util.HeatSourceUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.bukkit.world.BukkitWorldManager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Confirm placements after all cancellation and rollback handlers have finished. */
public final class AdvancementPlacementListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(CustomBlockPlaceEvent event) {
        String signal = switch (event.customBlock().id().asString()) {
            case "kaleidoscopecookery:pot" -> "place_pot_on_heat_source";
            case "kaleidoscopecookery:stockpot" -> "place_stockpot_on_heat_source";
            case "kaleidoscopecookery:steamer" -> "use_steamer";
            default -> null;
        };
        if (signal == null) return;
        Location location = event.location();
        FoliaUtil.runLater(() -> {
            if (event.isCancelled()) return;
            var state = CraftEngineBlocks.getCustomBlockState(location.getBlock());
            if (state == null || !state.owner().value().id().equals(event.customBlock().id())) return;
            Object level = BukkitWorldManager.instance().getWorld(location.getWorld().getUID()).storageWorld().world().minecraftWorld();
            if (HeatSourceUtils.isHeatSource(level, LocationUtils.toBlockPos(
                    location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()))) {
                KaleidoscopeAdvancements.recordEvent(event.player(), signal);
            }
        }, 1, location);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        if (!(event.furniture().controller instanceof ScarecrowController)) return;
        FoliaUtil.runLater(() -> {
            if (!event.isCancelled() && event.furniture().isValid()) {
                KaleidoscopeAdvancements.recordEvent(event.player(), "place_scarecrow");
            }
        }, 1, event.location());
    }
}
