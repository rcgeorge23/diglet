package io.github.rcgeorge23.diglet;

import java.io.IOException;
import java.util.Map;

/**
 * Operations Diglet needs from a JavaScript-capable Lightpanda page.
 *
 * <p>The external-CDP and embedded adapters intentionally share only this
 * small page surface. HTTP requests made by {@link WebTest} remain separate.</p>
 */
interface LightpandaPage extends AutoCloseable {
    void navigate(String url) throws IOException, InterruptedException;

    Map<String, Object> pageSnapshot() throws IOException, InterruptedException;

    Object evaluate(String script, boolean awaitPromise) throws IOException, InterruptedException;

    void execute(String script) throws IOException, InterruptedException;

    void click(String selector) throws IOException, InterruptedException;

    void setInputValue(String selector, String value) throws IOException, InterruptedException;

    void typeInto(String selector, String text) throws IOException, InterruptedException;

    Object inputValue(String selector) throws IOException, InterruptedException;

    /** Advances pending browser work and returns the suggested delay before polling again. */
    long pumpAndSuggestDelayMillis() throws IOException, InterruptedException;

    /**
     * Ensures the current document reports uncaught JavaScript errors and unhandled promise
     * rejections through the page console. Implementations that receive native events may do
     * nothing.
     */
    void installJavascriptErrorCapture() throws IOException, InterruptedException;

    /**
     * Drains diagnostics recorded since the previous drain into the supplied console.
     * Implementations that record events asynchronously may do nothing.
     */
    void drainConsoleMessages(BrowserConsole console) throws IOException, InterruptedException;

    @Override
    void close();
}
