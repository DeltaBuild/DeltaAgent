package fr.deltabuild.deltaagent.height;

import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.Options;
import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.PatchTarget;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;

/**
 * World from Y -64 to Y 319 (24 sections), with the NGClient protocol. Ported from DeltaHeight 1.0.0,
 * plus the AntiXray patch and per world superflat settings.
 */
public final class HeightModule {

    public static final String OPTION = "deltaagent.height";
    static final String LEGACY_VIEW = "deltaagent.height.legacyView";
    private static final String CHUNK_RESOURCE = "Chunk.class.bin";

    public static boolean enabled() {
        return Options.flag(OPTION, true);
    }

    public static void start(Instrumentation inst, PatchRegistry registry) {
        Log.info("height", "World extended to Y -64..319 (24 sections), NGClient protocol (NGNEID marker).");
        registerTargets(registry);
        inst.addTransformer(new HeightJvmTransformer());
    }

    /** The game classes this module changes. Public for the bench, which compares them with DeltaHeight. */
    public static void registerTargets(PatchRegistry registry) {
        // Storage
        registry.add(PatchTarget.replace("height", "net.minecraft.world.chunk.Chunk", chunkClass()));
        add(registry, "net.minecraft.world.chunk.storage.AnvilChunkLoader", true, new Patches.AnvilChunkLoaderPatch());
        // World bounds
        add(registry, "net.minecraft.world.World", true, new Patches.WorldPatch());
        add(registry, "net.minecraft.world.WorldProvider", true, new Patches.WorldProviderPatch());
        add(registry, "net.minecraft.world.ChunkCache", false, new Patches.BoundsPatch(true));
        add(registry, "net.minecraft.world.Explosion", false, new Patches.BoundsPatch(false));
        add(registry, "net.minecraft.entity.item.EntityFallingSand", false, new Patches.BoundsPatch(false));
        add(registry, "net.minecraft.block.BlockMushroom", false, new Patches.BoundsPatch(false));
        add(registry, "net.minecraft.block.BlockLilyPad", false, new Patches.BoundsPatch(false));
        add(registry, "net.minecraft.block.BlockVine", false, new Patches.BoundsPatch(false));
        add(registry, "net.minecraft.block.BlockDoor", false, new Patches.BoundsPatch(false));
        add(registry, "net.minecraft.item.ItemBlock", false, new Patches.BoundsPatch(false));
        add(registry, "net.minecraft.server.dedicated.DedicatedServer", false, new Patches.BuildLimitPatch());
        add(registry, "net.minecraft.entity.Entity", false, new Patches.VoidPatch());
        add(registry, "net.minecraft.entity.item.EntityMinecart", false, new Patches.VoidPatch());
        // Network (NGClient)
        add(registry, "net.minecraft.network.packet.Packet51MapChunk", true, new Patches.Packet51Patch());
        add(registry, "net.minecraft.network.packet.Packet56MapChunks", true, new Patches.Packet56Patch());
        add(registry, "net.minecraft.network.packet.Packet14BlockDig", true, new Patches.BlockYShortPatch());
        add(registry, "net.minecraft.network.packet.Packet15Place", true, new Patches.BlockYShortPatch());
        add(registry, "net.minecraft.network.packet.Packet53BlockChange", true, new Patches.BlockYShortPatch());
        add(registry, "net.minecraft.network.packet.Packet61DoorChange", false, new Patches.Packet61Patch());
        add(registry, "net.minecraft.server.management.ServerConfigurationManager", true, new Patches.MarkerPatch());
        add(registry, "net.minecraft.server.management.PlayerInstance", true, new Patches.PlayerInstancePatch());
        // Generators that work on 16 sections
        add(registry, "net.minecraft.world.gen.ChunkProviderFlat", true, new Patches.FlatProviderPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.generator.CustomChunkGenerator", true, new Patches.LegacyViewPatch());
        add(registry, "fr.nationsglory.ngupgrades.common.utils.ChunkHelper", false, new Patches.LegacyViewPatch());
        add(registry, "calclavia.lib.utility.MovementUtility", false, new Patches.LegacyViewPatch());
        String extra = Options.get(LEGACY_VIEW, "deltaheight.legacyView");
        if (extra != null) {
            for (String name : extra.split(",")) {
                if (!name.trim().isEmpty()) {
                    add(registry, name.trim(), false, new Patches.LegacyViewPatch());
                }
            }
        }
        // CraftBukkit and Spigot
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.CraftWorld", false, new Patches.CraftWorldPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.CraftChunk", false, new Patches.CraftChunkPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.block.CraftBlock", false, new Patches.CraftBlockPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.CraftChunkSnapshot", false, new Patches.ChunkSnapshotPatch());
        add(registry, "org.bukkit.craftbukkit.v1_6_R3.event.CraftEventFactory", false, new Patches.InteractEventPatch());
        add(registry, "org.spigotmc.AntiXray", false, new Patches.AntiXrayPatch());
    }

    private static void add(PatchRegistry registry, String className, boolean critical, Patches.Patch0 patch) {
        registry.add(PatchTarget.patch("height", className, critical, patch));
    }

    /** The replacement Chunk, compiled from src/replace/java to Java 6 bytecode by the build. */
    static byte[] chunkClass() {
        try (InputStream in = HeightModule.class.getResourceAsStream(CHUNK_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + CHUNK_RESOURCE);
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("[DeltaAgent/height] Cannot read the replacement Chunk: " + e, e);
        }
    }

    private HeightModule() {
    }
}
