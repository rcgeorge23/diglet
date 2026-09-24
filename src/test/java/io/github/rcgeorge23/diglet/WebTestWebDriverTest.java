package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import org.springframework.http.HttpStatus;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

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
}
