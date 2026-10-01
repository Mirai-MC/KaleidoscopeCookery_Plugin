package net.kaleidoscope.cookery.advancement;

import net.kaleidoscope.cookery.api.KaleidoscopeAdvancements;
import net.kaleidoscope.cookery.block.behavior.RiceCropBehavior;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Observe successful CraftEngine item conversion and vanilla fish bucket releases. */
public final class AdvancementGameplayListener implements Listener {
    private static final Key DOUGH = Key.of("kaleidoscopecookery:raw_dough");
    private static final Key NOODLES = Key.of("kaleidoscopecookery:raw_noodles");
    private final BiConsumer<Player, String> award;
    // Vanilla empties the bucket and spawns its mob synchronously on the same region thread.
    private final Map<Thread, BucketRelease> releases = new ConcurrentHashMap<>();

    private record BucketRelease(PlayerBucketEmptyEvent event, EntityType type, Location location) {}

    public AdvancementGameplayListener() {
        this(KaleidoscopeAdvancements::recordEvent);
    }

    public AdvancementGameplayListener(BiConsumer<Player, String> award) {
        this.award = award;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        var item = BukkitItemManager.instance().wrap(event.getItem());
        if (!DOUGH.equals(item.id()) || event.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        var definition = item.getDefinition().orElse(null);
        if (definition == null || !NOODLES.equals(definition.settings().consumeReplacement())
                || !CraftEngineItems.loadedItems().containsKey(NOODLES)) return;
        int remainingDough = event.getPlayer().getInventory().getItem(event.getHand()).getAmount() - 1;
        FoliaUtil.runLater(() -> {
            if (event.isCancelled() || !DOUGH.equals(CraftEngineItems.getCustomItemId(event.getItem()))) return;
            // A single dough becomes noodles in hand. A stack keeps its remaining dough;
            // CraftEngine gives the noodles separately after vanilla consumes one copy.
            var replacement = event.getReplacement();
            if (replacement == null) return;
            Key id = CraftEngineItems.getCustomItemId(replacement);
            if (NOODLES.equals(id) || (DOUGH.equals(id) && remainingDough > 0
                    && replacement.getAmount() == remainingDough)) award.accept(event.getPlayer(), "pull_the_dough");
        }, null, 1, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Thread thread = Thread.currentThread();
        releases.remove(thread);
        EntityType type = bucketType(event.getBucket());
        if (event.isCancelled() || type == null) return;
        var release = new BucketRelease(event, type, event.getBlock().getLocation());
        releases.put(thread, release);
        // Failed or cancelled spawns must not leave a candidate for a later action.
        FoliaUtil.runLater(() -> releases.remove(thread, release), 1, release.location());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDispense(BlockDispenseEvent event) {
        releases.remove(Thread.currentThread());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BUCKET) return;
        BucketRelease release = releases.remove(Thread.currentThread());
        if (release == null || event.isCancelled() || release.event().isCancelled()
                || event.getEntityType() != release.type() || !sameBlock(event.getLocation(), release.location())) return;
        var fish = event.getEntity();
        FoliaUtil.runLater(() -> {
            if (event.isCancelled() || release.event().isCancelled() || !fish.isValid()) return;
            // Match the mod's 3 x 3 x 1 search, dispatching each block to its owning region.
            AtomicBoolean awarded = new AtomicBoolean();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    Location location = release.location().clone().add(x, 0, z);
                    FoliaUtil.run(() -> {
                        var state = CraftEngineBlocks.getCustomBlockState(location.getBlock());
                        RiceCropBehavior rice = state == null ? null : state.behavior().getFirst(RiceCropBehavior.class);
                        if (rice != null && rice.isGrowthBooster(release.type()) && awarded.compareAndSet(false, true)) {
                            award.accept(release.event().getPlayer(), "place_fish_in_rice_field");
                        }
                    }, location);
                }
            }
        }, null, 1, fish);
    }

    private static boolean sameBlock(Location first, Location second) {
        return first.getWorld().equals(second.getWorld()) && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY() && first.getBlockZ() == second.getBlockZ();
    }

    private static EntityType bucketType(Material bucket) {
        return switch (bucket) {
            case COD_BUCKET -> EntityType.COD;
            case SALMON_BUCKET -> EntityType.SALMON;
            case TROPICAL_FISH_BUCKET -> EntityType.TROPICAL_FISH;
            case PUFFERFISH_BUCKET -> EntityType.PUFFERFISH;
            case TADPOLE_BUCKET -> EntityType.TADPOLE;
            case AXOLOTL_BUCKET -> EntityType.AXOLOTL;
            default -> null;
        };
    }
}
