package fr.deltabuild.deltaagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URL;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;

class RuntimeClassesTest {

    @Test
    void theLaunchClassLoaderFindsTheRuntimeClassesItself() throws Exception {
        LaunchClassLoader loader = new LaunchClassLoader(new URL[0]);
        assertNull(loader.getClassBytes("fr.deltabuild.deltaagent.runtime.WorldFormats.Nothing"));
        RuntimeClasses.addTo(loader);
        byte[] bytes = loader.getClassBytes("fr.deltabuild.deltaagent.runtime.ItemIds");
        assertNotNull(bytes);
        assertEquals("fr/deltabuild/deltaagent/runtime/ItemIds", Asm.read(bytes).name);
        assertNotNull(loader.getClassBytes("fr.deltabuild.deltaagent.runtime.WorldFormats"));
        // Only the runtime package is there: the rest of the agent stays with the application loader.
        assertNull(loader.getClassBytes("fr.deltabuild.deltaagent.core.Log"));
    }
}
