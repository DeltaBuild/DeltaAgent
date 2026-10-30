package fr.deltabuild.deltaagent.height;

import java.util.List;

/**
 * Same loop shape as org.spigotmc.AntiXray.obfuscate, compiled by javac: the test patches its
 * bytecode and runs it to see which Y the patched loop visits.
 */
public class FakeAntiXray {

    /** Records the Y of the bottom of every section the mask lets through. */
    public void obfuscate(int bitmask, List<Integer> visited) {
        for (int i = 0; i < 16; ++i) {
            if ((bitmask & 1 << i) == 0) {
                continue;
            }
            visited.add(i << 4);
        }
    }
}
