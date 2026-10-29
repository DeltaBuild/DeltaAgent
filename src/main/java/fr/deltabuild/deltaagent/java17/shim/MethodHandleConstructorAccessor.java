package fr.deltabuild.deltaagent.java17.shim;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Calls a constructor through a method handle, which, unlike {@code Constructor.newInstance},
 * also works for enum constructors.
 */
final class MethodHandleConstructorAccessor implements ConstructorAccessor {

    private final MethodHandle handle;

    MethodHandleConstructorAccessor(Constructor<?> constructor) {
        try {
            handle = MethodHandles.privateLookupIn(constructor.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectConstructor(constructor);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access " + constructor, e);
        }
    }

    @Override
    public Object newInstance(Object[] args) throws InvocationTargetException {
        try {
            return handle.invokeWithArguments(args == null ? new Object[0] : args);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(e);
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }
}
