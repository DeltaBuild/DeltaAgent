package fr.deltabuild.deltaagent.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.deltabuild.deltaagent.core.PatchRegistry;
import fr.deltabuild.deltaagent.core.PatchTarget;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdsModuleTest {

    @AfterEach
    void clear() {
        System.clearProperty(IdsModule.OPTION);
        System.clearProperty(IdsModule.CAPACITY);
        System.clearProperty(IdsModule.WINDOW);
        Registries.reset();
        BlockWindow.configure(null);
    }

    @Test
    void onByDefault() {
        assertTrue(IdsModule.enabled());
        System.setProperty(IdsModule.OPTION, "false");
        assertFalse(IdsModule.enabled());
    }

    @Test
    void oldOffValueStillTurnsItOff() {
        System.setProperty(IdsModule.CAPACITY, "off");
        assertFalse(IdsModule.enabled());
    }

    @Test
    void startsTheRegistriesAtTheGivenSize() {
        System.setProperty(IdsModule.CAPACITY, "65536");
        IdsModule.start(new PatchRegistry());
        assertEquals(65536, Registries.blocks());
        assertEquals(65536, Registries.items());
        assertEquals(1, IdsModule.itemFormat());
    }

    @Test
    void registriesCoverTheBlockWindow() {
        System.setProperty(IdsModule.CAPACITY, "4096");
        IdsModule.start(new PatchRegistry());
        assertEquals(32768, Registries.blocks()); // 19475 must fit
    }

    @Test
    void registersItsGameClasses() {
        PatchRegistry registry = new PatchRegistry();
        IdsModule.start(registry);
        assertEquals(20, registry.size());
        assertTrue(registry.get("net.minecraft.block.Block").get(0).critical());
        assertTrue(registry.get("net.minecraft.item.ItemStack").get(0).critical());
        assertFalse(registry.get("org.spigotmc.AntiXray").get(0).critical());
    }

    @Test
    void offKeepsOnlyTheWorldGuard() {
        PatchRegistry registry = new PatchRegistry();
        IdsModule.guardOnly(registry);
        assertEquals(1, registry.size());
        List<PatchTarget> guard = registry.get("net.minecraft.world.storage.WorldInfo");
        assertNotNull(guard);
        assertTrue(guard.get(0).critical());
    }

    @Test
    void stepsAsideOnANationsGloryServer(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve(IdsModule.NG_EXTENSION);
        Files.createDirectories(marker.getParent());
        Files.write(marker, new byte[] {0});
        PatchRegistry registry = new PatchRegistry();
        registry.add(PatchTarget.patch("height", "net.minecraft.world.World", true, node -> 1));
        IdsModule.start(registry);
        try (URLClassLoader server = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            new LoaderChecks(registry).run(server);
        }
        assertEquals(1, registry.size());
        assertNull(registry.get("net.minecraft.item.ItemStack"));
        assertEquals(0, IdsModule.itemFormat());
    }

    @Test
    void staysOnElsewhere(@TempDir File dir) throws Exception {
        PatchRegistry registry = new PatchRegistry();
        IdsModule.start(registry);
        try (URLClassLoader server = new URLClassLoader(new URL[] {dir.toURI().toURL()}, null)) {
            new LoaderChecks(registry).run(server);
        }
        assertEquals(20, registry.size());
    }
}
