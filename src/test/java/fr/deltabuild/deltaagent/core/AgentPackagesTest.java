package fr.deltabuild.deltaagent.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentPackagesTest {

    @Test
    void launchClassLoaderLeavesAgentClassesToTheApplicationLoader() {
        assertTrue(excluded("fr.deltabuild.deltaagent.DeltaAgent"));
        assertTrue(excluded("fr.deltabuild.deltaagent.ids.legacy.IdCapacity"));
        assertTrue(excluded("fr.deltabuild.deltaagent.java17.shim.ReflectionFactory"));
        assertTrue(excluded("fr.deltabuild.deltaagent.shadow.asm.ClassReader"));
    }

    @Test
    void runtimePackageStaysWithTheLaunchClassLoader() {
        assertFalse(excluded("fr.deltabuild.deltaagent.runtime.Anything"));
    }

    @Test
    void recognizesAgentInternalNames() {
        assertTrue(AgentPackages.isAgentClass("fr/deltabuild/deltaagent/core/Log"));
        assertFalse(AgentPackages.isAgentClass("fr/deltabuild/deltaheight/DeltaHeightAgent"));
        assertFalse(AgentPackages.isAgentClass(null));
    }

    private static boolean excluded(String className) {
        for (String prefix : AgentPackages.launchClassLoaderExclusions()) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
