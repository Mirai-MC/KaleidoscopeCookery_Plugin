import com.mojang.authlib.GameProfile;
import net.kaleidoscope.cookery.item.listener.BaoziThrowListener;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Wolf;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Paper 26.3 fixture: detached CraftPlayers, real item stacks, entities and event dispatch. */
public final class BaoziProbe implements Listener {
    private final JavaPlugin plugin;
    private final Map<String, Object> report;
    private final Map<String, Integer> signals = new HashMap<>();
    private final Map<String, Integer> expected = new LinkedHashMap<>();
    private final Map<String, Snowball> balls = new HashMap<>();
    private final Map<String, Integer> launches = new HashMap<>();
    private final Map<UUID, String> regainPolicies = new HashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Location origin = new Location(Bukkit.getWorlds().getFirst(), 340, 106, 340);
    private final BaoziThrowListener listener;
    private FakePlayer current;
    private Wolf physicsWolf;
    private Blaze physicsBlaze;

    private BaoziProbe(JavaPlugin plugin, Map<String, Object> report) {
        this.plugin = plugin;
        this.report = report;
        listener = new BaoziThrowListener(plugin, (player, signal) -> {
            if (!signal.equals("meat_buns_beat_dogs")) throw new AssertionError(signal);
            signals.merge(player.getName(), 1, Integer::sum);
        }, (player, target) -> !player.getName().equals("throw territory denied")
                && (!player.getName().equals("hit territory denied")
                || target.distanceSquared(player.getLocation()) < 4));
    }

    public static void verify(JavaPlugin plugin, Map<String, Object> report, Runnable done, Consumer<Throwable> failed) {
        BaoziProbe probe = new BaoziProbe(plugin, report);
        probe.origin.getWorld().setChunkForceLoaded(21, 21, true);
        Bukkit.getPluginManager().registerEvents(probe, plugin);
        Bukkit.getPluginManager().registerEvents(probe.listener, plugin);
        try {
            probe.throwCases();
            probe.hitCases();
            probe.physicsCases();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    probe.expected.forEach((name, count) -> probe.check(name + " award", probe.signals.getOrDefault(name, 0) == count));
                    probe.check("real wolf collision heals", probe.physicsWolf.getHealth() == 20);
                    probe.check("real wolf collision retires projectile", !probe.balls.get("physical wolf").isValid());
                    probe.check("real blaze collision does no damage", probe.physicsBlaze.getHealth() == 20);
                    probe.check("real blaze collision retires projectile", !probe.balls.get("physical blaze").isValid());
                    probe.check("miss retires after five seconds", !probe.balls.get("miss timeout").isValid());
                    probe.check("consumed miss is not returned", probe.timeoutPlayer.getInventory().getItemInMainHand().getAmount() == 1);
                    // A live flight is removed during disable rather than left in the world.
                    FakePlayer closing = probe.player("close flight");
                    Snowball closingBall = probe.throwOne(closing, EquipmentSlot.HAND, false);
                    probe.listener.close();
                    probe.check("disable retires active flight", !closingBall.isValid());
                    probe.click(probe.player("closed listener"), EquipmentSlot.HAND, false);
                    probe.check("closed listener cannot throw", !probe.balls.containsKey("closed listener"));
                    Field flights = BaoziThrowListener.class.getDeclaredField("flights");
                    flights.setAccessible(true);
                    probe.check("all flights cleared", ((Map<?, ?>) flights.get(probe.listener)).isEmpty());
                    done.run();
                } catch (Throwable error) { failed.accept(error); }
                finally { probe.cleanup(); }
            }, 105);
        } catch (Throwable error) {
            probe.cleanup();
            failed.accept(error);
        }
    }

    private FakePlayer timeoutPlayer;

    private void throwCases() {
        FakePlayer eating = player("ordinary eating");
        eating.sneaking = false;
        PlayerInteractEvent ordinary = click(eating, EquipmentSlot.HAND, false);
        check("normal right click preserves eating", ordinary.useItemInHand() == Event.Result.DEFAULT && !balls.containsKey(eating.getName()));
        FakePlayer other = player("other food");
        other.getInventory().setItemInMainHand(item("mantou", 2));
        click(other, EquipmentSlot.HAND, false);
        check("other food excluded", !balls.containsKey(other.getName()));
        FakePlayer denied = player("item use denied");
        click(denied, EquipmentSlot.HAND, true);
        check("cancelled item use excluded", !balls.containsKey(denied.getName()) && count(denied, EquipmentSlot.HAND) == 2);
        FakePlayer cancelled = player("launch cancelled");
        click(cancelled, EquipmentSlot.HAND, false);
        check("late launch cancellation keeps item", !balls.get(cancelled.getName()).isValid() && count(cancelled, EquipmentSlot.HAND) == 2);
        FakePlayer removed = player("launch removed");
        click(removed, EquipmentSlot.HAND, false);
        check("removed launch keeps item", !balls.get(removed.getName()).isValid() && count(removed, EquipmentSlot.HAND) == 2);
        FakePlayer changed = player("held item changed");
        click(changed, EquipmentSlot.HAND, false);
        check("changed hand aborts launch", !balls.get(changed.getName()).isValid()
                && Key.of("kaleidoscopecookery:mantou").equals(CraftEngineItems.getCustomItemId(changed.getInventory().getItemInMainHand())));
        FakePlayer territory = player("throw territory denied");
        click(territory, EquipmentSlot.HAND, false);
        check("throw territory gate keeps item", !balls.containsKey(territory.getName()) && count(territory, EquipmentSlot.HAND) == 2);
        FakePlayer main = player("main hand cooldown");
        Snowball ball = throwOne(main, EquipmentSlot.HAND, false);
        click(main, EquipmentSlot.HAND, false);
        check("repeat click cannot double consume", count(main, EquipmentSlot.HAND) == 1 && launches.get(main.getName()) == 1);
        check("projectile uses baozi model", Key.of("kaleidoscopecookery:baozi").equals(CraftEngineItems.getCustomItemId(ball.getItem())) && ball.getItem().getAmount() == 1);
        check("projectile preserves shooter", ball.getShooter() instanceof Player p && p.getUniqueId().equals(main.getUniqueId()));
        check("flight is not saved", !ball.isPersistent());
        FakePlayer both = player("both hands");
        both.getInventory().setItemInMainHand(item("baozi", 1));
        both.getInventory().setItemInOffHand(item("baozi", 2));
        throwOne(both, EquipmentSlot.HAND, false);
        click(both, EquipmentSlot.OFF_HAND, false);
        check("both hands throw only one", count(both, EquipmentSlot.HAND) == 0 && count(both, EquipmentSlot.OFF_HAND) == 2 && launches.get(both.getName()) == 1);
        FakePlayer offhand = player("offhand only");
        offhand.getInventory().setItemInMainHand(item("mantou", 2));
        offhand.getInventory().setItemInOffHand(item("baozi", 2));
        throwOne(offhand, EquipmentSlot.OFF_HAND, false);
        check("offhand consumes selected stack", count(offhand, EquipmentSlot.OFF_HAND) == 1 && count(offhand, EquipmentSlot.HAND) == 2);
        FakePlayer creative = player("creative throw");
        creative.mode = GameMode.CREATIVE;
        throwOne(creative, EquipmentSlot.HAND, false);
        check("creative matches mod consumption", count(creative, EquipmentSlot.HAND) == 1);
        FakePlayer spectator = player("spectator");
        spectator.mode = GameMode.SPECTATOR;
        click(spectator, EquipmentSlot.HAND, false);
        check("spectator cannot throw", !balls.containsKey(spectator.getName()));
        FakePlayer preCancelledAir = player("vanilla air deny");
        current = preCancelledAir;
        var air = interaction(preCancelledAir, EquipmentSlot.HAND);
        air.setUseInteractedBlock(Event.Result.DENY);
        listener.onInteract(air);
        freeze(balls.get(preCancelledAir.getName()));
        current = null;
        check("vanilla air pre cancellation permits throw", count(preCancelledAir, EquipmentSlot.HAND) == 1);
        timeoutPlayer = player("miss timeout");
        throwOne(timeoutPlayer, EquipmentSlot.HAND, false);
    }

    private void hitCases() {
        hitWolf("wild injured", 4, false, false, false, null, 1);
        hitWolf("tamed injured", 3, true, false, false, null, 1);
        hitWolf("full health", 20, false, false, false, null, 1);
        hitWolf("hit cancelled", 4, false, true, false, null, 0);
        hitWolf("hit late cancelled", 4, false, false, true, null, 0);
        hitWolf("hit territory denied", 4, false, false, false, null, 0);
        hitWolf("regain cancelled", 4, false, false, false, "cancel", 0);
        hitWolf("regain zero", 4, false, false, false, "zero", 0);
        hitWolf("regain modified", 4, false, false, false, "half", 1);
        hitWolf("removed wolf", 4, false, false, false, "remove", 0);
        FakePlayer entityClick = player("entity right click");
        Wolf target = wolf(20, 3);
        current = entityClick;
        listener.onInteractEntity(new PlayerInteractEntityEvent(entityClick, target, EquipmentSlot.HAND));
        current = null;
        freeze(balls.get(entityClick.getName()));
        check("entity right click consumes once", count(entityClick, EquipmentSlot.HAND) == 1);
        FakePlayer cancelled = player("entity click cancelled");
        var interact = new PlayerInteractEntityEvent(cancelled, target, EquipmentSlot.HAND);
        interact.setCancelled(true);
        listener.onInteractEntity(interact);
        check("cancelled entity click keeps item", !balls.containsKey(cancelled.getName()) && count(cancelled, EquipmentSlot.HAND) == 2);
        FakePlayer block = player("block hit");
        Snowball ball = throwOne(block, EquipmentSlot.HAND, false);
        var hitBlock = new ProjectileHitEvent(ball, null, origin.getBlock(), BlockFace.UP);
        hitBlock.callEvent();
        check("block hit suppresses vanilla reaction", hitBlock.isCancelled() && !ball.isValid());
        expected.put(block.getName(), 0);
        FakePlayer plain = player("ordinary snowball");
        Snowball ordinary = origin.getWorld().spawn(origin, Snowball.class);
        entities.add(ordinary);
        var plainHit = new ProjectileHitEvent(ordinary, target, null, null);
        plainHit.callEvent();
        check("ordinary snowball is untouched", !plainHit.isCancelled() && ordinary.isValid());
        expected.put(plain.getName(), 0);
    }

    private void hitWolf(String name, double health, boolean tamed, boolean cancelled, boolean late,
                         String policy, int award) {
        FakePlayer player = player(name);
        Snowball ball = throwOne(player, EquipmentSlot.HAND, false);
        Wolf wolf = wolf(health, 2);
        if (tamed) {
            wolf.setOwner(player);
            wolf.setHealth(health);
        }
        if (policy != null) regainPolicies.put(wolf.getUniqueId(), policy);
        var hit = new ProjectileHitEvent(ball, wolf, null, null);
        hit.setCancelled(cancelled);
        hit.callEvent();
        if (late) hit.setCancelled(true);
        // Duplicate delivery must not grant twice.
        hit.callEvent();
        if ("remove".equals(policy)) wolf.remove();
        expected.put(name, award);
        double expectedHealth = award == 1 ? ("half".equals(policy) ? 6 : wolf.getAttribute(Attribute.MAX_HEALTH).getValue()) : health;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try { check(name + " health", !wolf.isValid() || wolf.getHealth() == expectedHealth); }
            catch (Throwable error) { asynchronousFailure = error; }
        }, 4);
        check(name + " hit consumes flight", !ball.isValid());
    }

    private Throwable asynchronousFailure;

    private void physicsCases() {
        FakePlayer player = player("physical wolf");
        player.eye = new Location(origin.getWorld(), 341, 100.7, 341, -90, 0);
        player.getHandle().setPos(341, 100, 341);
        physicsWolf = origin.getWorld().spawn(new Location(origin.getWorld(), 345, 100, 341), Wolf.class);
        physicsWolf.setAI(false);
        physicsWolf.setGravity(false);
        physicsWolf.getAttribute(Attribute.MAX_HEALTH).setBaseValue(20);
        physicsWolf.setHealth(4);
        entities.add(physicsWolf);
        throwOne(player, EquipmentSlot.HAND, true);
        expected.put(player.getName(), 1);
        FakePlayer blazePlayer = player("physical blaze");
        blazePlayer.eye = new Location(origin.getWorld(), 341, 104.7, 341, -90, 0);
        blazePlayer.getHandle().setPos(341, 104, 341);
        physicsBlaze = origin.getWorld().spawn(new Location(origin.getWorld(), 345, 104, 341), Blaze.class);
        physicsBlaze.setAI(false);
        physicsBlaze.setGravity(false);
        entities.add(physicsBlaze);
        throwOne(blazePlayer, EquipmentSlot.HAND, true);
        expected.put(blazePlayer.getName(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (current == null || !(event.getEntity() instanceof Snowball ball)) return;
        String name = current.getName();
        balls.put(name, ball);
        entities.add(ball);
        launches.merge(name, 1, Integer::sum);
        check(name + " launch speed", Math.abs(ball.getVelocity().length() - 1.5) < 0.0001);
        if (name.equals("launch cancelled")) event.setCancelled(true);
        if (name.equals("launch removed")) ball.remove();
        if (name.equals("held item changed")) current.getInventory().setItemInMainHand(item("mantou", 2));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRegain(EntityRegainHealthEvent event) {
        String policy = regainPolicies.get(event.getEntity().getUniqueId());
        if ("cancel".equals(policy)) event.setCancelled(true);
        if ("zero".equals(policy)) event.setAmount(0);
        if ("half".equals(policy)) event.setAmount(2);
    }

    private Snowball throwOne(FakePlayer player, EquipmentSlot hand, boolean moving) {
        click(player, hand, false);
        Snowball ball = balls.get(player.getName());
        if (ball == null || !ball.isValid()) throw new AssertionError("Missing flight " + player.getName());
        if (!moving) freeze(ball);
        check(player.getName() + " consumes one", count(player, hand) == 1 || player.getName().equals("both hands"));
        return ball;
    }

    private PlayerInteractEvent click(FakePlayer player, EquipmentSlot hand, boolean denied) {
        current = player;
        try {
            var event = interaction(player, hand);
            if (denied) event.setUseItemInHand(Event.Result.DENY);
            listener.onInteract(event);
            return event;
        } finally { current = null; }
    }

    private PlayerInteractEvent interaction(FakePlayer player, EquipmentSlot hand) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, player.getInventory().getItem(hand), null, BlockFace.SELF, hand);
    }

    private static void freeze(Snowball ball) {
        if (ball == null) throw new AssertionError("No projectile to freeze");
        ball.setVelocity(new Vector());
        ball.setGravity(false);
    }

    private Wolf wolf(double health, int offset) {
        Wolf wolf = origin.getWorld().spawn(origin.clone().add(7, 3, offset), Wolf.class);
        wolf.setAI(false);
        wolf.setGravity(false);
        wolf.setCollidable(false);
        wolf.getAttribute(Attribute.MAX_HEALTH).setBaseValue(20);
        wolf.setHealth(health);
        entities.add(wolf);
        return wolf;
    }

    private FakePlayer player(String name) {
        var server = (CraftServer) Bukkit.getServer();
        var level = ((CraftWorld) origin.getWorld()).getHandle();
        var profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), "BaoziProbe");
        var handle = new ServerPlayer(server.getServer(), level, profile, ClientInformation.createDefault());
        handle.setPos(origin.getX(), origin.getY(), origin.getZ());
        FakePlayer player = new FakePlayer(server, handle, name, origin.clone().add(0, 1.6, 0));
        player.getInventory().setItemInMainHand(item("baozi", 2));
        return player;
    }

    private static int count(Player player, EquipmentSlot hand) {
        ItemStack held = player.getInventory().getItem(hand);
        return held == null || held.isEmpty() ? 0 : held.getAmount();
    }

    private static ItemStack item(String id, int amount) {
        ItemStack item = ItemStackUtils.getBukkitStack(InventoryUtils.createOrEmpty(Key.of("kaleidoscopecookery:" + id)));
        if (item.getType() == Material.AIR) throw new AssertionError("Missing pack item " + id);
        item.setAmount(amount);
        return item;
    }

    private void check(String name, boolean valid) {
        if (asynchronousFailure != null) throw new AssertionError("Deferred health check", asynchronousFailure);
        if (!valid) throw new AssertionError(name);
        report.put("baozi " + name, true);
    }

    private void cleanup() {
        listener.close();
        HandlerList.unregisterAll(listener);
        HandlerList.unregisterAll(this);
        entities.forEach(Entity::remove);
        origin.getWorld().setChunkForceLoaded(21, 21, false);
    }

    private static final class FakePlayer extends CraftPlayer {
        private final String label;
        private Location eye;
        private boolean sneaking = true;
        private GameMode mode = GameMode.SURVIVAL;

        FakePlayer(CraftServer server, ServerPlayer handle, String label, Location eye) {
            super(server, handle);
            this.label = label;
            this.eye = eye;
        }

        @Override public String getName() { return label; }
        @Override public boolean isOnline() { return true; }
        @Override public boolean isSneaking() { return sneaking; }
        @Override public GameMode getGameMode() { return mode; }
        @Override public Location getEyeLocation() { return eye.clone(); }
        @Override public void swingMainHand() {}
        @Override public void swingOffHand() {}
    }
}
