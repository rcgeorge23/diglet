package io.github.rcgeorge23.diglet;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class BrowserDocument {
    private final Document document;
    private final BrowserWindow window;
    private final List<CompletableFuture<?>> asyncTasks;

    public BrowserDocument(Document document, BrowserWindow window, List<CompletableFuture<?>> asyncTasks) {
        this.document = document;
        this.window = window;
        this.asyncTasks = asyncTasks;
    }

    public JsElement querySelector(String selector) {
        Element element = document.selectFirst(selector);
        return element != null ? new JsElement(element, window, asyncTasks) : null;
    }

    public JsNodeList querySelectorAll(String selector) {
        return new JsNodeList(
                document.select(selector).stream()
                        .map(el -> new JsElement(el, window, asyncTasks))
                        .collect(Collectors.toList())
        );
    }

    public JsElement getElementById(String id) {
        Element element = document.getElementById(id);
        return element != null ? new JsElement(element, window, asyncTasks) : null;
    }

    public void addEventListener(String event, ProxyExecutable handler) {
        // No-op: event listeners are not triggered in tests
    }

    public JsElement createElement(String tag) {
        Element el = document.createElement(tag);
        return new JsElement(el, window, asyncTasks);
    }
}
