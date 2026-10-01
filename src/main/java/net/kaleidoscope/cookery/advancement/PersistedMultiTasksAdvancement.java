package net.kaleidoscope.cookery.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.MultiTasksAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.tasks.TaskAdvancement;
import com.fren_gor.ultimateAdvancementAPI.database.TeamProgression;
import org.bukkit.entity.Player;

/** Persist the derived parent before UAA queues an offline completion/reward record. */
final class PersistedMultiTasksAdvancement extends MultiTasksAdvancement {
    PersistedMultiTasksAdvancement(String id, AdvancementDisplay display, Advancement parent, int groups) {
        super(id, display, parent, groups);
    }

    @Override
    protected void reloadTasks(TeamProgression progression, Player player, boolean giveRewards) {
        int total = 0;
        for (TaskAdvancement task : getTasks()) total += progression.getProgression(task);
        advancementTab.getDatabaseManager().updateProgression(getKey(), progression, total);
        super.reloadTasks(progression, player, giveRewards);
    }
}
