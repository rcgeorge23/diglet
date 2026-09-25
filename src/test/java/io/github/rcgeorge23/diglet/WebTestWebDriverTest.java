package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import org.springframework.http.HttpStatus;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            WebTest webTest = new WebTest(port, false, WebTest.Browser.CHROME, supplier)
                    .navigateTo("/");
            webTest.assertPageBodyContains("Hello");
            assertThatThrownBy(webTest::status)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("HTTP response status is unavailable for the current page");
            assertThatThrownBy(() -> webTest.assertStatusIs(HttpStatus.OK))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("HTTP response status is unavailable for the current page");
        } finally {
            driver.quit();
            server.stop(0);
        }
    }

    @Test
    void directHttpStatusRemainsAvailableInWebDriverMode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/missing", exchange -> respond(exchange, "not found", 404));
        server.start();
        int port = server.getAddress().getPort();
        HtmlUnitDriver driver = new HtmlUnitDriver(true);

        try {
            WebTest webTest = new WebTest(port, false, WebTest.Browser.CHROME, () -> driver)
                    .postJson("/missing", Map.of());

            assertThat(webTest.status()).isEqualTo(404);
            webTest.assertStatusIs(HttpStatus.NOT_FOUND);
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

    @Test
    void pooledDriverIsReusedAcrossWebTests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> respond(exchange, "<html><body>Hello</body></html>"));
        server.start();
        int port = server.getAddress().getPort();
        AtomicInteger created = new AtomicInteger();

        try (WebDriverPool pool = new WebDriverPool(() -> {
            created.incrementAndGet();
            return new HtmlUnitDriver(true);
        })) {
            new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/").assertPageBodyContains("Hello");
            new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/").assertPageBodyContains("Hello");

            assertThat(created).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pooledDriverClearsCookiesBetweenWebTests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/set-cookie", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=abc; Path=/");
            respond(exchange, "<html><body>cookie set</body></html>");
        });
        server.createContext("/", exchange -> respond(exchange, "<html><body>Hello</body></html>"));
        server.start();
        int port = server.getAddress().getPort();

        try (WebDriverPool pool = new WebDriverPool(() -> new HtmlUnitDriver(true))) {
            new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/set-cookie");
            WebTest second = new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/");

            assertThat(second.evaluateScript("document.cookie").asString()).doesNotContain("session=abc");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pooledDriverClearsLocalStorageBetweenWebTests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> respond(exchange, "<html><body>Hello</body></html>"));
        server.start();
        int port = server.getAddress().getPort();

        try (WebDriverPool pool = new WebDriverPool(() -> new HtmlUnitDriver(true))) {
            new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/")
                    .executeScript("localStorage.setItem('k', 'v')");
            WebTest second = new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/");

            assertThat(second.evaluateScript("String(localStorage.getItem('k'))").asString()).isEqualTo("null");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pooledDriverSurvivesWebTestClose() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> respond(exchange, "<html><body>Hello</body></html>"));
        server.start();
        int port = server.getAddress().getPort();

        try (WebDriverPool pool = new WebDriverPool(() -> new HtmlUnitDriver(true))) {
            new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/").close();
            new WebTest(port, false, WebTest.Browser.CHROME, pool).navigateTo("/").assertPageBodyContains("Hello");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        respond(exchange, body, 200);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
