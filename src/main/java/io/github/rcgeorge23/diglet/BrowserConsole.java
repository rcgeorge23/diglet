package io.github.rcgeorge23.diglet;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BrowserConsole implements ProxyObject {
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    public List<String> getLogs() {
        return logs;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void clear() {
        logs.clear();
        errors.clear();
    }

    @Override
    public Object getMember(String key) {
        return switch (key) {
            case "log" -> (ProxyExecutable) arguments -> {
                StringBuilder message = new StringBuilder();
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) message.append(" ");
                    message.append(arguments[i].asString());
                }
                logs.add(message.toString());
                return null;
            };
            case "error" -> (ProxyExecutable) arguments -> {
                StringBuilder message = new StringBuilder();
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) message.append(" ");
                    message.append(arguments[i].asString());
                }
                errors.add(message.toString());
                return null;
            };
            default -> null;
        };
    }

    @Override
    public Object getMemberKeys() {
        return new String[]{"log", "error"};
    }

    @Override
    public boolean hasMember(String key) {
        return "log".equals(key) || "error".equals(key);
    }

    @Override
    public void putMember(String key, Value value) {
        // no-op
    }
}
