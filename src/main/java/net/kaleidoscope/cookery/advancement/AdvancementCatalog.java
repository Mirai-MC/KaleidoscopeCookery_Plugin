package net.kaleidoscope.cookery.advancement;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mod criteria: requirement groups are AND, alternatives inside each group are OR. */
public record AdvancementCatalog(String source, String sourceCommit, String modVersion,
                                 List<Definition> advancements) {
    public record Criterion(String type, List<String> items, List<String> entities, String event) {
        boolean available(Set<String> registeredItems) {
            return items.isEmpty() || items.stream().anyMatch(registeredItems::contains);
        }
    }

    public record Definition(String id, String parent, String icon, String frame, boolean hidden,
                             boolean showToast, boolean announceChat,
                             String titleKey, String title, String descriptionKey, String description,
                             String unsupportedReason, Map<String, Criterion> criteria,
                             List<List<String>> requirements) {
        public int groups() { return requirements.size(); }
    }

    public record Match(String advancement, int group) {}
    public record Plan(Map<String, Definition> definitions, Map<String, String> parents,
                       Map<String, String> excluded) {}

    public static AdvancementCatalog load() {
        InputStream input = AdvancementCatalog.class.getResourceAsStream("/advancements/kaleidoscope.json");
        if (input == null) throw new IllegalStateException("Missing advancement catalog");
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            AdvancementCatalog catalog = new Gson().fromJson(reader, AdvancementCatalog.class);
            catalog.validate();
            return catalog;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read advancement catalog", e);
        }
    }

    private void validate() {
        Map<String, Definition> all = new LinkedHashMap<>();
        for (Definition definition : advancements) {
            if (all.put(definition.id(), definition) != null || definition.groups() == 0) {
                throw new IllegalArgumentException("Invalid advancement " + definition.id());
            }
            for (List<String> group : definition.requirements()) {
                if (group.isEmpty() || !definition.criteria().keySet().containsAll(group)) {
                    throw new IllegalArgumentException("Invalid requirements for " + definition.id());
                }
            }
        }
        if (!all.containsKey("root")) throw new IllegalArgumentException("Missing root");
        for (Definition definition : advancements) {
            Set<String> path = new LinkedHashSet<>();
            for (Definition current = definition; current.parent() != null; current = all.get(current.parent())) {
                if (!path.add(current.id()) || !all.containsKey(current.parent())) {
                    throw new IllegalArgumentException("Invalid parent graph for " + definition.id());
                }
            }
        }
    }

    public Plan plan(Set<String> registeredItems, Set<String> disabled) {
        Map<String, Definition> all = new LinkedHashMap<>();
        Map<String, Definition> active = new LinkedHashMap<>();
        Map<String, String> excluded = new LinkedHashMap<>();
        for (Definition definition : advancements) {
            all.put(definition.id(), definition);
            String reason = definition.unsupportedReason();
            if (disabled.contains(definition.id())) reason = "disabled in config";
            if (reason == null && definition.requirements().stream().anyMatch(group -> group.stream()
                    .noneMatch(name -> definition.criteria().get(name).available(registeredItems)))) {
                reason = "missing required items";
            }
            if (reason == null) active.put(definition.id(), definition);
            else excluded.put(definition.id(), reason);
        }
        if (!active.containsKey("root")) {
            active.keySet().forEach(id -> excluded.put(id, "root unavailable"));
            return new Plan(Map.of(), Map.of(), Map.copyOf(excluded));
        }
        Map<String, String> parents = new LinkedHashMap<>();
        for (Definition definition : active.values()) {
            String parent = definition.parent();
            while (parent != null && !active.containsKey(parent)) parent = all.get(parent).parent();
            if (parent != null) parents.put(definition.id(), parent);
        }
        Map<String, Definition> sorted = new LinkedHashMap<>();
        active.keySet().forEach(id -> addInParentOrder(id, active, parents, sorted));
        return new Plan(java.util.Collections.unmodifiableMap(sorted), Map.copyOf(parents), Map.copyOf(excluded));
    }

    private static void addInParentOrder(String id, Map<String, Definition> active,
                                         Map<String, String> parents, Map<String, Definition> sorted) {
        if (sorted.containsKey(id)) return;
        String parent = parents.get(id);
        if (parent != null) addInParentOrder(parent, active, parents, sorted);
        sorted.put(id, active.get(id));
    }

    /** Each signal maps to a persisted task, so repeated acquisitions cannot finish another group. */
    public static Map<String, List<Match>> index(Plan plan, String type) {
        Map<String, Set<Match>> index = new LinkedHashMap<>();
        for (Definition definition : plan.definitions().values()) {
            for (int group = 0; group < definition.groups(); group++) {
                Match match = new Match(definition.id(), group);
                for (String name : definition.requirements().get(group)) {
                    Criterion criterion = definition.criteria().get(name);
                    if (!type.equals(criterion.type())) continue;
                    List<String> signals = switch (type) {
                        case "inventory" -> criterion.items();
                        case "kill" -> criterion.entities();
                        case "event" -> List.of(criterion.event());
                        default -> List.of();
                    };
                    signals.forEach(signal -> index.computeIfAbsent(signal, ignored -> new LinkedHashSet<>()).add(match));
                }
            }
        }
        Map<String, List<Match>> result = new LinkedHashMap<>();
        index.forEach((key, matches) -> result.put(key, List.copyOf(matches)));
        return Map.copyOf(result);
    }
}
