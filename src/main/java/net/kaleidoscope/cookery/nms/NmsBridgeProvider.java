package net.kaleidoscope.cookery.nms;

import org.bukkit.Bukkit;

public final class NmsBridgeProvider {
    private static final NmsBridge BRIDGE = create();

    private NmsBridgeProvider() {}

    public static NmsBridge bridge() {
        return BRIDGE;
    }

    private static NmsBridge create() {
        String version = minecraftVersion();
        String className = implementationClassName(version);
        try {
            return (NmsBridge) Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to load NMS bridge for " + version + ": " + className, e);
        }
    }

    private static String minecraftVersion() {
        String version = Bukkit.getBukkitVersion();
        int dash = version.indexOf('-');
        if (dash > 0) {
            version = version.substring(0, dash);
        }
        int build = version.indexOf(".build");
        if (build > 0) {
            version = version.substring(0, build);
        }
        return version;
    }

    private static String implementationClassName(String version) {
        if (version.startsWith("26.3")) return name("v26_3_R1", "NmsV26_3_R1");
        throw new IllegalStateException("Unsupported server version: " + version + "; only 26.3 is supported");
    }

    private static String name(String packageName, String className) {
        return "net.kaleidoscope.cookery.nms." + packageName + "." + className;
    }
}
