package fr.deltabuild.deltaagent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Calls into classes of another loader, by name, for the tests on the real game classes. */
public final class Reflect {

    /**
     * Calls the static or instance method of that name, public or not; for an instance method the
     * first argument is the target.
     */
    public static Object call(Class<?> type, String name, Object... args) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                boolean isStatic = Modifier.isStatic(method.getModifiers());
                int count = isStatic ? args.length : args.length - 1;
                if (method.getParameterCount() != count) {
                    continue;
                }
                Object target = isStatic ? null : args[0];
                Object[] rest = isStatic ? args : Arrays.copyOfRange(args, 1, args.length);
                if (!fits(method.getParameterTypes(), rest)) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    return method.invoke(target, rest);
                } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    /** New instance through the constructor that fits the arguments. */
    public static Object create(Class<?> type, Object... args) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == args.length && fits(constructor.getParameterTypes(), args)) {
                constructor.setAccessible(true);
                try {
                    return constructor.newInstance(args);
                } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + ".<init>" + args.length);
    }

    private static boolean fits(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object arg = args[i];
            Class<?> type = types[i];
            if (arg == null) {
                if (type.isPrimitive()) {
                    return false;
                }
            } else if (type == int.class) {
                if (!(arg instanceof Integer)) {
                    return false;
                }
            } else if (type == short.class) {
                if (!(arg instanceof Short)) {
                    return false;
                }
            } else if (type == byte.class) {
                if (!(arg instanceof Byte)) {
                    return false;
                }
            } else if (type == boolean.class) {
                if (!(arg instanceof Boolean)) {
                    return false;
                }
            } else if (type.isPrimitive() || !type.isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private Reflect() {
    }
}
