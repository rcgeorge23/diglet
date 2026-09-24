package io.github.rcgeorge23.diglet;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.List;

public class JsNodeList implements ProxyArray {
    private final List<JsElement> elements;

    public JsNodeList(List<JsElement> elements) {
        this.elements = elements;
    }

    @Override
    public Object get(long index) {
        return (int) index < elements.size() ? elements.get((int) index) : null;
    }

    @Override
    public void set(long index, Value value) {
        // no-op
    }

    @Override
    public long getSize() {
        return elements.size();
    }

    public void forEach(ProxyExecutable callback) {
        for (int i = 0; i < elements.size(); i++) {
            try {
                callback.execute(Value.asValue(elements.get(i)), Value.asValue(i), Value.asValue(this));
            } catch (Exception e) {
                // Ignore callback execution errors
            }
        }
    }
}
