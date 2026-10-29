package fr.deltabuild.deltaagent.java17.shim;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Stands in for {@code sun.reflect.ReflectionFactory}, removed in Java 9. Forge's EnumHelper (and
 * similar code in MCPC+ and mods) uses it to create enum constants and write static final fields;
 * references to it are redirected here by {@link fr.deltabuild.deltaagent.java17.ClassPatcher}.
 */
public final class ReflectionFactory {

    private static final ReflectionFactory INSTANCE = new ReflectionFactory();

    public static ReflectionFactory getReflectionFactory() {
        return INSTANCE;
    }

    public ConstructorAccessor newConstructorAccessor(Constructor<?> constructor) {
        return new MethodHandleConstructorAccessor(constructor);
    }

    public FieldAccessor newFieldAccessor(Field field, boolean override) {
        return new UnsafeFieldAccessor(field);
    }

    private ReflectionFactory() {
    }
}
