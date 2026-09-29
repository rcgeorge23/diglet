package io.github.rcgeorge23.diglet;

import org.openqa.selenium.json.Json;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** A WebTest page backed by one session in the JVM-wide embedded runtime. */
final class EmbeddedLightpandaPage implements LightpandaPage {
    private final EmbeddedLightpandaRuntime runtime;
    private final long sessionId;
    private final Json json = new Json();
    private final AtomicBoolean closed = new AtomicBoolean();

    EmbeddedLightpandaPage(EmbeddedLightpandaRuntime runtime, long sessionId) {
        this.runtime = runtime;
        this.sessionId = sessionId;
    }

    @Override
    public void navigate(String url) throws IOException, InterruptedException {
        call("goto", Map.of("url", url));
    }

    @Override
    public Map<String, Object> pageSnapshot() throws IOException, InterruptedException {
        Object result = evaluate("({html: document.documentElement?.outerHTML ?? '', url: location.href})", true);
        if (!(result instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Embedded Lightpanda returned an invalid page snapshot: " + result);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("html", map.get("html"));
        snapshot.put("url", map.get("url"));
        return snapshot;
    }

    @Override
    public Object evaluate(String script, boolean awaitPromise) throws IOException, InterruptedException {
        String encodedResultScript = "Promise.resolve((" + script + ")).then(value => JSON.stringify(value))";
        String result = call("evaluate", Map.of("script", encodedResultScript));
        if (result == null || result.isBlank() || result.equals("undefined")) {
            return null;
        }
        return json.toType(result, Object.class);
    }

    @Override
    public void execute(String script) throws IOException, InterruptedException {
        call("evaluate", Map.of("script", script));
    }

    @Override
    public void click(String selector) throws IOException, InterruptedException {
        call("click", Map.of("selector", selector));
    }

    @Override
    public void setInputValue(String selector, String value) throws IOException, InterruptedException {
        fill(selector, value);
    }

    @Override
    public void typeInto(String selector, String text) throws IOException, InterruptedException {
        fill(selector, text);
    }

    @Override
    public String inputValue(String selector) throws IOException, InterruptedException {
        Object value = evaluate("document.querySelector(" + json.toJson(selector) + ")?.value ?? null", true);
        return value == null ? null : value.toString();
    }

    @Override
    public long pumpAndSuggestDelayMillis() throws IOException, InterruptedException {
        ensureOpen();
        return runtime.pump(sessionId);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            runtime.closeSession(sessionId);
        }
    }

    private void fill(String selector, String value) throws IOException, InterruptedException {
        call("fill", Map.of("selector", selector, "value", value));
    }

    private String call(String tool, Map<String, ?> arguments) throws IOException, InterruptedException {
        ensureOpen();
        return runtime.call(sessionId, tool, json.toJson(arguments));
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Embedded Lightpanda page is closed");
        }
    }
}
