package fr.deltabuild.deltaagent.core;

import org.objectweb.asm.tree.ClassNode;

/**
 * A change made to one game class, after FML's deobfuscation (MCP class names, SRG member names).
 */
public interface ClassPatch {

    /** Changes the class in place; returns how many places were changed, 0 if the expected code is missing. */
    int apply(ClassNode node);
}
