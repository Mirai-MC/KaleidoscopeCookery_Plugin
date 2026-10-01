package net.kaleidoscope.cookery.advancement;

import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.MultiTasksAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.TaskAdvancement;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import com.fren_gor.ultimateAdvancementAPI.events.PlayerLoadingCompletedEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kaleidoscope.cookery.api.ItemTags;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.kaleidoscope.cookery.util.FoliaUtil;
import net.kaleidoscope.cookery.util.InventoryUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stable UAA API with optional Pro layout, localization and reload extensions. */
public final class UltimateAdvancementIntegration implements AdvancementTracker, Listener {
    public static final String NAMESPACE = "kaleidoscopecookery";
    private final KaleidoscopeCookeryPlugin plugin;
    private final UltimateAdvancementAPI api;
    private final AdvancementCatalog catalog = AdvancementCatalog.load();
    private final Map<UUID, Set<AdvancementCatalog.Match>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Attack> attacks = new ConcurrentHashMap<>();
    private volatile State state;
    private volatile boolean closed;
    private record Attack(UUID target, boolean knife, long time) {}

    private record State(AdvancementTab tab, Map<AdvancementCatalog.Match, Advancement> targets,
                         Map<String, List<AdvancementCatalog.Match>> inventory,
                         Map<String, List<AdvancementCatalog.Match>> events,
                         Map<String, List<AdvancementCatalog.Match>> kills, Set<String> knives) {}

    public UltimateAdvancementIntegration(KaleidoscopeCookeryPlugin plugin) {
        this.plugin = plugin;
        this.api = UltimateAdvancementAPI.getInstance(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public synchronized void reload() {
        if (closed) return;
        Set<String> available = new HashSet<>();
        CraftEngineItems.loadedItems().keySet().forEach(key -> available.add(key.asString()));
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isLegacy()) available.add(material.getKey().toString());
        }
        AdvancementCatalog.Plan plan = catalog.plan(available,
                Set.copyOf(plugin.getConfig().getStringList("advancements.disabled")));
        State old = state;
        state = null;
        if (old != null) unregister(false);
        if (plan.definitions().isEmpty()) {
            plugin.getLogger().warning("成就入口所需物品未加载，跳过注册森罗厨房成就。");
            return;
        }
        if (api.isAdvancementTabRegistered(NAMESPACE)) {
            throw new IllegalStateException("Advancement namespace is already owned: " + NAMESPACE);
        }
        AdvancementTab tab = api.createAdvancementTab(NAMESPACE);
        try {
            Map<String, Advancement> nodes = new LinkedHashMap<>();
            Map<AdvancementCatalog.Match, Advancement> targets = new HashMap<>();
            Set<BaseAdvancement> children = new LinkedHashSet<>();
            Map<String, Integer> depths = new HashMap<>();
            Set<String> knives = new HashSet<>();
            int row = 0;
            for (AdvancementCatalog.Definition definition : plan.definitions().values()) {
                String id = definition.id();
                String parentId = plan.parents().get(id);
                Advancement parent = parentId == null ? null : nodes.get(parentId);
                int depth = parentId == null ? 0 : depths.get(parentId) + 1;
                depths.put(id, depth);
                String iconId = available.contains(definition.icon()) ? definition.icon()
                        : definition.criteria().values().stream().flatMap(c -> c.items().stream())
                        .filter(available::contains).findFirst().orElse("minecraft:paper");
                var display = new LocalizedAdvancementDisplay(icon(iconId), definition, depth, row++);
                Advancement node;
                if (parent == null) {
                    node = new RootAdvancement(tab, id, display, "minecraft:textures/block/bricks.png");
                } else if (definition.groups() > 1) {
                    MultiTasksAdvancement multi = new PersistedMultiTasksAdvancement(id, display, parent, definition.groups());
                    Set<TaskAdvancement> tasks = new LinkedHashSet<>();
                    for (int group = 0; group < definition.groups(); group++) {
                        TaskAdvancement task = new TaskAdvancement(id + "__group_" + group, multi);
                        targets.put(new AdvancementCatalog.Match(id, group), task);
                        tasks.add(task);
                    }
                    multi.registerTasks(tasks);
                    node = multi;
                } else if (definition.hidden()) {
                    node = new BaseAdvancement(id, display, parent) {
                        @Override
                        public boolean isVisible(TeamProgression progression) { return isGranted(progression); }
                    };
                } else {
                    node = new BaseAdvancement(id, display, parent);
                }
                if (definition.groups() == 1) targets.put(new AdvancementCatalog.Match(id, 0), node);
                nodes.put(id, node);
                if (node instanceof BaseAdvancement child) children.add(child);
                definition.criteria().values().stream().filter(c -> c.type().equals("kill"))
                        .forEach(c -> knives.addAll(c.items()));
            }
            register(tab, (RootAdvancement) nodes.get("root"), children);
            tab.automaticallyShowToPlayers();
            state = new State(tab, Map.copyOf(targets), AdvancementCatalog.index(plan, "inventory"),
                    AdvancementCatalog.index(plan, "event"), AdvancementCatalog.index(plan, "kill"), Set.copyOf(knives));
            plugin.getServer().getOnlinePlayers().forEach(this::loadPlayer);
            plugin.getLogger().info("已接入 UltimateAdvancementAPI：森罗厨房入口 + "
                    + (nodes.size() - 1) + " 项成就（模组共 37 项，隐藏 " + plan.excluded().size() + " 项）。");
            if (!plan.excluded().isEmpty()) plugin.getLogger().info("暂不注册的成就：" + plan.excluded());
        } catch (RuntimeException | LinkageError exception) {
            unregister(true);
            throw exception;
        }
    }

    private static ItemStack icon(String id) {
        if (id.startsWith("minecraft:")) {
            Material material = Material.matchMaterial(id);
            if (material == null) throw new IllegalArgumentException("Unknown icon " + id);
            return new ItemStack(material);
        }
        var item = InventoryUtils.createOrEmpty(Key.of(id));
        if (item.isEmpty()) throw new IllegalStateException("Missing custom icon " + id);
        return ItemStackUtils.getBukkitStack(item.minecraftItem());
    }

    private static void register(AdvancementTab tab, RootAdvancement root, Set<BaseAdvancement> children) {
        try {
            tab.getClass().getMethod("registerAdvancements", RootAdvancement.class, Set.class, boolean.class)
                    .invoke(tab, root, children, true);
        } catch (NoSuchMethodException exception) {
            tab.registerAdvancements(root, children);
        } catch (ReflectiveOperationException exception) {
            throw reflectionFailure(exception);
        }
    }

    private void unregister(boolean removeClient) {
        if (!api.isAdvancementTabRegistered(NAMESPACE)) return;
        if (api.getAdvancementTab(NAMESPACE).getOwningPlugin() != plugin) return;
        try {
            api.getClass().getMethod("unregisterAdvancementTab", String.class, boolean.class)
                    .invoke(api, NAMESPACE, removeClient);
        } catch (NoSuchMethodException exception) {
            api.unregisterAdvancementTab(NAMESPACE);
        } catch (ReflectiveOperationException exception) {
            throw reflectionFailure(exception);
        }
    }

    private static RuntimeException reflectionFailure(ReflectiveOperationException exception) {
        Throwable cause = exception instanceof InvocationTargetException ? exception.getCause() : exception;
        return new IllegalStateException("UltimateAdvancementAPI Pro extension failed", cause);
    }

    @Override
    public void recordEvent(Player player, String event) {
        State current = state;
        if (current != null) award(player, current.events().getOrDefault(event, List.of()));
    }

    private void award(Player player, List<AdvancementCatalog.Match> matches) {
        if (matches.isEmpty() || closed) return;
        FoliaUtil.runEntity(player, () -> awardOnPlayerThread(player, matches));
    }

    private synchronized void awardOnPlayerThread(Player player, List<AdvancementCatalog.Match> matches) {
        State current = state;
        if (current == null || closed || !player.isOnline()) return;
        if (!api.isLoaded(player)) {
            pending.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).addAll(matches);
            return;
        }
        for (AdvancementCatalog.Match match : matches) {
            // A third-party progression listener may rebuild the tree during a grant.
            State latest = state;
            if (latest == null) return;
            Advancement target = latest.targets().get(match);
            if (target != null && !target.isGranted(player)) target.grant(player);
        }
    }

    private void inspectItem(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        State current = state;
        if (current == null) return;
        Key customId = CraftEngineItems.getCustomItemId(stack);
        String id = customId == null ? stack.getType().getKey().toString() : customId.asString();
        award(player, current.inventory().getOrDefault(id, List.of()));
    }

    private void loadPlayer(Player player) {
        FoliaUtil.runEntity(player, () -> {
            State current = state;
            if (current == null || closed || !player.isOnline() || !api.isLoaded(player)) return;
            synchronized (this) {
                if (state != current || closed) return;
                current.tab().showTab(player);
            }
            for (ItemStack stack : player.getInventory().getContents()) inspectItem(player, stack);
            Set<AdvancementCatalog.Match> queued = pending.remove(player.getUniqueId());
            if (queued != null) award(player, new ArrayList<>(queued));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoaded(PlayerLoadingCompletedEvent event) { loadPlayer(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventory(PlayerInventorySlotChangeEvent event) { inspectItem(event.getPlayer(), event.getNewItemStack()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        State current = state;
        if (current == null) return;
        if (!event.willAttack()) {
            attacks.remove(event.getPlayer().getUniqueId());
            return;
        }
        // Capture the weapon on the attacker's region; a later weapon swap cannot affect the kill.
        ItemStack weapon = event.getPlayer().getInventory().getItemInMainHand();
        Key id = CraftEngineItems.getCustomItemId(weapon);
        boolean knife = (id != null && current.knives().contains(id.asString()))
                || ItemTags.instance().matches(Key.of("kaleidoscopecookery:kitchen_knife"), BukkitItemManager.instance().wrap(weapon));
        attacks.put(event.getPlayer().getUniqueId(), new Attack(event.getAttacked().getUniqueId(), knife, System.nanoTime()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        State current = state;
        if (killer == null || current == null || !(event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent damage)
                || damage.getDamager() != killer) return;
        String entityType = event.getEntity().getType().getKey().toString();
        List<AdvancementCatalog.Match> matches = current.kills().getOrDefault(entityType, List.of());
        if (matches.isEmpty()) return;
        Attack attack = attacks.get(killer.getUniqueId());
        if (attack != null && attack.knife() && System.nanoTime() - attack.time() < java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                && (attack.target().equals(event.getEntity().getUniqueId())
                || damage.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
            award(killer, matches);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
        attacks.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public synchronized void close() {
        closed = true;
        state = null;
        pending.clear();
        attacks.clear();
        HandlerList.unregisterAll(this);
        unregister(true);
    }
}
