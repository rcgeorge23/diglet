package io.github.rcgeorge23.diglet;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jsoup.nodes.Element;

/**
 * Minimal implementation of the JavaScript dataset API for JsElements.
 */
public class JsDataset implements ProxyObject {

    private final Element element;

    public JsDataset(Element element) {
        this.element = element;
    }

    @Override
    public Object getMember(String key) {
        return element.attr("data-" + toKebabCase(key));
    }

    @Override
    public Object getMemberKeys() {
        return new String[]{};
    }

    @Override
    public boolean hasMember(String key) {
        return true;
    }

    @Override
    public void putMember(String key, Value value) {
        element.attr("data-" + toKebabCase(key), value.asString());
    }

    private String toKebabCase(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

