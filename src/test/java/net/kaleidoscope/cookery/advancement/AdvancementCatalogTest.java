package net.kaleidoscope.cookery.advancement;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AdvancementCatalogTest {
    private final AdvancementCatalog catalog = AdvancementCatalog.load();

    private Set<String> allItems() {
        Set<String> items = new HashSet<>();
        catalog.advancements().forEach(d -> {
            items.add(d.icon());
            d.criteria().values().forEach(c -> items.addAll(c.items()));
        });
        return items;
    }

    @Test
    void preservesOfficialDefinitionsAndProvenance() {
        assertEquals(38, catalog.advancements().size()); // 37 achievements plus the root.
        assertEquals("1.6.0-forge+mc1.20.1", catalog.modVersion());
        assertEquals("2f4e386ce23f49a385ddf003c67fc6415c55417a", catalog.sourceCommit());
        assertEquals("森罗厨房", definition("root").title());
        assertEquals("咣！咣！咣！", definition("chopping_board").title());
        assertTrue(definition("dangerous_chef").hidden());
        assertEquals("challenge", definition("dangerous_chef").frame());
    }

    @Test
    void hidesOnlyUnportedMechanicsWhenAllItemsAreAvailable() {
        var plan = catalog.plan(allItems(), Set.of());
        assertEquals(33, plan.definitions().size());
        assertEquals(Set.of("baozi", "nitrogen_100", "nitrogen_300",
                "nitrogen_1000", "nitrogen_3000"), plan.excluded().keySet());
        assertEquals("dough", plan.parents().get("steamer"));
        assertEquals("dough", definition("steamer").parent());
    }

    @Test
    void keepsDarkCuisineAlternativesInOneTask() {
        var index = AdvancementCatalog.index(catalog.plan(allItems(), Set.of()), "inventory");
        var task = new AdvancementCatalog.Match("dark_cuisine", 0);
        assertTrue(index.get("kaleidoscopecookery:dark_cuisine").contains(task));
        assertTrue(index.get("kaleidoscopecookery:suspicious_stir_fry").contains(task));
        assertEquals(1, definition("dark_cuisine").groups());
        Set<String> items = allItems();
        items.remove("kaleidoscopecookery:suspicious_stir_fry");
        assertTrue(catalog.plan(items, Set.of()).definitions().containsKey("dark_cuisine"));
    }

    @Test
    void repeatedRedChiliCannotCompleteGreenChiliTask() {
        var index = AdvancementCatalog.index(catalog.plan(allItems(), Set.of()), "inventory");
        Set<AdvancementCatalog.Match> tasks = new HashSet<>();
        for (int acquisition = 0; acquisition < 10; acquisition++) {
            tasks.addAll(index.get("kaleidoscopecookery:red_chili"));
        }
        assertEquals(1, tasks.stream().filter(m -> m.advancement().equals("dual_chili")).count());
        tasks.addAll(index.get("kaleidoscopecookery:green_chili"));
        assertEquals(2, tasks.stream().filter(m -> m.advancement().equals("dual_chili")).count());
        assertEquals(2, definition("dual_chili").groups());
    }

    @Test
    void farmerSetHasFourDistinctAcquisitionTasks() {
        assertEquals(4, definition("farmer_set").groups());
        var index = AdvancementCatalog.index(catalog.plan(allItems(), Set.of()), "inventory");
        Set<AdvancementCatalog.Match> hat = index.get("kaleidoscopecookery:straw_hat").stream()
                .filter(m -> m.advancement().equals("farmer_set")).collect(Collectors.toSet());
        Set<AdvancementCatalog.Match> flowerHat = index.get("kaleidoscopecookery:straw_hat_flower").stream()
                .filter(m -> m.advancement().equals("farmer_set")).collect(Collectors.toSet());
        assertEquals(hat, flowerHat);
        Set<String> available = allItems();
        available.remove("kaleidoscopecookery:farmer_boots");
        var plan = catalog.plan(available, Set.of());
        assertFalse(plan.definitions().containsKey("farmer_set"));
        assertEquals("straw_hat", plan.parents().get("scarecrow"));
    }

    @Test
    void missingRootDisablesEntireTab() {
        Set<String> available = allItems();
        definition("root").criteria().values().forEach(c -> available.removeAll(c.items()));
        assertTrue(catalog.plan(available, Set.of()).definitions().isEmpty());
    }

    @Test
    void disabledParentsAreReplacedByNearestAvailableAncestor() {
        var plan = catalog.plan(allItems(), Set.of("pot", "add_oil"));
        assertEquals("stove", plan.parents().get("stir_fry"));
        List<String> order = List.copyOf(plan.definitions().keySet());
        plan.parents().forEach((child, parent) -> assertTrue(order.indexOf(parent) < order.indexOf(child)));
    }

    @Test
    void killCriteriaMatchOriginalSourcesAndKnives() {
        var kill = definition("oil").criteria().values().iterator().next();
        assertEquals(Set.of("minecraft:pig", "minecraft:piglin", "minecraft:piglin_brute", "minecraft:hoglin",
                "minecraft:zombified_piglin", "minecraft:zoglin"), Set.copyOf(kill.entities()));
        assertEquals(4, kill.items().size());
        assertTrue(kill.items().contains("kaleidoscopecookery:netherite_kitchen_knife"));
        var index = AdvancementCatalog.index(catalog.plan(allItems(), Set.of()), "kill");
        assertEquals(2, index.get("minecraft:piglin_brute").size());
        assertFalse(index.containsKey("minecraft:cow"));
    }

    @Test
    void eventBridgeIncludesPackMechanicsAndExcludesUnportedMechanics() {
        var index = AdvancementCatalog.index(catalog.plan(allItems(), Set.of()), "event");
        assertEquals(List.of(new AdvancementCatalog.Match("dough", 0)), index.get("pull_the_dough"));
        assertFalse(index.containsKey("meat_buns_beat_dogs"));
        assertEquals(List.of(new AdvancementCatalog.Match("fish_rice", 0)), index.get("place_fish_in_rice_field"));
        assertTrue(index.containsKey("drive_the_millstone"));
        assertEquals(List.of(new AdvancementCatalog.Match("stir_fry", 0)), index.get("stir_fry_in_pot"));
    }

    @Test
    void acquiringDoughOrNoodlesCannotSubstituteForPullingDough() {
        var index = AdvancementCatalog.index(catalog.plan(allItems(), Set.of()), "inventory");
        for (String item : List.of("kaleidoscopecookery:raw_dough", "kaleidoscopecookery:raw_noodles")) {
            assertTrue(index.getOrDefault(item, List.of()).stream().noneMatch(match -> match.advancement().equals("dough")));
        }
    }

    @Test
    void missingPackIngredientsHideTheirMechanicsAndReparentSteamer() {
        Set<String> items = allItems();
        items.remove("kaleidoscopecookery:raw_dough");
        items.remove("kaleidoscopecookery:wild_rice");
        var plan = catalog.plan(items, Set.of());
        assertFalse(plan.definitions().containsKey("dough"));
        assertFalse(plan.definitions().containsKey("fish_rice"));
        assertEquals("millstone", plan.parents().get("steamer"));
    }

    private AdvancementCatalog.Definition definition(String id) {
        return catalog.advancements().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }
}
