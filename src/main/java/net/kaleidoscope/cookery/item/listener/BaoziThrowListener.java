package net.kaleidoscope.cookery.item.listener;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kaleidoscope.cookery.api.KaleidoscopeAdvancements;
import net.kaleidoscope.cookery.item.ItemKeys;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.kaleidoscope.cookery.util.Hands;
import net.kaleidoscope.cookery.util.InteractGuard;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.plugin.scheduler.SchedulerTask;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Wolf;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/** Sneak + right-click throws one baozi; ordinary right-click still eats it. */
public final class BaoziThrowListener implements Listener, AutoCloseable {
    private static final int COOLDOWN_TICKS = 5;
    private static final long LIFETIME_TICKS = 100L;
    private final NamespacedKey marker;
    private final BiConsumer<Player, String> award;
    private final BiPredicate<Player, Location> canInteract;
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastThrow = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public BaoziThrowListener(Plugin plugin) {
        this(plugin, KaleidoscopeAdvancements::recordEvent, InteractGuard::canInteract);
    }

    public BaoziThrowListener(Plugin plugin, BiConsumer<Player, String> award,
                              BiPredicate<Player, Location> canInteract) {
        this.marker = new NamespacedKey(plugin, "thrown_baozi");
        this.award = award;
        this.canInteract = canInteract;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Air interactions may be pre-cancelled by vanilla through useInteractedBlock only.
        if (event.useItemInHand() == Event.Result.DENY || !eligible(event.getPlayer(), event.getHand())) return;
        Location target = event.getClickedBlock() == null
                ? event.getPlayer().getLocation() : event.getClickedBlock().getLocation();
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (canInteract.test(event.getPlayer(), target)) throwBaozi(event.getPlayer(), event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.isCancelled() || !eligible(event.getPlayer(), event.getHand())) return;
        event.setCancelled(true);
        if (canInteract.test(event.getPlayer(), event.getRightClicked().getLocation())) {
            throwBaozi(event.getPlayer(), event.getHand());
        }
    }

    private boolean eligible(Player player, EquipmentSlot hand) {
        if (closed || !player.isSneaking() || player.getGameMode() == GameMode.SPECTATOR
                || (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND)) return false;
        if (!isBaozi(player.getInventory().getItem(hand))) return false;
        // When both hands hold baozi, the main hand owns the action.
        return hand != EquipmentSlot.OFF_HAND || !isBaozi(player.getInventory().getItemInMainHand());
    }

    private static boolean isBaozi(ItemStack item) {
        return item != null && !item.isEmpty() && ItemKeys.BAOZI.equals(CraftEngineItems.getCustomItemId(item));
    }

    private void throwBaozi(Player player, EquipmentSlot hand) {
        int now = player.getTicksLived();
        Integer previous = lastThrow.get(player.getUniqueId());
        if (previous != null && now - previous < COOLDOWN_TICKS) return;
        lastThrow.put(player.getUniqueId(), now);
        ItemStack original = player.getInventory().getItem(hand).clone();
        ItemStack appearance = original.clone();
        appearance.setAmount(1);
        Location origin = player.getEyeLocation().subtract(0, 0.1, 0);
        Vector velocity = origin.getDirection().multiply(1.5);
        Snowball projectile = origin.getWorld().spawn(origin, Snowball.class, ball -> {
            ball.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            ball.setPersistent(false);
            ball.setItem(appearance);
            ball.setShooter(player);
            ball.setVelocity(velocity);
        });
        // World.spawn fires ProjectileLaunchEvent. Inspect the result after every listener ran.
        ItemStack remaining = player.getInventory().getItem(hand);
        if (closed || !projectile.isValid() || remaining == null || !remaining.equals(original)) {
            projectile.remove();
            return;
        }
        // Like the mod, throwing consumes one even in creative; eating retains its own rules.
        remaining = remaining.clone();
        remaining.setAmount(remaining.getAmount() - 1);
        player.getInventory().setItem(hand, remaining);
        Flight flight = new Flight(projectile, player);
        flights.put(projectile.getUniqueId(), flight);
        // Disable may race an already-running throw on another Folia region.
        if (closed) {
            flights.remove(projectile.getUniqueId(), flight);
            projectile.remove();
            return;
        }
        flight.timeout = FoliaUtil.runLater(() -> {
            flights.remove(projectile.getUniqueId(), flight);
            projectile.remove();
        }, () -> flights.remove(projectile.getUniqueId(), flight), LIFETIME_TICKS, projectile);
        origin.getWorld().playSound(origin, Sound.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.5f, 0.7f);
        Hands.swing(player, hand);
    }

    private boolean marked(Entity entity) {
        return entity instanceof Snowball && entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSuppressVanillaHit(ProjectileHitEvent event) {
        if (marked(event.getEntity()) && !(event.getHitEntity() instanceof Wolf)) {
            // Baozi must not activate target blocks/bells or vanilla entity reactions.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (marked(event.getDamager())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent event) {
        Flight flight = flights.remove(event.getEntity().getUniqueId());
        if (flight == null) return;
        if (flight.timeout != null) flight.timeout.cancel();
        Location impact = flight.projectile.getLocation();
        ItemStack item = flight.projectile.getItem();
        flight.projectile.remove();
        impact.getWorld().spawnParticle(Particle.ITEM, impact, 12, 0.15, 0.15, 0.15, 0.03, item);
        impact.getWorld().playSound(impact, Sound.BLOCK_SNOW_HIT, SoundCategory.NEUTRAL, 1, 1);
        if (event.getHitEntity() instanceof Wolf wolf) {
            // Wait until all hit listeners finish; heal on the wolf's owning region.
            FoliaUtil.runLater(() -> {
                if (!closed && !event.isCancelled()) feedWolf(flight.player, wolf);
            }, () -> {}, 1, wolf);
        }
    }

    private void feedWolf(Player player, Wolf wolf) {
        if (!player.isOnline() || !wolf.isValid() || wolf.isDead() || !canInteract.test(player, wolf.getLocation())) return;
        var attribute = wolf.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) return;
        double missing = Math.max(0, attribute.getValue() - wolf.getHealth());
        var regain = new EntityRegainHealthEvent(wolf, missing, EntityRegainHealthEvent.RegainReason.CUSTOM);
        if (!regain.callEvent() || !wolf.isValid() || wolf.isDead()) return;
        double amount = regain.getAmount();
        if (!Double.isFinite(amount) || amount < 0 || (missing > 0 && amount == 0)) return;
        wolf.setHealth(Math.min(attribute.getValue(), wolf.getHealth() + amount));
        wolf.getWorld().spawnParticle(Particle.HEART, wolf.getLocation().add(0, 0.5, 0), 7, 0.3, 0.2, 0.3, 0);
        FoliaUtil.runEntity(player, () -> {
            if (!closed && player.isOnline()) award.accept(player, "meat_buns_beat_dogs");
        });
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        Flight flight = flights.remove(event.getEntity().getUniqueId());
        if (flight != null && flight.timeout != null) flight.timeout.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastThrow.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        closed = true;
        for (Flight flight : flights.values()) {
            if (flight.timeout != null) flight.timeout.cancel();
            if (FoliaUtil.isFolia()) {
                // CE remains enabled during our disable; do not use FoliaUtil's shutdown gate.
                BukkitCraftEngine.instance().scheduler().platform().run(flight.projectile::remove, () -> {}, flight.projectile);
            } else flight.projectile.remove();
        }
        flights.clear();
        lastThrow.clear();
    }

    private static final class Flight {
        final Snowball projectile;
        final Player player;
        volatile SchedulerTask timeout;

        Flight(Snowball projectile, Player player) {
            this.projectile = projectile;
            this.player = player;
        }
    }
}
