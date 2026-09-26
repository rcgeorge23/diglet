package io.github.rcgeorge23.diglet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @ParameterizedTest
    @MethodSource("browsers")
    void canDismissExpectedConfirmAndInspectItsText(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/dialogs");
            var confirm = webTest.expectConfirm().dismiss();

            webTest.click("#confirm");

            assertThat(confirm.getText()).isEqualTo("Remove this queued email?");
            assertThat(webTest.evaluateScript("window.confirmResult").asBoolean()).isFalse();
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void canAcceptExpectedAlertAndInspectItsText(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/dialogs");
            WebTest.DialogExpectation alert = webTest.expectAlert();

            webTest.click("#alert");

            assertThat(alert.getText()).isEqualTo("Account archived");
            assertThat(webTest.evaluateScript("window.alertResult").asBoolean()).isTrue();
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void canRespondToExpectedPromptAndInspectItsText(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/dialogs");
            var prompt = webTest.expectPrompt().sendKeys("Ada");

            webTest.click("#prompt");

            assertThat(prompt.getText()).isEqualTo("Your name?");
            assertThat(webTest.evaluateScript("window.promptResult").asString()).isEqualTo("Ada");
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void unexpectedDialogFailsClearly(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/dialogs");

            assertThatThrownBy(() -> webTest.click("#unexpected"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unexpected JavaScript dialog: Unexpected dialog");
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void expectedDialogMustBeTriggeredByTheAction(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/dialogs");
            webTest.expectConfirm();

            assertThatThrownBy(() -> webTest.click("#no-dialog"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Expected a JavaScript confirm dialog, but no dialog appeared");
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void evaluateScriptReturnsEngineIndependentJavaValues(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/form");

            JsValue number = webTest.evaluateScript("1 + 1");
            assertThat(number.asObject()).isEqualTo(2.0d);
            assertThat(number.asInt()).isEqualTo(2);

            JsValue bool = webTest.evaluateScript("true");
            assertThat(bool.asObject()).isEqualTo(true);
            assertThat(bool.asBoolean()).isTrue();

            JsValue string = webTest.evaluateScript("'text'");
            assertThat(string.asObject()).isEqualTo("text");
            assertThat(string.asString()).isEqualTo("text");
            assertThat(webTest.evaluateScript("'di' + 'glet'").asObject()).isEqualTo("diglet");

            JsValue nullValue = webTest.evaluateScript("null");
            assertThat(nullValue.isNull()).isTrue();
            assertThat(nullValue.asObject()).isNull();
            assertThat(webTest.evaluateScript("undefined").isNull()).isTrue();

            Object array = webTest.evaluateScript("[1, true, 'text', null, [2]]").asObject();
            assertThat(array.getClass().getPackageName()).doesNotStartWith("org.htmlunit");
            assertThat(array).isEqualTo(Arrays.asList(1.0d, true, "text", null, List.of(2.0d)));

            Object object = webTest.evaluateScript("({number: 3, enabled: true, text: 'value', "
                    + "values: [4, 'nested'], child: {count: 5}})").asObject();
            assertThat(object.getClass().getPackageName()).doesNotStartWith("org.htmlunit");
            assertThat(object).isEqualTo(Map.of(
                    "number", 3.0d,
                    "enabled", true,
                    "text", "value",
                    "values", List.of(4.0d, "nested"),
                    "child", Map.of("count", 5.0d)));
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void evaluateScriptRejectsEngineSpecificObjects(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/form");

            assertThatThrownBy(() -> webTest.evaluateScript("document.body"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("primitive, array, or plain object");
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void formFieldValueAssertionReadsLiveInputValueAndTextareaText(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/form")
                    .setInputValue("#q", "typed")
                    .assertFormFieldValue("q", "typed");

            assertThat(webTest.document().selectFirst("[name='q']").attr("value")).isEqualTo("default");

            webTest.executeScript("document.querySelector('[name=\"q\"]').value = 'scripted';")
                    .assertFormFieldValue("q", "scripted");

            webTest.setInputValue("#notes", "typed notes");
            assertThat(webTest.evaluateScript("document.querySelector('#notes').value").asString())
                    .isEqualTo("typed notes");
            webTest.assertFormFieldValue("notes", "typed notes");

            webTest.postJson("/form", Map.of())
                    .assertFormFieldValue("notes", "default notes");
            assertThat(webTest.document().selectFirst("[name='notes']").attr("value")).isEmpty();
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void waitForPreservesDirectHttpResponseAfterBrowserNavigation(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/form")
                    .postJson("/direct-form", Map.of())
                    .waitFor(document -> {
                        var notes = document.selectFirst("[name='notes']");
                        return notes != null && "response notes".equals(notes.text());
                    })
                    .assertFormFieldValue("notes", "response notes");
        } finally {
            webTest.close();
        }
    }

    @ParameterizedTest
    @MethodSource("browsers")
    void typeIntoReplacesValueAndDispatchesKeyboardAndCommitEvents(String mode) throws Exception {
        int port = startServer();
        WebTest webTest = "web-driver".equals(mode)
                ? new WebTest(port, false, WebTest.Browser.CHROME, () -> new HtmlUnitDriver(true))
                : new WebTest(port, false, WebTest.Browser.HTML_UNIT);

        try {
            webTest.navigateTo("/form")
                    .typeInto("#q", "typed")
                    .assertFormFieldValue("q", "typed");

            String events = webTest.document().getElementById("events").text();
            assertThat(events).contains("keydown:t,keypress:t,input:t,keyup:t,"
                    + "keydown:y,keypress:y,input:ty,keyup:y,"
                    + "keydown:p,keypress:p,input:typ,keyup:p,"
                    + "keydown:e,keypress:e,input:type,keyup:e,"
                    + "keydown:d,keypress:d,input:typed,keyup:d,");
            int finalKeyUp = events.indexOf("keyup:d,");
            assertThat(events.indexOf("change,")).isGreaterThan(finalKeyUp);
            assertThat(events.indexOf("blur,")).isGreaterThan(finalKeyUp);

            webTest.typeInto("#notes", "typed notes");
            assertThat(webTest.evaluateScript("document.querySelector('#notes').value").asString())
                    .isEqualTo("typed notes");
        } finally {
            webTest.close();
        }
    }

    private int startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/form", exchange -> respond(exchange,
                "<html><head><title>Form Page</title></head><body>"
                        + "<form id='f' action='/submit' method='post'>"
                        + "<input id='q' name='q' value='default' "
                        + "onkeydown=\"document.getElementById('events').textContent += 'keydown:' + event.key + ',';\" "
                        + "onkeypress=\"document.getElementById('events').textContent += 'keypress:' + event.key + ',';\" "
                        + "oninput=\"document.getElementById('events').textContent += 'input:' + this.value + ',';\" "
                        + "onkeyup=\"document.getElementById('events').textContent += 'keyup:' + event.key + ',';\" "
                        + "onchange=\"document.getElementById('events').textContent += 'change,';\" "
                        + "onblur=\"document.getElementById('events').textContent += 'blur,';\"/>"
                        + "<output id='events'></output>"
                        + "<textarea id='notes' name='notes'>default notes</textarea>"
                        + "<button id='go' type='submit'>Go</button>"
                        + "</form></body></html>"));
        server.createContext("/submit", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, "<html><head><title>Done</title></head><body>SUBMITTED " + requestBody + "</body></html>");
        });
        server.createContext("/direct-form", exchange -> respond(exchange,
                "<html><body><textarea name='notes'>response notes</textarea></body></html>"));
        server.createContext("/dialogs", exchange -> respond(exchange,
                "<html><body>"
                        + "<button id='confirm' onclick=\"window.confirmResult = confirm('Remove this queued email?');\">Confirm</button>"
                        + "<button id='alert' onclick=\"window.alertResult = false; alert('Account archived'); window.alertResult = true;\">Alert</button>"
                        + "<button id='prompt' onclick=\"window.promptResult = prompt('Your name?', '');\">Prompt</button>"
                        + "<button id='unexpected' onclick=\"alert('Unexpected dialog');\">Unexpected</button>"
                        + "<button id='no-dialog'>No dialog</button>"
                        + "</body></html>"));
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
