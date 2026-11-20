package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebTestEmbeddedLightpandaTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void embeddedModeRunsModernJavaScriptAndPumpsTimers() throws Exception {
        int port = startServer();

        try (WebTest webTest = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA)) {
            webTest.navigateTo("/modern")
                    .assertCurrentUrlIs("http://localhost:" + port + "/modern")
                    .assertPageBodyContains("Pick a name")
                    .typeInto("#name", "Ada")
                    .click("#load")
                    .waitFor(document -> {
                        var result = document.selectFirst("#result");
                        return result != null && "ready:Ada".equals(result.text());
                    }, Duration.ofSeconds(10));

            assertThat(webTest.evaluateScript(
                    "({title: document.title, result: document.querySelector('#result').textContent})")
                    .asObject())
                    .isEqualTo(Map.of("title", "Diglet Embedded", "result", "ready:Ada"));

            webTest.executeScript(
                    "setTimeout(() => document.querySelector('#timer').textContent = 'pumped', 20)");
            webTest.waitFor(document -> "pumped".equals(document.selectFirst("#timer").text()),
                    Duration.ofSeconds(5));
            assertThat(webTest.body()).contains("pumped");
        }
    }

    @Test
    void embeddedSessionsKeepTheirPagesIsolated() throws Exception {
        int port = startServer();

        try (WebTest first = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA);
             WebTest second = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA)) {
            first.navigateTo("/modern").typeInto("#name", "Ada");
            second.navigateTo("/modern").typeInto("#name", "Grace");

            first.click("#load").waitFor(document -> "ready:Ada".equals(
                    document.selectFirst("#result").text()), Duration.ofSeconds(10));
            second.click("#load").waitFor(document -> "ready:Grace".equals(
                    document.selectFirst("#result").text()), Duration.ofSeconds(10));

            assertThat(first.evaluateScript("document.querySelector('#result').textContent").asString())
                    .isEqualTo("ready:Ada");
            assertThat(second.evaluateScript("document.querySelector('#result').textContent").asString())
                    .isEqualTo("ready:Grace");
        }
    }

    @Test
    void embeddedModeFailsOnConsoleErrorByDefault() throws Exception {
        int port = startServer();

        try (WebTest webTest = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA)) {
            assertThatThrownBy(() -> webTest.navigateTo("/console-error"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Page JavaScript errors:")
                    .hasMessageContaining("embedded-console-error-marker");

            assertThat(webTest.javascriptErrors())
                    .anySatisfy(error -> assertThat(error)
                            .contains("Lightpanda console.error:")
                            .contains("embedded-console-error-marker"));
        }
    }

    @Test
    void embeddedModeFailsOnUncaughtListenerErrorByDefault() throws Exception {
        int port = startServer();

        try (WebTest webTest = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA)) {
            webTest.navigateTo("/errors");

            assertThatThrownBy(() -> webTest.click("#listener-error"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("embedded-listener-boom");

            assertThat(webTest.javascriptErrors())
                    .anySatisfy(error -> assertThat(error)
                            .contains("Lightpanda JavaScript error:")
                            .contains("embedded-listener-boom"));
        }
    }

    @Test
    void ignoreJavascriptErrorsKeepsConsoleErrorsQueryable() throws Exception {
        int port = startServer();

        try (WebTest webTest = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA)) {
            webTest.ignoreJavascriptErrors().navigateTo("/console-error");

            assertThat(webTest.javascriptErrors())
                    .anyMatch(error -> error.contains("embedded-console-error-marker"));
        }
    }

    @Test
    void ignoreJavascriptErrorsKeepsUncaughtErrorsAndRejectionsQueryable() throws Exception {
        int port = startServer();

        try (WebTest webTest = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA)) {
            webTest.ignoreJavascriptErrors().navigateTo("/errors").click("#listener-error");
            assertThat(webTest.javascriptErrors())
                    .anyMatch(error -> error.contains("embedded-listener-boom"));

            webTest.click("#reject");
            webTest.waitFor(document -> webTest.javascriptErrors().stream()
                    .anyMatch(error -> error.contains("embedded-reject-boom")), Duration.ofSeconds(5));

            assertThat(webTest.javascriptErrors())
                    .anySatisfy(error -> assertThat(error)
                            .contains("Lightpanda JavaScript error:")
                            .contains("embedded-reject-boom"));
        }
    }

    @Test
    void embeddedModeRearmsErrorCaptureAfterSameUrlReload() throws Exception {
        int port = startServer();

        try (WebTest webTest = new WebTest(port, WebTest.Browser.EMBEDDED_LIGHTPANDA)) {
            webTest.navigateTo("/reload-errors");
            webTest.click("#reload");
            webTest.waitFor(document -> {
                var generation = document.selectFirst("#generation");
                return generation != null && "2".equals(generation.text());
            }, Duration.ofSeconds(10));

            assertThatThrownBy(() -> webTest.click("#listener-error"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("reload-listener-boom");
        }
    }

    private int startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicInteger reloadGeneration = new AtomicInteger();
        server.createContext("/modern", exchange -> respond(exchange, "text/html; charset=UTF-8", """
                <!doctype html>
                <html><head><title>Diglet Embedded</title></head><body>
                  <label for="name">Pick a name</label>
                  <input id="name" value="default">
                  <button id="load">Load</button>
                  <output id="result"></output>
                  <output id="timer"></output>
                  <script type="module">
                    class Widget {
                      async load(name) {
                        const response = await fetch('/api/data?name=' + encodeURIComponent(name));
                        const data = await response.json();
                        return data.value + ':' + name;
                      }
                    }
                    document.querySelector('#load').addEventListener('click', async () => {
                      document.querySelector('#result').textContent = await new Widget().load(
                        document.querySelector('#name').value);
                    });
                  </script>
                </body></html>
                """));
        server.createContext("/api/data", exchange -> respond(exchange, "application/json; charset=UTF-8",
                "{\"value\":\"ready\"}"));
        server.createContext("/console-error", exchange -> respond(exchange, "text/html; charset=UTF-8", """
                <!doctype html>
                <html><head><title>Diglet Embedded console error</title></head><body>
                  <p>console error page</p>
                  <script>console.error('embedded-console-error-marker');</script>
                </body></html>
                """));
        server.createContext("/errors", exchange -> respond(exchange, "text/html; charset=UTF-8", """
                <!doctype html>
                <html><head><title>Diglet Embedded errors</title></head><body>
                  <button id="listener-error">Listener error</button>
                  <button id="reject">Reject</button>
                  <output id="result"></output>
                  <script>
                    document.querySelector('#listener-error').addEventListener('click', () => {
                      throw new Error('embedded-listener-boom');
                    });
                    document.querySelector('#reject').addEventListener('click', () => {
                      Promise.reject(new Error('embedded-reject-boom'));
                    });
                  </script>
                </body></html>
                """));
        server.createContext("/reload-errors", exchange -> {
            int generation = reloadGeneration.incrementAndGet();
            respond(exchange, "text/html; charset=UTF-8", """
                    <!doctype html>
                    <html><head><title>Diglet Embedded reload</title></head><body>
                      <p id="generation">%d</p>
                      <button id="reload">Reload</button>
                      <button id="listener-error">Listener error</button>
                      <script>
                        document.querySelector('#reload').addEventListener('click', () => location.reload());
                        document.querySelector('#listener-error').addEventListener('click', () => {
                          throw new Error('reload-listener-boom');
                        });
                      </script>
                    </body></html>
                    """.formatted(generation));
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
