# Diglet

**Fast browser-like integration tests for Java web applications.**

Diglet lets Java tests send HTTP requests, navigate server-rendered pages, complete forms, execute page JavaScript, and assert on the resulting DOM through one fluent API. It uses [HtmlUnit](https://www.htmlunit.org/) in process by default, so most tests do **not** need Chrome, a browser driver, or an external service.

Use the same `WebTest` API with Selenium-driven Chrome or Firefox when you need real browser behaviour. Optional Lightpanda backends are available for more specialised workloads.

- **Works with any HTTP-served application** — no Spring Boot, Micronaut, or other framework integration is required.
- **Test the complete request/response flow** — including cookies, redirects, forms, resource loading, and JavaScript-driven UI changes.
- **Prefer fast tests; opt into browser fidelity** — keep the bulk of a suite in HtmlUnit and run selected flows in Chrome/Firefox.
- **Buildable from source** — Java 25, Gradle wrapper, and local test fixtures.

## Get started

### 1. Add the test dependency

Requirements: **JDK 25 or newer**, Maven Central, and an application or local HTTP fixture to exercise.

Use the latest published `io.github.rcgeorge23:diglet` version from [Maven Central](https://central.sonatype.com/artifact/io.github.rcgeorge23/diglet). The repository's [`gradle.properties`](gradle.properties) records the current source version; a source version is not necessarily published yet.

**Gradle (Groovy DSL)**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    testImplementation 'io.github.rcgeorge23:diglet:REPLACE_WITH_PUBLISHED_VERSION'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.12.2'
}

test {
    useJUnitPlatform()
}
```

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("io.github.rcgeorge23:diglet:REPLACE_WITH_PUBLISHED_VERSION")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.test {
    useJUnitPlatform()
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.rcgeorge23</groupId>
    <artifactId>diglet</artifactId>
    <version>REPLACE_WITH_PUBLISHED_VERSION</version>
    <scope>test</scope>
</dependency>
```

Configure JUnit 5 in your application's build as usual. Diglet exposes the web-testing API; it does not start your application or manage your test server.

### 2. Write a test against your application

Assume your application is running on `localhost:8080` and exposes a `GET /login` page with a form `id="loginForm"`. Submitting the form returns a page containing `Welcome, alice`:

```java
import io.github.rcgeorge23.diglet.WebTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

class LoginTest {
    @Test
    void userCanLogIn() throws Exception {
        try (WebTest web = new WebTest(8080)) {
            web.navigateTo("/login")
               .submitForm("loginForm", Map.of(
                   "username", "alice",
                   "password", "secret"))
               .assertPageTextContains("Welcome, alice");
        }
    }
}
```

Replace the port, path, form ID, and expected text with those used by your app. `WebTest` is `AutoCloseable`: prefer try-with-resources, particularly for Selenium or Lightpanda backends.

### 3. Try it without an application

This complete JUnit test starts an ephemeral HTTP server, exercises a real HTTP response, checks the DOM, and shuts the server down. It requires no database or separate web server:

```java
import com.sun.net.httpserver.HttpServer;
import io.github.rcgeorge23.diglet.WebTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DigletSmokeTest {
    @Test
    void rendersPageFromLocalServer() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] html = """
                    <!doctype html>
                    <html><head><title>Example</title></head>
                    <body><main>Hello from Diglet</main></body></html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, html.length);
            try (var output = exchange.getResponseBody()) {
                output.write(html);
            }
        });
        server.start();

        try (WebTest web = new WebTest(server.getAddress().getPort())) {
            web.navigateTo("/")
               .assertStatusIs(HttpStatus.OK)
               .assertThatPageTitleIs("Example")
               .assertPageTextContains("Hello from Diglet");

            assertEquals("Example",
                    web.evaluateScript("document.title").asString());
        } finally {
            server.stop(0);
        }
    }
}
```

See [`WebTestIntegrationTest`](src/test/java/io/github/rcgeorge23/diglet/WebTestIntegrationTest.java) for further runnable server-backed examples.

## Common recipes

### Forms, client-side events, and asynchronous updates

```java
try (WebTest web = new WebTest(8080)) {
    web.navigateTo("/profile")
       .setInputValue("#displayName", "Ada")
       .click("#save")
       .waitFor(page -> page.selectFirst("#notification") != null)
       .assertPageTextContains("Profile saved");
}
```

`setInputValue` updates an input and fires its `input` event. Use `typeInto` when the page requires keyboard and commit events. `submitForm("profileForm", Map.of(...))` fires the form submit event and honours browser constraint validation. To test server-side validation that a browser would normally prevent, use `forceSubmitForm` (or the CSS-selector equivalents).

`waitFor` polls the parsed DOM while asynchronous UI work progresses. For example, use `waitFor(page -> page.selectFirst("#result") != null)` after a JavaScript-driven fetch.

### HTTP requests and status assertions

```java
import org.springframework.http.HttpStatus;

try (WebTest web = new WebTest(8080)) {
    web.postJson("/api/notes", Map.of("text", "Buy milk"))
       .assertStatusIs(HttpStatus.OK);

    String responseBody = web.body(); // Raw response; parse JSON with your preferred library
}
```

`postForm` sends form-encoded data. `status()` returns the numeric status for responses where status information is available; `assertRedirectIs`, `assertRedirectEndsWith` and `followRedirect` cover explicit HTTP redirect flows.

### JavaScript, document inspection, and dialogs

```java
try (WebTest web = new WebTest(8080)) {
    web.navigateTo("/calculator")
       .executeScript("document.querySelector('#value').textContent = '42'");

    int result = web.evaluateScript(
            "Number(document.querySelector('#value').textContent)").asInt();

    var heading = web.document().selectFirst("h1"); // jsoup Document
}
```

`evaluateScript` returns `JsValue` with engine-independent accessors (for example `asString()`, `asInt()`, `asBoolean()`, and `asObject()`). Plain JavaScript arrays and objects are converted to Java lists and maps. Do not return DOM nodes or functions; use `document()` to inspect the HTML instead.

Declare a JavaScript dialog expectation **before** the click that opens it:

```java
try (WebTest web = new WebTest(8080)) {
    web.navigateTo("/settings");

    var confirmation = web.expectConfirm().dismiss();
    web.click("#delete");
    assertEquals("Delete this entry?", confirmation.getText());

    var prompt = web.expectPrompt().sendKeys("Ada");
    web.click("#rename");
    assertEquals("New name?", prompt.getText());
}
```

The example assumes the page has matching buttons and dialog messages. Also available: `expectAlert()`, `expectConfirm().accept()`, and `expectPrompt().dismiss()`. Unexpected or missing dialogs cause a test failure.

## Choosing a browser backend

| Mode | When to use it | Extra setup |
| --- | --- | --- |
| `Browser.HTML_UNIT` (**default**) | Fast server-rendered UI, HTTP, cookies, forms and supported JavaScript | None |
| `Browser.CHROME` / `Browser.FIREFOX` | Layout, computed styles, richer JavaScript, genuine browser interactions | Compatible browser/driver via Selenium |
| `Browser.LIGHTPANDA` | Modern JavaScript against an existing Lightpanda CDP service | Run and supply a CDP endpoint |
| `Browser.EMBEDDED_LIGHTPANDA` | In-process Lightpanda via the native embedding ABI | Add separate Java binding and compatible native shared library |

Switch engines without rewriting the test:

```java
import io.github.rcgeorge23.diglet.WebTest;
import io.github.rcgeorge23.diglet.WebTest.Browser;

try (WebTest web = new WebTest(8080, Browser.HTML_UNIT)) {
    web.navigateTo("/search")
       .assertThatPageTitleIs("Search");
}

// For a selected real-browser test (Chrome must be available):
try (WebTest web = new WebTest(8080, Browser.CHROME)) {
    web.navigateTo("/search")
       .assertThatPageTitleIs("Search");
}
```

A JUnit 5 parameterised test can run the same flow in both modes:

```java
static java.util.stream.Stream<WebTest.Browser> browsers() {
    return java.util.stream.Stream.of(
            WebTest.Browser.HTML_UNIT, WebTest.Browser.CHROME);
}

@org.junit.jupiter.params.ParameterizedTest
@org.junit.jupiter.params.provider.MethodSource("browsers")
void searchPageLoadsInBothEngines(WebTest.Browser browser) throws Exception {
    try (WebTest web = new WebTest(8080, browser)) {
        web.navigateTo("/search")
           .assertPageTextContains("Search");
    }
}
```

Add `junit-jupiter-params` to your test dependencies if it is not already provided by your test framework. A full example using a custom WebDriver supplier is in [`WebTestDriverAgnosticTest`](src/test/java/io/github/rcgeorge23/diglet/WebTestDriverAgnosticTest.java).

**Important differences:** after Selenium/Lightpanda browser navigation, Diglet does not always have an HTTP response status. Prefer page content, URL and title assertions when testing across backends. HtmlUnit does not expose a newly opened `target="_blank"` window for selection. Use Chrome/Firefox for flows requiring that behaviour.

### Reuse a Selenium browser process

`WebDriverPool` lets multiple tests share a browser process instead of launching Chrome repeatedly:

```java
import io.github.rcgeorge23.diglet.WebDriverPool;
import io.github.rcgeorge23.diglet.WebTest;
import org.openqa.selenium.chrome.ChromeDriver;

try (WebDriverPool pool = new WebDriverPool(ChromeDriver::new)) {
    try (WebTest first = new WebTest(
            8080, false, WebTest.Browser.CHROME, pool)) {
        first.navigateTo("/").assertPageTextContains("Home");
    }
    pool.reset(); // Clear cookies and web storage before the next test
    try (WebTest second = new WebTest(
            8080, false, WebTest.Browser.CHROME, pool)) {
        second.navigateTo("/").assertPageTextContains("Home");
    }
}
```

Manage the pool's lifecycle in your own test framework. Diglet also provides `ChromeDriverFactory` and `SeleniumWebDriverTestSupport` in [`io.github.rcgeorge23.diglet.selenium`](src/main/java/io/github/rcgeorge23/diglet/selenium) for optional headless Chrome test helpers. These helpers do not install a JUnit or Spring lifecycle.

### External Lightpanda (CDP)

Provision and start Lightpanda yourself (for example `lightpanda serve --host 127.0.0.1 --port 9222`), then connect:

```java
import java.net.URI;
import io.github.rcgeorge23.diglet.WebTest;

try (WebTest web = new WebTest(
        8080,
        WebTest.Browser.LIGHTPANDA,
        URI.create("http://127.0.0.1:9222"))) {
    web.navigateTo("/").assertPageTextContains("Welcome");
}
```

This backend is DOM-oriented, **not** a layout-fidelity replacement for Chrome. Diglet does not download, launch or stop Lightpanda. Browser status codes, dialog expectations, and cookie synchronisation with Diglet's direct HTTP client are not available in this mode. Review Lightpanda's AGPL-3.0 licence before provisioning it.

### Embedded Lightpanda (experimental)

`Browser.EMBEDDED_LIGHTPANDA` uses the separate [lightpanda-java](https://github.com/rcgeorge23/lightpanda-java) FFM binding and a compatible native `liblightpanda.so` instead of CDP. Diglet intentionally **does not** include this AGPL-licensed binding as a runtime dependency.

```kotlin
dependencies {
    testRuntimeOnly("io.github.rcgeorge23:lightpanda-java:REPLACE_WITH_PUBLISHED_VERSION")
}
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("lightpanda.library", "/absolute/path/to/liblightpanda.so")
}
```

The selected binding version must provide `Session.pump()`, `Session.drainConsoleMessages()` and `Session.installJavascriptErrorCapture()`. The embedded engine serialises native calls on a JVM-wide owner thread. Its JavaScript error capture begins **after** navigation, so initial page-load, iframe and worker errors can be missed. Neither layout fidelity nor navigation response status is available.

See the [binding README](https://github.com/rcgeorge23/lightpanda-java#readme) for native build instructions and ABI limitations. To exercise the project's opt-in native integration tests on Linux x86_64 (Zig 0.16.0 and Rust required):

```sh
./gradle/build-lightpanda-c-api.sh
./gradlew embeddedLightpandaTest \
  -PlightpandaLibrary="$HOME/.cache/diglet/lightpanda-c-api/zig-out/lib/liblightpanda.so"
```

These tests do **not** run as part of the default build.

## JavaScript compatibility and diagnostics

HtmlUnit supports many common web features, including `fetch`, DOM events, and Diglet shims for `queueMicrotask`, `structuredClone`, `ResizeObserver`, `requestIdleCallback` and other APIs. It is not a full modern browser. Diglet diagnoses unsupported syntax such as JavaScript classes, `async`/`await`, and module scripts, and recommends a real browser when necessary. The detection is deliberately not a complete JavaScript parser.

By default script failures, failed resources, and `console.error` can fail a test. Call `ignoreJavascriptErrors()` to continue while retaining diagnostic messages in `javascriptErrors()`; it does not make unsupported JavaScript execute. Use `withJavaScriptEnabled(false)` for tests which intentionally exercise only server-rendered HTML.

## Contributing and running the project

```sh
git clone https://github.com/rcgeorge23/diglet.git
cd diglet
./gradlew build             # Unit/integration tests with local HTTP fixtures
./gradlew seleniumTest      # Optional Chrome tests (requires Chrome/driver)
```

The standard build requires no running external application. The native Lightpanda integration task is opt-in as described above.

Issues and contributions: [GitHub issues](https://github.com/rcgeorge23/diglet/issues). Releases are published through Maven Central from version tags; repository maintainers use the [release workflow](.github/workflows/release.yml).

## Licence

[Apache License, Version 2.0](LICENSE). Optional Lightpanda components have separate AGPL-3.0 licensing considerations.
