package fr.deltabuild.deltaagent.java17.shim;

/**
 * Stands in for {@code sun.reflect.FieldAccessor}.
 */
public interface FieldAccessor {

    Object get(Object target) throws IllegalArgumentException;

    boolean getBoolean(Object target) throws IllegalArgumentException;

    byte getByte(Object target) throws IllegalArgumentException;

    char getChar(Object target) throws IllegalArgumentException;

    short getShort(Object target) throws IllegalArgumentException;

    int getInt(Object target) throws IllegalArgumentException;

    long getLong(Object target) throws IllegalArgumentException;

    float getFloat(Object target) throws IllegalArgumentException;

    double getDouble(Object target) throws IllegalArgumentException;

    void set(Object target, Object value) throws IllegalArgumentException, IllegalAccessException;

    void setBoolean(Object target, boolean value) throws IllegalArgumentException, IllegalAccessException;

    void setByte(Object target, byte value) throws IllegalArgumentException, IllegalAccessException;

    void setChar(Object target, char value) throws IllegalArgumentException, IllegalAccessException;

    void setShort(Object target, short value) throws IllegalArgumentException, IllegalAccessException;

    void setInt(Object target, int value) throws IllegalArgumentException, IllegalAccessException;

    void setLong(Object target, long value) throws IllegalArgumentException, IllegalAccessException;

    void setFloat(Object target, float value) throws IllegalArgumentException, IllegalAccessException;

    void setDouble(Object target, double value) throws IllegalArgumentException, IllegalAccessException;
}
