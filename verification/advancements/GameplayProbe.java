import net.kaleidoscope.cookery.advancement.AdvancementGameplayListener;
import net.kaleidoscope.cookery.block.behavior.RiceCropBehavior;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Cod;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Synthetic event regression against real Paper entities and CraftEngine pack data. */
public final class GameplayProbe {
    private final Map<String, Integer> signals = new LinkedHashMap<>();
    private final Map<String, Integer> expected = new LinkedHashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final AdvancementGameplayListener listener = new AdvancementGameplayListener((player, signal) -> {
        String key = player.getName() + ":" + signal;
        signals.merge(key, 1, Integer::sum);
    });

    public static void verify(JavaPlugin plugin, Map<String, Object> report, Runnable done, Consumer<Throwable> failed) {
        GameplayProbe probe = new GameplayProbe();
        Location rice = new Location(Bukkit.getWorlds().getFirst(), 240, 100, 240);
        try {
            // This fixture has no online players to keep its entities' chunk loaded.
            rice.getWorld().setChunkForceLoaded(rice.getBlockX() >> 4, rice.getBlockZ() >> 4, true);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                rice.clone().add(x, -1, z).getBlock().setType(Material.STONE, false);
                rice.clone().add(x, 0, z).getBlock().setType(Material.WATER, false);
            }
            rice.clone().add(0, -1, 0).getBlock().setType(Material.FARMLAND, false);
            if (!CraftEngineBlocks.place(rice, Key.of("kaleidoscopecookery:rice_crop"), false)) throw new AssertionError("rice placement");
            RiceCropBehavior behavior = CraftEngineBlocks.getCustomBlockState(rice.getBlock()).behavior().getFirst(RiceCropBehavior.class);
            if (!behavior.isGrowthBooster(EntityType.COD) || behavior.isGrowthBooster(EntityType.CHICKEN)) throw new AssertionError("configured rice boosters");
            report.put("hook rice booster configuration", true);
            probe.doughCases();
            probe.fishCases(rice);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    report.put("hook rice survives scheduled tick", CraftEngineBlocks.getCustomBlockState(rice.getBlock()) != null);
                    probe.expected.forEach((key, count) -> {
                        if (probe.signals.getOrDefault(key, 0) != count) throw new AssertionError(key + " expected " + count + " got " + probe.signals.get(key));
                        report.put("hook " + key, true);
                    });
                    done.run();
                } catch (Throwable error) { failed.accept(error); }
                finally {
                    probe.entities.forEach(Entity::remove);
                    CraftEngineBlocks.remove(rice.getBlock());
                    rice.getWorld().setChunkForceLoaded(rice.getBlockX() >> 4, rice.getBlockZ() >> 4, false);
                }
            }, 5);
        } catch (Throwable error) {
            probe.entities.forEach(Entity::remove);
            CraftEngineBlocks.remove(rice.getBlock());
            rice.getWorld().setChunkForceLoaded(rice.getBlockX() >> 4, rice.getBlockZ() >> 4, false);
            failed.accept(error);
        }
    }

    private void doughCases() {
        consume("dough single", GameMode.SURVIVAL, "raw_dough", "raw_noodles", EquipmentSlot.HAND, false, false, false, 1);
        consume("dough stack offhand", GameMode.SURVIVAL, "raw_dough", "raw_dough", EquipmentSlot.OFF_HAND, false, false, false, 1);
        consume("dough cancelled", GameMode.SURVIVAL, "raw_dough", "raw_noodles", EquipmentSlot.HAND, true, false, false, 0);
        consume("dough late cancellation", GameMode.SURVIVAL, "raw_dough", "raw_noodles", EquipmentSlot.HAND, false, true, false, 0);
        consume("dough creative no conversion", GameMode.CREATIVE, "raw_dough", "raw_noodles", EquipmentSlot.HAND, false, false, false, 0);
        consume("noodles acquisition is not pulling", GameMode.SURVIVAL, "raw_noodles", "raw_noodles", EquipmentSlot.HAND, false, false, false, 0);
        consume("dough wrong replacement", GameMode.SURVIVAL, "raw_dough", "mantou", EquipmentSlot.HAND, false, false, false, 0);
        consume("dough single not converted", GameMode.SURVIVAL, "raw_dough", "raw_dough", EquipmentSlot.HAND, false, false, false, 0);
        consume("dough changed consumed item", GameMode.SURVIVAL, "raw_dough", "raw_noodles", EquipmentSlot.HAND, false, false, true, 0);
    }

    private void consume(String name, GameMode mode, String item, String replacement, EquipmentSlot hand,
                         boolean cancelled, boolean lateCancelled, boolean changedItem, int count) {
        var event = new PlayerItemConsumeEvent(player(name, mode), item(item), hand);
        event.setReplacement(item(replacement));
        event.setCancelled(cancelled);
        listener.onConsume(event);
        if (lateCancelled) event.setCancelled(true);
        if (changedItem) event.setItem(item("raw_noodles"));
        expected.put(name + ":pull_the_dough", count);
    }

    private void fishCases(Location rice) {
        release("fish rice adjacent", rice.clone().add(1, 0, 1), false, false, false, false, false, 1);
        release("fish bucket cancelled", rice.clone().add(1, 0, 0), true, false, false, false, false, 0);
        release("fish bucket late cancellation", rice.clone().add(1, 0, 0), false, true, false, false, false, 0);
        release("fish spawn cancelled", rice.clone().add(1, 0, 0), false, false, true, false, false, 0);
        release("fish beyond rice range", rice.clone().add(5, 0, 0), false, false, false, false, false, 0);
        release("fish wrong rice height", rice.clone().add(1, -1, 0), false, false, false, false, false, 0);
        release("fish dispenser excluded", rice.clone().add(1, 0, 0), false, false, false, true, false, 0);
        release("fish absent from world", rice.clone().add(1, 0, 0), false, false, false, false, true, 0);
    }

    private void release(String name, Location location, boolean cancelled, boolean lateCancelled,
                         boolean spawnCancelled, boolean dispenser, boolean removed, int count) {
        var event = new PlayerBucketEmptyEvent(player(name, GameMode.SURVIVAL), location.getBlock(),
                location.clone().add(0, -1, 0).getBlock(), BlockFace.UP, Material.COD_BUCKET,
                new ItemStack(Material.BUCKET), EquipmentSlot.HAND);
        event.setCancelled(cancelled);
        listener.onBucketEmpty(event);
        if (dispenser) listener.onDispense(new BlockDispenseEvent(location.getBlock(), new ItemStack(Material.COD_BUCKET), new Vector()));
        LivingEntity fish = location.getWorld().spawn(location.clone().add(0.5, 0, 0.5), Cod.class);
        entities.add(fish);
        var spawn = new CreatureSpawnEvent(fish, CreatureSpawnEvent.SpawnReason.BUCKET);
        listener.onBucketSpawn(spawn);
        if (lateCancelled) event.setCancelled(true);
        if (spawnCancelled) spawn.setCancelled(true);
        if (removed) fish.remove();
        expected.put(name + ":place_fish_in_rice_field", count);
    }

    private static ItemStack item(String id) {
        return ItemStackUtils.getBukkitStack(InventoryUtils.createOrEmpty(Key.of("kaleidoscopecookery:" + id)));
    }

    private static Player player(String name, GameMode mode) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getItem")) throw new UnsupportedOperationException(method.getName());
                    ItemStack held = item("raw_dough");
                    held.setAmount(name.equals("dough stack offhand") ? 2 : 1);
                    return held;
                });
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> uuid;
            case "getGameMode" -> mode;
            case "getInventory" -> inventory;
            case "isOnline", "isValid" -> true;
            case "equals" -> proxy == args[0];
            case "hashCode" -> uuid.hashCode();
            case "toString" -> "GameplayProbe(" + name + ")";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
