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

import static org.assertj.core.api.Assertions.assertThat;

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

    private int startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
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
