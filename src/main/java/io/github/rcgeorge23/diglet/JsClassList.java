package io.github.rcgeorge23.diglet;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jsoup.nodes.Element;

public class JsClassList implements ProxyObject {
    private final Element element;

    public JsClassList(Element element) {
        this.element = element;
    }

    @Override
    public Object getMember(String key) {
        return switch (key) {
            case "add" -> (ProxyExecutable) args -> {
                element.addClass(args[0].asString());
                return null;
            };
            case "remove" -> (ProxyExecutable) args -> {
                element.removeClass(args[0].asString());
                return null;
            };
            case "contains" -> (ProxyExecutable) args -> element.hasClass(args[0].asString());
            default -> null;
        };
    }

    @Override
    public Object getMemberKeys() {
        return new String[]{"add", "remove", "contains"};
    }

    @Override
    public boolean hasMember(String key) {
        return "add".equals(key) || "remove".equals(key) || "contains".equals(key);
    }

    @Override
    public void putMember(String key, Value value) {
        // no-op
    }
}
