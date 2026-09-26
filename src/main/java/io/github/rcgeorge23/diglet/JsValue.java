package io.github.rcgeorge23.diglet;

/**
 * The result of evaluating a JavaScript expression with {@link WebTest#evaluateScript(String)}.
 *
 * <p>Wraps the underlying JavaScript value so that it can be inspected and coerced without
 * depending on the JavaScript engine used to run the page.</p>
 */
public final class JsValue {

    private final Object value;

    JsValue(Object value) {
        this.value = value;
    }

    public boolean isNull() {
        return value == null;
    }

    public boolean isString() {
        return value instanceof String;
    }

    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public String asString() {
        if (value == null) {
            return "null";
        }
        return (String) value;
    }

    public int asInt() {
        return ((Number) value).intValue();
    }

    public long asLong() {
        return ((Number) value).longValue();
    }

    public double asDouble() {
        return ((Number) value).doubleValue();
    }

    public boolean asBoolean() {
        return (Boolean) value;
    }

    /**
     * Returns the normalized Java value: {@link Double}, {@link Boolean}, {@link String}, or
     * {@code null} for primitives, and recursively converted {@code List<Object>} or
     * {@code Map<String, Object>} values for JavaScript arrays and plain objects.
     *
     * @return the Java representation of this JavaScript value
     */
    public Object asObject() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
