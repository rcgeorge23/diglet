package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import org.springframework.http.HttpStatus;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class WebTestWebDriverTest {

    @Test
    void canUseCustomWebDriver() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "<html><head><title>Hello</title></head><body>Hello</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        HtmlUnitDriver driver = new HtmlUnitDriver(true);
        Supplier<WebDriver> supplier = () -> driver;

        try {
            new WebTest(port, false, WebTest.Browser.CHROME, supplier)
                    .navigateTo("/")
                    .assertStatusIs(HttpStatus.OK)
                    .assertPageBodyContains("Hello");
        } finally {
            driver.quit();
            server.stop(0);
        }
    }

    @Test
    void submitFormFiresSubmitHandlerWithCustomWebDriver() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/form", exchange -> {
            byte[] bytes = ("<html><body><form id='f' action='/submit' method='post' " +
                    "onsubmit=\"document.getElementById('r').textContent='handled'; return false;\">" +
                    "<input name='q' value='def'/><button type='submit'>Go</button></form>" +
                    "<div id='r'></div></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/submit", exchange -> {
            byte[] bytes = "<html><body>SUBMITTED</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        HtmlUnitDriver driver = new HtmlUnitDriver(true);
        try {
            WebTest webTest = new WebTest(port, false, WebTest.Browser.CHROME, () -> driver)
                    .navigateTo("/form")
                    .submitForm("f", Map.of("q", "changed"));

            assertThat(webTest.document().getElementById("r").text()).isEqualTo("handled");
            assertThat(webTest.body()).doesNotContain("SUBMITTED");
        } finally {
            driver.quit();
            server.stop(0);
        }
    }
}
