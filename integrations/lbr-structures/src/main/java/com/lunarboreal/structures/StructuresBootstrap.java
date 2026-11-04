package com.lunarboreal.structures;

import com.lunarboreal.structures.config.ConfigStore;
import com.lunarboreal.structures.nms.NativeRegistries;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class StructuresBootstrap implements PluginBootstrap {
    private ConfigStore store;
    private Exception configurationFailure;

    @Override public void bootstrap(BootstrapContext context) {
        var build = ServerBuildInfo.buildInfo();
        if (!build.minecraftVersionId().equals("26.3") || !build.brandId().equals(ServerBuildInfo.BRAND_PAPER_ID))
            throw new IllegalStateException("LunarCoreStructures requires Paper 26.3 / Java 25");
        try {
            store = new ConfigStore(context.getDataDirectory().resolve("structures.json"));
            com.lunarboreal.structures.retrofit.JobJournal.load(context.getDataDirectory().resolve("structure-backups"));
        }
        catch (Exception e) { configurationFailure = e; }
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            if (configurationFailure != null) throw new IllegalStateException("Cannot read structure settings or recovery journal", configurationFailure);
            NativeRegistries.register();
            try { event.registrar().discoverPack(getClass().getResource("/yungs").toURI(), "yungs"); }
            catch (Exception e) { throw new IllegalStateException("Cannot discover YUNG structure resources", e); }
        });
    }

    @Override public JavaPlugin createPlugin(PluginProviderContext context) {
        return new StructuresPlugin(store);
    }
}
