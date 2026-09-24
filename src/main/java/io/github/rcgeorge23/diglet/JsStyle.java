package io.github.rcgeorge23.diglet;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jsoup.nodes.Element;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class JsStyle implements ProxyObject {
    private final Element element;

    public JsStyle(Element element) {
        this.element = element;
    }

    private Map<String, String> parse() {
        Map<String, String> map = new HashMap<>();
        String style = element.attr("style");
        if (style != null && !style.isEmpty()) {
            for (String part : style.split(";")) {
                if (part.contains(":")) {
                    String[] kv = part.split(":", 2);
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return map;
    }

    @Override
    public Object getMember(String key) {
        return parse().getOrDefault(key, "");
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
        Map<String, String> styles = parse();
        styles.put(key, value.asString());
        String styleStr = styles.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(";"));
        element.attr("style", styleStr);
    }
}
