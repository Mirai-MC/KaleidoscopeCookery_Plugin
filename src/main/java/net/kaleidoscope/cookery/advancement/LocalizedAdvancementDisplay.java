package net.kaleidoscope.cookery.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.inventory.ItemStack;

final class LocalizedAdvancementDisplay extends AdvancementDisplay {
    private final AdvancementCatalog.Definition definition;

    LocalizedAdvancementDisplay(ItemStack icon, AdvancementCatalog.Definition definition, float x, float y) {
        super(icon, definition.title(), AdvancementFrameType.valueOf(definition.frame().toUpperCase(java.util.Locale.ROOT)),
                definition.showToast(), definition.announceChat(), x, y, definition.description());
        this.definition = definition;
    }

    // Pro extension; deliberately also compiles against the public 2.8.1 API.
    public boolean usesComponentDisplay() { return true; }

    @Override
    public BaseComponent[] getChatTitle() {
        TranslatableComponent title = new TranslatableComponent(definition.titleKey());
        title.setFallback(definition.title());
        title.setColor(getFrame().getColor());
        return new BaseComponent[]{title};
    }

    @Override
    public BaseComponent[] getChatDescription() {
        TranslatableComponent description = new TranslatableComponent(definition.descriptionKey());
        description.setFallback(definition.description());
        return new BaseComponent[]{description};
    }
}
