package stone.mae2.util;

import net.minecraftforge.fml.ModList;

public class LoadedModsHelper {
    public static boolean isFork = ModList.get().getMods().stream().anyMatch(mod -> mod.getDisplayName().endsWith(": CL"));
}
