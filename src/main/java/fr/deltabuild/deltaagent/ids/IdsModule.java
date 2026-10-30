package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Log;
import fr.deltabuild.deltaagent.core.Options;
import fr.deltabuild.deltaagent.core.PatchRegistry;

/**
 * Block and item IDs up to 2^24, as NationsGlory has them: registries that grow on demand, item IDs
 * above 31999 saved and sent with an "idExt" int, chunks that store 24-bit block IDs, 16-bit
 * metadata and 16-bit biomes and send them to NGClient.
 */
public final class IdsModule {

    public static final String OPTION = "deltaagent.ids";
    public static final String CAPACITY = "deltaagent.ids.capacity";
    static final String OLD_CAPACITY = "mcpcj17.idCapacity";
    public static final String WINDOW = "deltaagent.ids.blockWindow";
    /** NGClient's name for the same window. */
    static final String NG_WINDOW = "ng.blocs.fenetre";
    public static final String FORGET = "deltaagent.ids.forgetWorldFormat";
    /** NationsGlory's name for the same option. */
    static final String NG_FORGET = "ngid.formatmonde.oublier";
    static final String NG_EXTENSION = "fr/nationsglory/ngid/NGCapaciteIds.class";

    private static volatile boolean active;
    private static volatile boolean forget;

    public static boolean enabled() {
        String capacity = System.getProperty(CAPACITY);
        if (capacity != null && capacity.trim().equalsIgnoreCase("off")) {
            Log.info("ids", "-D" + CAPACITY + "=off still works, but it is now -D" + OPTION + "=false.");
            return false;
        }
        return Options.flag(OPTION, true);
    }

    public static void start(PatchRegistry registry) {
        if (System.getProperty(OLD_CAPACITY) != null) {
            Log.info("ids", "-D" + OLD_CAPACITY + " is no longer read: the registries grow on their own, and -D"
                    + CAPACITY + " sets their starting size.");
        }
        int capacity = IdCapacity.parse(System.getProperty(CAPACITY));
        BlockWindow.configure(Options.get(WINDOW, NG_WINDOW));
        if (BlockWindow.max() >= capacity) {
            capacity = IdCapacity.covering(BlockWindow.max(), capacity);
            Log.info("ids", "Registries start at " + capacity + " to cover the block window.");
        }
        Registries.configure(capacity);
        forget = Options.flag(FORGET, false, NG_FORGET);
        if (forget) {
            Log.warn("ids", "The ID formats worlds declare in level.dat are not kept this time (-D" + FORGET
                    + "=true): they are written again only for what is saved. Remove the option after this start.");
        }
        IdPatches.register(registry);
        StoragePatches.register(registry);
        BiomePatches.register(registry);
        registry.addLoaderCheck(IdsModule::leaveOutOnNationsGloryServers);
        active = true;
        Log.info("ids", "Block and item registries start at " + capacity + " IDs and grow up to " + IdCapacity.LIMIT
                + ". Chunks store 24-bit block IDs (Add2, BlocksB3), 16-bit metadata (DataAdd, Data2) and 16-bit biomes (Biomes16), items above 31999 use idExt.");
    }

    /** Module off: vanilla limits, but worlds that need it are still refused. */
    public static void guardOnly(PatchRegistry registry) {
        active = false;
        Log.info("ids", "Vanilla ID limits kept (-D" + OPTION + "=false): blocks 0-4095, items 0-31999.");
        IdPatches.registerGuard(registry);
    }

    /** A server with its own large ID support (NGServer) keeps it, as with mcpc-java17. */
    private static void leaveOutOnNationsGloryServers(ClassLoader launchClassLoader, PatchRegistry registry) {
        if (launchClassLoader.getResource(NG_EXTENSION) != null) {
            int removed = registry.removeModule("ids");
            active = false;
            Log.info("ids", "The server has its own large ID support: ids module left out (" + removed + " patches).");
        }
    }

    /** Highest NGItemIdFormat this server can load: 1 is idExt. */
    public static int itemFormat() {
        return active ? 1 : 0;
    }

    /** Whether the format a world declares is dropped instead of kept, as NG's ngid.formatmonde.oublier. */
    public static boolean forgetWorldFormat() {
        return forget;
    }

    /** Highest NGBlockIdFormat this server can load: 3 is Add2, palette sections and BlocksB3, 4 adds 16-bit metadata, 5 16-bit biomes. */
    public static int blockFormat() {
        return active ? 5 : 0;
    }

    private IdsModule() {
    }
}
