import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.TaskAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.MultiTasksAdvancement;
import com.fren_gor.ultimateAdvancementAPI.database.DatabaseManager;
import com.fren_gor.ultimateAdvancementAPI.database.IDatabase;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import com.fren_gor.ultimateAdvancementAPI.database.OrderedDatabaseExecutor;
import com.google.gson.GsonBuilder;
import net.kaleidoscope.cookery.advancement.AdvancementCatalog;
import net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Isolated Paper + UAA Pro fixture. Run three times against the same SQLite database. */
public final class AdvancementProbe extends JavaPlugin implements Listener {
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final UUID uuid = UUID.nameUUIDFromBytes("KC isolated advancement verification".getBytes(StandardCharsets.UTF_8));
    private UltimateAdvancementAPI api;
    private KaleidoscopeCookeryPlugin cookery;
    private int phase;
    private OrderedDatabaseExecutor actor;
    private IDatabase database;
    private TeamProgression team;
    private boolean waitingForReload;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                GameplayProbe.verify(this, report, () -> {
                    try { start(); } catch (Throwable error) { finish(error); }
                }, this::finish);
            } catch (Throwable error) { finish(error); }
        }, 100);
    }

    private void start() throws Exception {
        phase = Files.exists(Path.of("advancement-phase.txt"))
                ? Integer.parseInt(Files.readString(Path.of("advancement-phase.txt")).trim()) : 1;
        report.put("phase", phase);
        report.put("minecraft", Bukkit.getMinecraftVersion());
        check("UltimateAdvancementAPI Pro enabled", Bukkit.getPluginManager().isPluginEnabled("UltimateAdvancementAPI"));
        check("Cookery enabled", Bukkit.getPluginManager().isPluginEnabled("KaleidoscopeCookeryPlugin"));
        report.put("apiVersion", Bukkit.getPluginManager().getPlugin("UltimateAdvancementAPI").getDescription().getVersion());
        api = UltimateAdvancementAPI.getInstance(this);
        cookery = (KaleidoscopeCookeryPlugin) Bukkit.getPluginManager().getPlugin("KaleidoscopeCookeryPlugin");
        inspectTree();
        DatabaseManager database = tab().getDatabaseManager();
        Field actorField = DatabaseManager.class.getDeclaredField("databaseExecutor");
        actorField.setAccessible(true);
        actor = (OrderedDatabaseExecutor) actorField.get(database);
        Field databaseField = DatabaseManager.class.getDeclaredField("database");
        databaseField.setAccessible(true);
        this.database = (IDatabase) databaseField.get(database);
        Method load = DatabaseManager.class.getDeclaredMethod("loadOrRegisterPlayer", UUID.class, String.class);
        load.setAccessible(true);
        CompletableFuture<TeamProgression> loaded = new CompletableFuture<>();
        actor.execute(() -> {
            try {
                @SuppressWarnings("unchecked")
                var entry = (Map.Entry<TeamProgression, Boolean>) load.invoke(database, uuid, "KCProbe");
                loaded.complete(entry.getKey());
            } catch (Throwable error) { loaded.completeExceptionally(error); }
        });
        loaded.whenComplete((team, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) { finish(error); return; }
            try {
                this.team = team;
                verifyProgression(team);
                if (phase == 3) {
                    waitingForReload = true;
                    check("real CE reload command dispatched", Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all"));
                } else checkDatabase();
            } catch (Throwable failure) { finish(failure); }
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReload(CraftEngineReloadEvent event) {
        if (!waitingForReload || event.isFirstReload()) return;
        waitingForReload = false;
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                inspectTree();
                check("completed goals survive full CE reload", advancement("dual_chili").isGranted(uuid)
                        && advancement("farmer_set").isGranted(uuid));
                checkDatabase();
            } catch (Throwable error) { finish(error); }
        });
    }

    private void checkDatabase() {
        Advancement dual = advancement("dual_chili");
        Advancement farmer = advancement("farmer_set");
        actor.execute(() -> {
            try {
                TeamProgression stored = database.loadUUID(uuid);
                check("parent progress stored in real SQLite", stored.getProgression(dual) == (phase == 1 ? 1 : 2)
                        && stored.getProgression(farmer) == (phase == 1 ? 1 : 4));
                if (phase >= 2) {
                    var unredeemed = database.getUnredeemed(team.getTeamId());
                    check("offline records follow parent writes", unredeemed.stream().anyMatch(record -> record.getKey().equals(dual.getKey()))
                            && unredeemed.stream().anyMatch(record -> record.getKey().equals(farmer.getKey())));
                }
                Bukkit.getScheduler().runTask(this, () -> finish(null));
            } catch (Throwable error) { Bukkit.getScheduler().runTask(this, () -> finish(error)); }
        });
    }

    private void inspectTree() throws Exception {
        check("tab initialized", tab() != null && tab().isInitialised());
        check("33 visible definitions", tab().getAdvancements().stream().filter(a -> !(a instanceof TaskAdvancement)).count() == 33);
        check("six persisted AND tasks", tab().getAdvancements().stream().filter(a -> a instanceof MultiTasksAdvancement)
                .mapToInt(a -> ((MultiTasksAdvancement) a).getTasks().size()).sum() == 6);
        for (String unsupported : List.of("baozi", "nitrogen_100", "nitrogen_300", "nitrogen_1000", "nitrogen_3000")) {
            check("unported hidden " + unsupported, api.getAdvancement("kaleidoscopecookery", unsupported) == null);
        }
        check("steamer keeps original dough parent", ((BaseAdvancement) advancement("steamer")).getParent() == advancement("dough"));
        check("fish rice keeps original rice parent", ((BaseAdvancement) advancement("fish_rice")).getParent() == advancement("rice_panicle"));
        Set<String> positions = new HashSet<>();
        for (Advancement node : tab().getAdvancements()) {
            if (node instanceof TaskAdvancement) continue;
            check("custom item icon " + node.getKey().getKey(), !node.getDisplay().getIcon().isEmpty());
            check("unique layout " + node.getKey().getKey(), positions.add(node.getDisplay().getX() + ":" + node.getDisplay().getY()));
        }
        var display = advancement("root").getDisplay();
        check("Pro component display", display.usesComponentDisplay());
        var title = display.getChatTitle()[0];
        check("localized title and Chinese fallback", title instanceof TranslatableComponent translated
                && translated.getTranslate().equals("advancements.kaleidoscope_cookery.root.title")
                && translated.getFallback().equals("森罗厨房"));
        check("native component wrapper", display.getNMSWrapper(advancement("root")) != null);
        check("native localized toast wrapper", display.getToastNMSWrapper() != null);
        report.put("modSource", AdvancementCatalog.load().sourceCommit());
    }

    private void verifyProgression(TeamProgression team) throws Exception {
        check("root not granted on join", !advancement("root").isGranted(uuid));
        if (phase == 1) {
            check("new dual chili starts empty", advancement("dual_chili").getProgression(uuid) == 0);
            for (int repeat = 0; repeat < 10; repeat++) advancement("dual_chili__group_0").setProgression(uuid, 1, false);
            check("duplicates cannot finish dual chili", advancement("dual_chili").getProgression(uuid) == 1 && !advancement("dual_chili").isGranted(uuid));
            advancement("farmer_set__group_0").setProgression(uuid, 1, false);
            check("partial farmer set", advancement("farmer_set").getProgression(uuid) == 1);
            check("dangerous chef hidden before completion", !advancement("dangerous_chef").isVisible(team));
            cookery.reloadAdvancements();
            check("partial tasks survive tree rebuild", advancement("dual_chili").getProgression(uuid) == 1);
            cookery.getConfig().set("advancements.enabled", false);
            cookery.reloadAdvancements();
            check("config disable removes owned tab", !api.isAdvancementTabRegistered("kaleidoscopecookery"));
            cookery.getConfig().set("advancements.enabled", true);
            cookery.reloadAdvancements();
            check("reenable preserves partial tasks", advancement("farmer_set").getProgression(uuid) == 1);
            cookery.getConfig().set("advancements.disabled", List.of("pot", "add_oil"));
            cookery.reloadAdvancements();
            check("disabled parents reparent surviving branch", ((BaseAdvancement) advancement("stir_fry")).getParent() == advancement("stove"));
            cookery.getConfig().set("advancements.disabled", List.of());
            cookery.reloadAdvancements();
            inspectTree();
        } else if (phase == 2) {
            check("dual chili partial task persisted across restart", advancement("dual_chili").getProgression(uuid) == 1);
            check("farmer partial task persisted across restart", advancement("farmer_set").getProgression(uuid) == 1);
            advancement("dual_chili__group_1").setProgression(uuid, 1, false);
            check("second distinct chili completes goal", advancement("dual_chili").isGranted(uuid));
            for (int group = 1; group < 4; group++) advancement("farmer_set__group_" + group).setProgression(uuid, 1, false);
            check("four distinct farmer tasks complete goal", advancement("farmer_set").isGranted(uuid));
            advancement("dangerous_chef").setProgression(uuid, 1, false);
            check("hidden challenge appears after completion", advancement("dangerous_chef").isVisible(team));
        } else {
            check("completed dual chili persisted", advancement("dual_chili").getProgression(uuid) == 2);
            check("completed farmer set persisted", advancement("farmer_set").getProgression(uuid) == 4);
            check("completed hidden challenge persisted", advancement("dangerous_chef").isGranted(uuid));
        }
    }

    private Advancement advancement(String key) {
        Advancement advancement = api.getAdvancement("kaleidoscopecookery", key);
        if (advancement == null && key.contains("__group_")) {
            MultiTasksAdvancement multi = (MultiTasksAdvancement) api.getAdvancement("kaleidoscopecookery", key.split("__group_")[0]);
            advancement = multi.getTasks().stream().filter(task -> task.getKey().getKey().equals(key)).findFirst().orElse(null);
        }
        if (advancement == null) throw new AssertionError("Missing advancement " + key);
        return advancement;
    }

    private AdvancementTab tab() { return api.getAdvancementTab("kaleidoscopecookery"); }

    private void check(String name, boolean success) {
        if (!success) throw new AssertionError(name);
        report.put(name, true);
    }

    private void finish(Throwable error) {
        try {
            report.put("success", error == null);
            if (error != null) {
                report.put("error", error.toString());
                error.printStackTrace();
            } else Files.writeString(Path.of("advancement-phase.txt"), Integer.toString(phase + 1));
            Files.writeString(Path.of("advancement-probe-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            getLogger().info("ADVANCEMENT_PROBE_" + (error == null ? "SUCCESS" : "FAILED") + " phase=" + phase);
        } catch (Exception failure) { failure.printStackTrace(); }
    }
}
