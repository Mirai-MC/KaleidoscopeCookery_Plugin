package net.kaleidoscope.cookery.nms.v26_3_R1;

import net.kaleidoscope.cookery.nms.NmsBridge;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import org.bukkit.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class NmsV26_3_R1 implements NmsBridge {
    private static final String CLIENT_COMMAND_PACKET = "net.minecraft.network.protocol.game.ServerboundClientCommandPacket";
    private static final String SPECTATOR_ACTION_PACKET = "net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket";
    private static final String TELEPORT_TO_ENTITY_PACKET = "net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket";
    private static final Object LIT_PROPERTY = staticField(
            "net.minecraft.world.level.block.state.properties.BlockStateProperties", "LIT");

    @Override
    public Object litBlockStateProperty() {
        return LIT_PROPERTY;
    }

    @Override
    public Object bukkitEntity(Object nmsEntity) {
        return EntityProxy.CLASS.isInstance(nmsEntity)
                ? EntityProxy.INSTANCE.getBukkitEntity(nmsEntity)
                : null;
    }

    @Override
    public Object nmsHandle(Entity entity) {
        return CraftEntityProxy.INSTANCE.getEntity(entity);
    }

    @Override
    public boolean isSpectatePacket(Object packet) {
        return isInstance(TELEPORT_TO_ENTITY_PACKET, packet)
                || isInstance(SPECTATOR_ACTION_PACKET, packet);
    }

    @Override
    public boolean isPerformRespawnPacket(Object packet) {
        if (!isInstance(CLIENT_COMMAND_PACKET, packet)) {
            return false;
        }
        try {
            Method getAction = packet.getClass().getMethod("getAction");
            return "PERFORM_RESPAWN".equals(String.valueOf(getAction.invoke(packet)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read client command action", e);
        }
    }

    private static boolean isInstance(String className, Object value) {
        try {
            return Class.forName(className).isInstance(value);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Object staticField(String className, String fieldName) {
        try {
            Field field = Class.forName(className).getField(fieldName);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to access " + className + "." + fieldName, e);
        }
    }
}
