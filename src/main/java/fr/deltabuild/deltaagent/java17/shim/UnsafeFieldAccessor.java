package fr.deltabuild.deltaagent.java17.shim;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import sun.misc.Unsafe;

/**
 * Reads and writes a field through {@link Unsafe}, including static final fields, as
 * {@code sun.reflect.FieldAccessor} allowed on Java 8.
 */
@SuppressWarnings({"deprecation", "removal"})
final class UnsafeFieldAccessor implements FieldAccessor {

    private static final Unsafe UNSAFE;
    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Field field;
    private final boolean isStatic;
    private final Object staticBase;
    private final long offset;

    UnsafeFieldAccessor(Field field) {
        this.field = field;
        this.isStatic = Modifier.isStatic(field.getModifiers());
        if (isStatic) {
            this.staticBase = UNSAFE.staticFieldBase(field);
            this.offset = UNSAFE.staticFieldOffset(field);
        } else {
            this.staticBase = null;
            this.offset = UNSAFE.objectFieldOffset(field);
        }
    }

    private Object base(Object target) {
        if (isStatic) {
            return staticBase;
        }
        if (!field.getDeclaringClass().isInstance(target)) {
            throw new IllegalArgumentException("Cannot access field " + field + " on " + target);
        }
        return target;
    }

    private Class<?> type() {
        return field.getType();
    }

    @Override
    public Object get(Object target) {
        Object base = base(target);
        Class<?> type = type();
        if (!type.isPrimitive()) {
            return UNSAFE.getObjectVolatile(base, offset);
        } else if (type == boolean.class) {
            return UNSAFE.getBooleanVolatile(base, offset);
        } else if (type == byte.class) {
            return UNSAFE.getByteVolatile(base, offset);
        } else if (type == char.class) {
            return UNSAFE.getCharVolatile(base, offset);
        } else if (type == short.class) {
            return UNSAFE.getShortVolatile(base, offset);
        } else if (type == int.class) {
            return UNSAFE.getIntVolatile(base, offset);
        } else if (type == long.class) {
            return UNSAFE.getLongVolatile(base, offset);
        } else if (type == float.class) {
            return UNSAFE.getFloatVolatile(base, offset);
        } else {
            return UNSAFE.getDoubleVolatile(base, offset);
        }
    }

    @Override
    public void set(Object target, Object value) {
        Object base = base(target);
        Class<?> type = type();
        if (!type.isPrimitive()) {
            if (value != null && !type.isInstance(value)) {
                throw new IllegalArgumentException("Cannot set " + field + " to " + value.getClass().getName());
            }
            UNSAFE.putObjectVolatile(base, offset, value);
        } else if (value == null) {
            throw new IllegalArgumentException("Cannot set primitive field " + field + " to null");
        } else if (type == boolean.class) {
            UNSAFE.putBooleanVolatile(base, offset, (Boolean) value);
        } else if (type == byte.class) {
            UNSAFE.putByteVolatile(base, offset, (Byte) value);
        } else if (type == char.class) {
            UNSAFE.putCharVolatile(base, offset, (Character) value);
        } else if (type == short.class) {
            UNSAFE.putShortVolatile(base, offset, ((Number) value).shortValue());
        } else if (type == int.class) {
            UNSAFE.putIntVolatile(base, offset, value instanceof Character ? (Character) value : ((Number) value).intValue());
        } else if (type == long.class) {
            UNSAFE.putLongVolatile(base, offset, value instanceof Character ? (Character) value : ((Number) value).longValue());
        } else if (type == float.class) {
            UNSAFE.putFloatVolatile(base, offset, value instanceof Character ? (Character) value : ((Number) value).floatValue());
        } else {
            UNSAFE.putDoubleVolatile(base, offset, value instanceof Character ? (Character) value : ((Number) value).doubleValue());
        }
    }

    @Override
    public boolean getBoolean(Object target) {
        return (Boolean) get(target);
    }

    @Override
    public byte getByte(Object target) {
        return ((Number) get(target)).byteValue();
    }

    @Override
    public char getChar(Object target) {
        return (Character) get(target);
    }

    @Override
    public short getShort(Object target) {
        return ((Number) get(target)).shortValue();
    }

    @Override
    public int getInt(Object target) {
        Object value = get(target);
        return value instanceof Character ? (Character) value : ((Number) value).intValue();
    }

    @Override
    public long getLong(Object target) {
        Object value = get(target);
        return value instanceof Character ? (Character) value : ((Number) value).longValue();
    }

    @Override
    public float getFloat(Object target) {
        Object value = get(target);
        return value instanceof Character ? (Character) value : ((Number) value).floatValue();
    }

    @Override
    public double getDouble(Object target) {
        Object value = get(target);
        return value instanceof Character ? (Character) value : ((Number) value).doubleValue();
    }

    @Override
    public void setBoolean(Object target, boolean value) {
        set(target, value);
    }

    @Override
    public void setByte(Object target, byte value) {
        set(target, value);
    }

    @Override
    public void setChar(Object target, char value) {
        set(target, value);
    }

    @Override
    public void setShort(Object target, short value) {
        set(target, value);
    }

    @Override
    public void setInt(Object target, int value) {
        set(target, value);
    }

    @Override
    public void setLong(Object target, long value) {
        set(target, value);
    }

    @Override
    public void setFloat(Object target, float value) {
        set(target, value);
    }

    @Override
    public void setDouble(Object target, double value) {
        set(target, value);
    }
}
