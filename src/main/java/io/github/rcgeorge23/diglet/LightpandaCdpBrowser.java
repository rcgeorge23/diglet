package io.github.rcgeorge23.diglet;

import org.openqa.selenium.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small CDP adapter for an externally managed Lightpanda process. It owns the page target and
 * WebSocket it creates, but deliberately never starts or stops the Lightpanda process.
 */
final class LightpandaCdpBrowser implements AutoCloseable {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(30);

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Json json = new Json();
    private final BrowserConsole console;
    private final AtomicLong commandIds = new AtomicLong();
    private final Map<Long, CompletableFuture<Map<String, Object>>> pendingCommands = new ConcurrentHashMap<>();
    private final Object connectionLock = new Object();
    private volatile WebSocket webSocket;
    private volatile String targetId;
    private volatile String sessionId;
    private volatile CompletableFuture<Void> pendingLoad;

    LightpandaCdpBrowser(URI endpoint, BrowserConsole console) {
        this.endpoint = validateEndpoint(endpoint);
        this.console = console;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static URI validateEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("A CDP endpoint is required for Browser.LIGHTPANDA");
        }
        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute() || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("The Lightpanda CDP endpoint must be an absolute HTTP(S) URI");
        }
        return endpoint;
    }

    void navigate(String url) throws IOException, InterruptedException {
        ensureConnected();
        CompletableFuture<Void> load = new CompletableFuture<>();
        pendingLoad = load;
        Map<String, Object> response = sendSessionCommand("Page.navigate", Map.of("url", url));
        Map<String, Object> result = objectMap(response.get("result"));
        Object errorText = result.get("errorText");
        if (errorText != null && !errorText.toString().isBlank()) {
            pendingLoad = null;
            throw new IOException("Lightpanda could not navigate to " + url + ": " + errorText);
        }
        try {
            load.get(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pendingLoad = null;
            throw new IOException("Timed out waiting for Lightpanda to load " + url, exception);
        } catch (ExecutionException exception) {
            pendingLoad = null;
            throw new IOException("Lightpanda failed while loading " + url, exception.getCause());
        } finally {
            pendingLoad = null;
        }
    }

    Map<String, Object> pageSnapshot() throws IOException, InterruptedException {
        Object value = evaluate("({html: document.documentElement ? document.documentElement.outerHTML : '', url: location.href})", true);
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException("Lightpanda did not return the current page snapshot");
        }
        return objectMap(value);
    }

    Object evaluate(String expression, boolean awaitPromise) throws IOException, InterruptedException {
        Map<String, Object> response = evaluateResponse(expression, awaitPromise);
        Map<String, Object> result = objectMap(response.get("result"));
        if (result.containsKey("value")) {
            return result.get("value");
        }
        String type = stringValue(result.get("type"));
        if ("undefined".equals(type) || "null".equals(stringValue(result.get("subtype")))) {
            return null;
        }
        throw new IllegalArgumentException(
                "Unsupported JavaScript result type; return a primitive, array, or plain object instead");
    }

    void execute(String script) throws IOException, InterruptedException {
        evaluateResponse(script, false);
    }

    void click(String selector) throws IOException, InterruptedException {
        String selectorLiteral = json.toJson(selector);
        execute("(() => { const element = document.querySelector(" + selectorLiteral + ");"
                + "if (!element) throw new Error('No element matches selector: ' + " + selectorLiteral + ");"
                + "element.click(); })();");
    }

    void setInputValue(String selector, String value) throws IOException, InterruptedException {
        String selectorLiteral = json.toJson(selector);
        String valueLiteral = json.toJson(value);
        execute("(() => { const element = document.querySelector(" + selectorLiteral + ");"
                + "if (!element) throw new Error('No element matches selector: ' + " + selectorLiteral + ");"
                + "element.value = " + valueLiteral + ";"
                + "element.dispatchEvent(new Event('input', { bubbles: true })); })();");
    }

    void typeInto(String selector, String text) throws IOException, InterruptedException {
        String selectorLiteral = json.toJson(selector);
        String textLiteral = json.toJson(text);
        execute("(() => { const element = document.querySelector(" + selectorLiteral + ");"
                + "if (!element) throw new Error('No element matches selector: ' + " + selectorLiteral + ");"
                + "const text = " + textLiteral + "; element.focus(); element.value = '';"
                + "for (const character of Array.from(text)) {"
                + "const init = { key: character, bubbles: true };"
                + "element.dispatchEvent(new KeyboardEvent('keydown', init));"
                + "element.dispatchEvent(new KeyboardEvent('keypress', init));"
                + "element.value += character;"
                + "element.dispatchEvent(new InputEvent('input', { bubbles: true, data: character, inputType: 'insertText' }));"
                + "element.dispatchEvent(new KeyboardEvent('keyup', init));"
                + "} element.dispatchEvent(new Event('change', { bubbles: true })); element.blur(); })();");
    }

    Object inputValue(String selector) throws IOException, InterruptedException {
        String selectorLiteral = json.toJson(selector);
        return evaluate("(() => { const element = document.querySelector(" + selectorLiteral + ");"
                + "if (!element) throw new Error('No element matches selector: ' + " + selectorLiteral + ");"
                + "return element.value; })()", true);
    }

    private Map<String, Object> evaluateResponse(String expression, boolean awaitPromise)
            throws IOException, InterruptedException {
        Map<String, Object> params = new HashMap<>();
        params.put("expression", expression);
        params.put("awaitPromise", awaitPromise);
        params.put("returnByValue", true);
        params.put("userGesture", true);
        Map<String, Object> response = sendSessionCommand("Runtime.evaluate", params);
        Map<String, Object> result = objectMap(response.get("result"));
        if (result.get("exceptionDetails") instanceof Map<?, ?> details) {
            recordException(objectMap(details));
        }
        return result;
    }

    private void ensureConnected() throws IOException, InterruptedException {
        if (webSocket != null) {
            return;
        }
        synchronized (connectionLock) {
            if (webSocket != null) {
                return;
            }
            connectAndCreateTarget();
        }
    }

    private void connectAndCreateTarget() throws IOException, InterruptedException {
        URI versionUri = endpoint.resolve("/json/version");
        HttpRequest request = HttpRequest.newBuilder(versionUri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Lightpanda CDP discovery returned HTTP " + response.statusCode()
                    + " from " + versionUri);
        }
        Map<String, Object> version = parseObject(response.body());
        String webSocketUrl = stringValue(version.get("webSocketDebuggerUrl"));
        if (webSocketUrl == null || webSocketUrl.isBlank()) {
            throw new IOException("Lightpanda /json/version response did not include webSocketDebuggerUrl");
        }

        WebSocket connected;
        try {
            connected = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(webSocketUrl), new CdpListener())
                    .get(15, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Could not connect to the Lightpanda CDP WebSocket", exception);
        }
        webSocket = connected;

        Map<String, Object> created = objectMap(sendBrowserCommand("Target.createTarget", Map.of("url", "about:blank"))
                .get("result"));
        targetId = stringValue(created.get("targetId"));
        if (targetId == null || targetId.isBlank()) {
            throw new IOException("Lightpanda did not create a CDP page target");
        }

        Map<String, Object> attached = objectMap(sendBrowserCommand("Target.attachToTarget",
                Map.of("targetId", targetId, "flatten", true)).get("result"));
        sessionId = stringValue(attached.get("sessionId"));
        if (sessionId == null || sessionId.isBlank()) {
            throw new IOException("Lightpanda did not attach a CDP session to its page target");
        }
        sendSessionCommand("Page.enable", Map.of());
        sendSessionCommand("Runtime.enable", Map.of());
    }

    private Map<String, Object> sendBrowserCommand(String method, Map<String, Object> params)
            throws IOException, InterruptedException {
        return sendCommand(method, params, null);
    }

    private Map<String, Object> sendSessionCommand(String method, Map<String, Object> params)
            throws IOException, InterruptedException {
        ensureConnected();
        return sendCommand(method, params, sessionId);
    }

    private Map<String, Object> sendCommand(String method, Map<String, Object> params, String targetSessionId)
            throws IOException, InterruptedException {
        WebSocket socket = webSocket;
        if (socket == null) {
            throw new IOException("Lightpanda CDP WebSocket is not connected");
        }
        long commandId = commandIds.incrementAndGet();
        CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
        pendingCommands.put(commandId, response);
        Map<String, Object> command = new HashMap<>();
        command.put("id", commandId);
        command.put("method", method);
        command.put("params", params);
        if (targetSessionId != null) {
            command.put("sessionId", targetSessionId);
        }
        try {
            socket.sendText(json.toJson(command), true).get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Map<String, Object> message = response.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (message.get("error") instanceof Map<?, ?> error) {
                throw new IOException("Lightpanda CDP command " + method + " failed: "
                        + objectMap(error).get("message"));
            }
            return message;
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Lightpanda CDP command timed out or failed: " + method, exception);
        } finally {
            pendingCommands.remove(commandId);
        }
    }

    private void handleMessage(String message) {
        try {
            Map<String, Object> parsed = parseObject(message);
            Object rawId = parsed.get("id");
            if (rawId instanceof Number id) {
                CompletableFuture<Map<String, Object>> pending = pendingCommands.get(id.longValue());
                if (pending != null) {
                    pending.complete(parsed);
                }
                return;
            }
            String method = stringValue(parsed.get("method"));
            String eventSession = stringValue(parsed.get("sessionId"));
            if (sessionId != null && eventSession != null && !sessionId.equals(eventSession)) {
                return;
            }
            Map<String, Object> params = objectMap(parsed.get("params"));
            if ("Page.loadEventFired".equals(method)) {
                CompletableFuture<Void> load = pendingLoad;
                if (load != null) {
                    load.complete(null);
                }
            } else if ("Runtime.exceptionThrown".equals(method)) {
                recordException(objectMap(params.get("exceptionDetails")));
            } else if ("Runtime.consoleAPICalled".equals(method)
                    && "error".equals(stringValue(params.get("type")))) {
                console.getErrors().add("Lightpanda console.error: " + formatArguments(params.get("args")));
            }
        } catch (RuntimeException exception) {
            console.getErrors().add("Lightpanda CDP event could not be read: " + exception.getMessage());
        }
    }

    private void recordException(Map<String, Object> details) {
        Map<String, Object> exception = objectMap(details.get("exception"));
        String description = stringValue(exception.get("description"));
        String text = stringValue(details.get("text"));
        String message = description == null || description.isBlank() ? text : description;
        if (message == null || message.isBlank()) {
            message = "JavaScript exception";
        }
        console.getErrors().add("Lightpanda JavaScript error: " + message);
    }

    private String formatArguments(Object rawArguments) {
        if (!(rawArguments instanceof List<?> arguments)) {
            return String.valueOf(rawArguments);
        }
        List<String> values = new ArrayList<>(arguments.size());
        for (Object argument : arguments) {
            Map<String, Object> item = objectMap(argument);
            Object value = item.containsKey("value") ? item.get("value") : item.get("description");
            values.add(String.valueOf(value));
        }
        return String.join(" ", values);
    }

    private Map<String, Object> parseObject(String source) {
        return objectMap(json.toType(source, Json.OBJECT_TYPE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public void close() {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        if (targetId != null) {
            try {
                sendBrowserCommand("Target.closeTarget", Map.of("targetId", targetId));
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "WebTest closed its page target")
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            socket.abort();
        } finally {
            webSocket = null;
            targetId = null;
            sessionId = null;
        }
    }

    private final class CdpListener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                handleMessage(text.toString());
                text.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            IOException failure = new IOException("Lightpanda CDP WebSocket closed: " + statusCode + " " + reason);
            pendingCommands.values().forEach(future -> future.completeExceptionally(failure));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            pendingCommands.values().forEach(future -> future.completeExceptionally(error));
            console.getErrors().add("Lightpanda CDP WebSocket error: " + error.getMessage());
        }
    }
}
