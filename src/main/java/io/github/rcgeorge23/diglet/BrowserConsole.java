package io.github.rcgeorge23.diglet;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BrowserConsole {
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
}
