package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WebTestLightpandaTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void lightpandaModeRequiresAnExplicitCdpEndpoint() throws IOException {
        int port = startServer();

        assertThatThrownBy(() -> new WebTest(port, WebTest.Browser.LIGHTPANDA, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CDP endpoint");

        assertThatThrownBy(() -> new WebTest(port, WebTest.Browser.HTML_UNIT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only be used with Browser.LIGHTPANDA");
    }

    @Test
    void lightpandaRunsModernJavaScriptAndReportsScriptErrors() throws Exception {
        String endpoint = System.getenv("DIGLET_LIGHTPANDA_CDP_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(),
                "Set DIGLET_LIGHTPANDA_CDP_ENDPOINT to an externally running Lightpanda CDP endpoint");
        int port = startServer();

        try (WebTest webTest = new WebTest(port, WebTest.Browser.LIGHTPANDA, URI.create(endpoint))) {
            webTest.ignoreJavascriptErrors()
                    .navigateTo("/modern")
                    .assertCurrentUrlIs("http://localhost:" + port + "/modern")
                    .assertPageBodyContains("Pick a name")
                    .typeInto("#name", "Ada")
                    .click("#load")
                    .waitFor(document -> {
                        var result = document.selectFirst("#result");
                        return result != null && "ready:Ada".equals(result.text());
                    });

            assertThat(webTest.evaluateScript("({title: document.title, result: document.querySelector('#result').textContent})")
                    .asObject())
                    .isEqualTo(Map.of("title", "Lightpanda CDP", "result", "ready:Ada"));
            assertThat(webTest.body()).contains("ready:Ada");

            webTest.executeScript("throw new Error('lightpanda-js-error-marker')");
            webTest.postJson("/direct-response", Map.of("source", "jdk-http"));
            webTest.followRedirect();
            webTest.waitFor(document -> document.body().text().contains("direct-response-marker"), Duration.ofSeconds(5));
            assertThat(webTest.body()).contains("direct-response-marker");
            assertThat(webTest.status()).isEqualTo(200);

            webTest.postJson("/direct-redirect", Map.of("source", "jdk-http"));
            assertThat(webTest.status()).isEqualTo(302);
            webTest.followRedirect();
            webTest.waitFor(document -> document.body().text().contains("redirect-target-marker"), Duration.ofSeconds(5));
            assertThat(webTest.body()).contains("redirect-target-marker");
            webTest.assertCurrentUrlIs("http://localhost:" + server.getAddress().getPort() + "/redirect-target");

            assertThat(webTest.javascriptErrors())
                .anySatisfy(error -> assertThat(error).contains("lightpanda-js-error-marker"));
        }
    }

    private int startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/modern", exchange -> respond(exchange, "text/html; charset=UTF-8", """
                <!doctype html>
                <html><head><title>Lightpanda CDP</title></head><body>
                  <label for="name">Pick a name</label>
                  <input id="name" value="default">
                  <button id="load">Load</button>
                  <output id="result"></output>
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
        server.createContext("/direct-response", exchange -> respond(exchange, "text/html; charset=UTF-8",
                "<!doctype html><html><body>direct-response-marker</body></html>"));
        server.createContext("/direct-redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-target", exchange -> respond(exchange, "text/html; charset=UTF-8",
                "<!doctype html><html><body>redirect-target-marker</body></html>"));
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
