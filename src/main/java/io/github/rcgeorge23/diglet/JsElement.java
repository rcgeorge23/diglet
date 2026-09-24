package io.github.rcgeorge23.diglet;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class JsElement implements ProxyObject {
    private final Element element;
    private final BrowserWindow window;
    private final List<CompletableFuture<?>> asyncTasks;
    private final Map<String, List<Value>> eventHandlers = new HashMap<>();

    public JsElement(Element element, BrowserWindow window, List<CompletableFuture<?>> asyncTasks) {
        this.element = element;
        this.window = window;
        this.asyncTasks = asyncTasks;
    }

    @Override
    public Object getMember(String key) {
        return switch (key) {
            case "click" -> (ProxyExecutable) args -> {
                dispatchEvent("click", new JsEvent("click", null, this));
                String href = element.attr("href");
                if (!href.isEmpty()) {
                    window.location.href = href;
                }
                return null;
            };
            case "classList" -> new JsClassList(element);
            case "addEventListener" -> (ProxyExecutable) args -> {
                String event = args[0].asString();
                Value handler = args[1];
                eventHandlers.computeIfAbsent(event, k -> new ArrayList<>()).add(handler);
                return null;
            };
            case "dispatchEvent" -> (ProxyExecutable) args -> {
                JsEvent event = toJsEvent(args[0]);
                dispatchEvent(event.type, event);
                return true;
            };
            case "querySelector" -> (ProxyExecutable) args -> {
                Element found = element.selectFirst(args[0].asString());
                return found != null ? new JsElement(found, window, asyncTasks) : null;
            };
            case "querySelectorAll" -> (ProxyExecutable) args -> new JsNodeList(
                    element.select(args[0].asString()).stream()
                            .map(el -> new JsElement(el, window, asyncTasks))
                            .toList()
            );
            case "insertBefore" -> (ProxyExecutable) args -> {
                JsElement newNode = args[0].asHostObject();
                JsElement referenceNode = args[1].asHostObject();
                referenceNode.element.before(newNode.element);
                return newNode;
            };
            case "innerHTML" -> element.html();
            case "textContent" -> element.text();
            case "value" -> element.attr("value");
            case "checked" -> element.hasAttr("checked");
            case "disabled" -> element.hasAttr("disabled");
            case "style" -> new JsStyle(element);
            case "dataset" -> new JsDataset(element);
            default -> null;
        };
    }

    private JsEvent toJsEvent(Value arg) {
        if (arg.hasMembers()) {
            String type = arg.hasMember("type") ? arg.getMember("type").asString() : "";
            String key = arg.hasMember("key") ? arg.getMember("key").asString() : null;
            return new JsEvent(type, key, this);
        }
        return new JsEvent(arg.asString(), null, this);
    }

    private void dispatchEvent(String event, JsEvent jsEvent) {
        List<Value> handlers = eventHandlers.get(event);
        if (handlers != null) {
            for (Value handler : handlers) {
                if (handler.canExecute()) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        handler.execute(jsEvent);
                    });
                    asyncTasks.add(future);
                    future.whenComplete((r, e) -> asyncTasks.remove(future));
                }
            }
        }
    }

    @Override
    public Object getMemberKeys() {
        return new String[]{"click", "classList", "addEventListener", "dispatchEvent", "querySelector", "querySelectorAll", "insertBefore", "innerHTML", "textContent", "value", "checked", "disabled", "style", "dataset"};
    }

    @Override
    public boolean hasMember(String key) {
        return "click".equals(key)
                || "classList".equals(key)
                || "addEventListener".equals(key)
                || "dispatchEvent".equals(key)
                || "querySelector".equals(key)
                || "querySelectorAll".equals(key)
                || "insertBefore".equals(key)
                || "innerHTML".equals(key)
                || "textContent".equals(key)
                || "value".equals(key)
                || "checked".equals(key)
                || "disabled".equals(key)
                || "style".equals(key)
                || "dataset".equals(key);
    }

    @Override
    public void putMember(String key, Value value) {
        switch (key) {
            case "innerHTML" -> element.html(value.asString());
            case "textContent" -> element.text(value.asString());
            case "value" -> element.attr("value", value.asString());
            case "checked" -> {
                if (value.asBoolean()) {
                    element.attr("checked", "checked");
                } else {
                    element.removeAttr("checked");
                }
            }
            case "disabled" -> {
                if (value.asBoolean()) {
                    element.attr("disabled", "disabled");
                } else {
                    element.removeAttr("disabled");
                }
            }
            default -> {
                // no-op
            }
        }
    }
}
