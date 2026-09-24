package io.github.rcgeorge23.diglet;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

public class JsEvent implements ProxyObject {
    final String type;
    final String key;
    final JsElement target;

    public JsEvent(String type) {
        this(type, null, null);
    }

    public JsEvent(String type, String key) {
        this(type, key, null);
    }

    public JsEvent(String type, String key, JsElement target) {
        this.type = type;
        this.key = key;
        this.target = target;
    }

    @Override
    public Object getMember(String member) {
        return switch (member) {
            case "preventDefault" -> (ProxyExecutable) args -> null;
            case "type" -> type;
            case "key" -> key;
            case "target" -> target;
            default -> null;
        };
    }

    @Override
    public Object getMemberKeys() {
        return new String[]{"preventDefault", "type", "key", "target"};
    }

    @Override
    public boolean hasMember(String key) {
        return "preventDefault".equals(key) || "type".equals(key) || "key".equals(key) || "target".equals(key);
    }

    @Override
    public void putMember(String key, Value value) {
        // no-op
    }
}
