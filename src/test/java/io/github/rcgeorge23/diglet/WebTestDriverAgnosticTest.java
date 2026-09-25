package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import static org.assertj.core.api.Assertions.assertThat;

class WebTestDriverAgnosticTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    static Stream<String> browsers() {
        return Stream.of("html-unit", "web-driver");
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void theSameFlowRunsInBothModes(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/form")
                    .assertThatPageTitleIs("Form Page")
                    .setInputValue("#q", "hello")
                    .click("#go")
                    .assertPageBodyContains("SUBMITTED")
                    .assertPageBodyContains("q=hello");

            assertThat(webTest.evaluateScript("document.title").asString()).isEqualTo("Done");
        } finally {
            webTest.close();
        }
    }

    private int startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/form", exchange -> respond(exchange,
                "<html><head><title>Form Page</title></head><body>"
                        + "<form id='f' action='/submit' method='post'>"
                        + "<input id='q' name='q'/><button id='go' type='submit'>Go</button>"
                        + "</form></body></html>"));
        server.createContext("/submit", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, "<html><head><title>Done</title></head><body>SUBMITTED " + requestBody + "</body></html>");
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
