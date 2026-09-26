package io.github.rcgeorge23.diglet.selenium;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/**
 * Opt-in headless-Chrome tests for Selenium support using a local fixture page.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeleniumWebDriverTestSupportTest {
    private static final String PAGE = """
            <!doctype html>
            <html>
            <head>
                <title>Diglet Selenium fixture</title>
                <style>
                    .modal { display: none; position: fixed; inset: 10px; background: white; }
                    .modal.show { display: block; }
                    .modal-backdrop { display: block; position: fixed; inset: 0; background: #0008; }
                </style>
            </head>
            <body>
                <form>
                    <label for="controlled-input">Name</label>
                    <input id="controlled-input" value="initial">
                    <output id="controlled-output">initial</output>
                    <label><input id="terms" type="checkbox"> Accept</label>
                    <output id="checkbox-output">unchecked</output>
                    <label><input id="payment-card" type="radio" name="payment"> Card</label>
                    <output id="radio-output">none</output>
                </form>
                <button id="primary-action" type="button">Primary</button>
                <button id="hidden-action" type="button" style="display:none">Hidden</button>
                <button id="alternate-action" type="button">Alternate</button>
                <output id="click-output">none</output>
                <button id="show-modal" type="button">Show modal</button>
                <div id="fixture-modal" class="modal" aria-hidden="true">
                    <button id="hide-modal" type="button">Hide modal</button>
                </div>
                <script>
                    const input = document.getElementById('controlled-input');
                    input.addEventListener('input', () => {
                        document.getElementById('controlled-output').textContent = input.value;
                    });
                    document.getElementById('terms').addEventListener('change', event => {
                        document.getElementById('checkbox-output').textContent = event.target.checked ? 'checked' : 'unchecked';
                    });
                    document.getElementById('payment-card').addEventListener('change', event => {
                        document.getElementById('radio-output').textContent = event.target.checked ? 'card' : 'none';
                    });
                    document.getElementById('primary-action').addEventListener('click', () => {
                        document.getElementById('click-output').textContent = 'primary';
                    });
                    document.getElementById('alternate-action').addEventListener('click', () => {
                        document.getElementById('click-output').textContent = 'alternate';
                    });
                    document.getElementById('show-modal').addEventListener('click', () => {
                        setTimeout(() => {
                            const modal = document.getElementById('fixture-modal');
                            modal.style.display = 'block';
                            modal.classList.add('show');
                            modal.setAttribute('aria-hidden', 'false');
                            document.body.classList.add('modal-open');
                            const backdrop = document.createElement('div');
                            backdrop.className = 'modal-backdrop';
                            backdrop.id = 'fixture-backdrop';
                            document.body.appendChild(backdrop);
                        }, 10);
                    });
                    document.getElementById('hide-modal').addEventListener('click', () => {
                        const modal = document.getElementById('fixture-modal');
                        modal.style.display = 'none';
                        modal.classList.remove('show');
                        modal.setAttribute('aria-hidden', 'true');
                        document.body.classList.remove('modal-open');
                        document.getElementById('fixture-backdrop')?.remove();
                    });
                </script>
            </body>
            </html>
            """;

    private HttpServer server;
    private WebDriver driver;
    private SeleniumWebDriverTestSupport support;
    private String pageUrl;

    @BeforeAll
    void startBrowserAndFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/support", this::serveFixture);
        server.start();

        driver = ChromeDriverFactory.createChromeDriver();
        support = new SeleniumWebDriverTestSupport(driver);
        pageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/support";
    }

    @BeforeEach
    void openFixture() {
        support.open(pageUrl);
    }

    @AfterAll
    void closeBrowserAndFixture() {
        if (driver != null) {
            driver.quit();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void browserHelpersDriveFormsClicksAndBootstrapModalState() {
        support.waitForDocumentReady();
        assertThat(support.executeScript("return document.title"))
                .isEqualTo("Diglet Selenium fixture");
        assertThat(support.executeScript("return navigator.webdriver")).isNull();

        support.typeInto(By.id("controlled-input"), "Ada");
        assertThat(driver.findElement(By.id("controlled-output")).getText()).isEqualTo("Ada");

        support.setCheckbox(By.id("terms"));
        assertThat(driver.findElement(By.id("checkbox-output")).getText()).isEqualTo("checked");

        support.selectRadio(By.id("payment-card"));
        assertThat(driver.findElement(By.id("radio-output")).getText()).isEqualTo("card");

        support.clickVisible("#primary-action");
        assertThat(driver.findElement(By.id("click-output")).getText()).isEqualTo("primary");
        support.clickFirstDisplayed(By.id("hidden-action"), By.id("alternate-action"));
        assertThat(driver.findElement(By.id("click-output")).getText()).isEqualTo("alternate");

        support.click(By.id("show-modal"));
        support.waitForModalToBeVisible("fixture-modal");
        support.click(By.id("hide-modal"));
        support.waitForModalToBeHidden("fixture-modal");
    }

    private void serveFixture(HttpExchange exchange) throws IOException {
        byte[] response = PAGE.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        } finally {
            exchange.close();
        }
    }
}
