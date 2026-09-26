# Diglet

Diglet is a lightweight, fluent Java library for exercising HTTP endpoints and server-rendered HTML
pages in tests without needing a real browser. It is useful for end-to-end testing of web
applications: navigate, fill in and submit forms, follow redirects, run page JavaScript, and assert
on the resulting DOM.

It builds on the JDK `HttpClient`, parses HTML with [jsoup](https://jsoup.org/) and executes
JavaScript using the in-process [HtmlUnit](https://www.htmlunit.org/) browser engine. For more
realistic end-to-end tests, Diglet can also drive real browsers via Selenium by selecting
`WebTest.Browser.CHROME` or `WebTest.Browser.FIREFOX`, or by supplying a custom `WebDriver`
supplier.

## Requirements

* Java 25 or newer
* Gradle or Maven

## Installation

Gradle:

```groovy
testImplementation 'io.github.rcgeorge23:diglet:0.1.0'
```

Maven:

```xml
<dependency>
    <groupId>io.github.rcgeorge23</groupId>
    <artifactId>diglet</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

## Capabilities

- Navigate to pages and inspect the response with `navigateTo`.
- Drive headless Chrome or Firefox via Selenium by selecting `WebTest.Browser.CHROME` or
  `WebTest.Browser.FIREFOX`.
- Reuse one browser process across tests with `WebDriverPool`, which clears cookies and web storage
  between uses so real-browser suites stay fast and isolated.
- Reuse one browser process across tests with `WebDriverPool` to avoid per-test browser startup;
  cookies and web storage are cleared between tests.
- Assert status codes and redirects via methods such as `assertStatusIs`, `assertRedirectIs` and
  `assertRedirectEndsWith`.
- Interact with the DOM:
  - Submit forms by id or CSS selector, including file uploads, using `submitForm` /
    `submitFormBySelector`. These behave like a user submitting the form in a browser: the submit
    event fires (so page JavaScript handlers and AJAX flows run) and the browser's constraint
    validation is enforced.
  - Submit forms while bypassing client-side validation and submit handlers (equivalent to calling
    `form.submit()` from JavaScript) using `forceSubmitForm` / `forceSubmitFormBySelector`, for
    tests that exercise server-side validation of input a browser would refuse to submit.
  - Click links and buttons with `click`. Clicking an element that is hidden or disabled fails the
  test like a real browser would; use `forceClick` to click such an element explicitly.
  - Set input values and trigger the `input` event with `setInputValue`, or replace text using
    browser keyboard events and trigger commit handlers with `typeInto`.
  - Wait for asynchronous DOM changes with `waitFor`.
  - Execute JavaScript snippets with `executeScript` and access resulting DOM changes.
  - Evaluate JavaScript expressions and capture their return values with `evaluateScript`.
- Verify page content using helpers like `assertThatPageTitleIs`, `assertPageBodyContains`,
  `assertFormFieldValue` and `assertThatElementWithIdIsPresent`.
- `assertFormFieldValue` checks the live value property for inputs in a browser page, so it sees
  values changed by `setInputValue` or JavaScript even when the original HTML `value` attribute is
  unchanged. Textareas continue to use their serialized text content.
- Verify visible page text with `assertPageTextContains` / `assertPageTextDoesNotContain`; use the
  raw-HTML `assertPageBodyContains` / `assertPageBodyDoesNotContain` when you need to match markup,
  comments or script content that a user would not see.
- Follow redirects explicitly with `followRedirect` or configure automatic redirect following when
  constructing the class.
- Send JSON or form-encoded POST requests directly using `postJson` and `postForm`.
- Access the raw response body with `body()` for parsing JSON or other content types.
- Retrieve the HTTP status code of the last response using `status()`.
- Automatically load external scripts and images referenced by the page, and fail the test if any
  resource or script fails to load, a script throws, or a script logs to `console.error`. Use
  `ignoreJavascriptErrors()` to opt out and `javascriptErrors()` to inspect the collected errors.
- Fill common Web API gaps in HtmlUnit mode with built-in shims (`queueMicrotask`, `structuredClone`,
  `requestIdleCallback`/`cancelIdleCallback`, `ResizeObserver` and the `Symbol` polyfill) so more
  modern page scripts run without a real browser.
- Support the `fetch` API (including method, headers and body options) within page JavaScript,
  enabling tests to trigger AJAX-driven UI updates.

## HtmlUnit JavaScript compatibility

HtmlUnit does not implement every modern JavaScript feature. In `Browser.HTML_UNIT` mode, Diglet
detects common ES class and `async`/`await` syntax in inline and external scripts, as well as
`<script type="module">`, and fails with a diagnostic that identifies the source and recommends
`Browser.CHROME` or `Browser.FIREFOX`. This is targeted detection, not a complete ECMAScript parser.

Use a real browser for flows that depend on those features. If JavaScript errors are irrelevant to a
server-rendered test, `ignoreJavascriptErrors()` lets navigation continue and `javascriptErrors()`
still exposes the diagnostics; it does not make unsupported scripts execute. Use
`withJavaScriptEnabled(false)` when a test intentionally needs only the server-rendered markup.

`evaluateScript(expression)` returns a `JsValue` in both browser modes. Its `asObject()` method
exposes Java values rather than engine-specific wrappers: numbers are `Double`, booleans are
`Boolean`, strings are `String`, JavaScript `null` and `undefined` are `null`, arrays are recursively
converted to `List<Object>`, and plain objects to `Map<String, Object>`. Returning a DOM node,
function, or another non-plain JavaScript object throws `IllegalArgumentException` instead of
exposing a browser-engine object. Use `executeScript` for side-effect-only scripts, including ones
that start asynchronous work; use `evaluateScript` when you need a supported return value.

`submitForm` and `submitFormBySelector` fire the submit event and honour constraint validation, so
page JavaScript validation, AJAX submit handlers and `preventDefault` behave as they would in a
browser. Use `forceSubmitForm` or `forceSubmitFormBySelector` to bypass both (as a JavaScript
`form.submit()` call would) when a test needs to reach server-side validation of input the browser
would normally block.

## JavaScript dialogs

Declare the next dialog before triggering the action. Expectations accept dialogs by default; use
`dismiss()` for a confirmation or prompt, or `sendKeys()` to answer a prompt:

```java
var confirmation = webTest.expectConfirm().dismiss();
webTest.click("#remove");
assertThat(confirmation.getText()).isEqualTo("Remove this item?");

var prompt = webTest.expectPrompt().sendKeys("Ada");
webTest.click("#rename");
assertThat(prompt.getText()).isEqualTo("New name?");
```

`expectAlert()` handles alerts, while `expectConfirm()` and `expectPrompt()` expose the selected
result through the page's JavaScript. If an expected dialog does not appear, or an unanticipated
dialog appears, the action fails with an `IllegalStateException`. This remains true when
`ignoreJavascriptErrors()` is enabled. The default Chrome and Firefox drivers leave prompts open for
`WebTest` to handle; custom WebDriver suppliers should use the `unhandledPromptBehavior=ignore`
capability as well.

## Typical usage

Create an instance pointing at the local server under test and chain actions and assertions:

```java
int port = 8080;
new WebTest(port)
        .navigateTo("/login")
        .submitForm("loginForm", Map.of("username", "alice", "password", "secret"))
        .followRedirect()
        .assertStatusIs(HttpStatus.OK)
        .assertPageBodyContains("Welcome, alice");
```

Clicking a link and executing JavaScript is just as straightforward:

```java
new WebTest(port)
        .navigateTo("/page1")
        .click("#next")
        .executeScript("highlight()")
        .assertPageBodyContains("Step 2 highlighted");
```

Refer to [`WebTestIntegrationTest`](src/test/java/io/github/rcgeorge23/diglet/WebTestIntegrationTest.java)
for more examples.

## Running the same flow in more than one browser

Write the flow once and parameterise the browser, so it can run in the fast in-process
`Browser.HTML_UNIT` mode on every commit and in a real browser (for example headless Chrome) on a
schedule:

```java
static Stream<Browser> browsers() {
    return Stream.of(Browser.HTML_UNIT, Browser.CHROME);
}

@ParameterizedTest
@MethodSource("browsers")
void userCanLogIn(Browser browser) throws Exception {
    try (WebTest webTest = new WebTest(port, false, browser)) {
        webTest.navigateTo("/login")
                .submitForm("loginForm", Map.of("username", "alice", "password", "secret"))
                .assertPageBodyContains("Welcome, alice");
    }
}
```

A custom `Supplier<WebDriver>` can be supplied for non-default drivers, and `WebDriverPool` reuses
one browser process across tests.

After clicking a `target="_blank"` link in HTML_UNIT mode, `WebTest` stays on the original window;
the opened window is not exposed for selection. HTTP status is available for HtmlUnit pages and
direct HTTP responses, but not after WebDriver navigation. Prefer assertions on page content and URLs
for flows that must pass in both modes. See the diglet test suite (`WebTestDriverAgnosticTest`) for a
worked example.

## Running the same flow in more than one browser

`WebTest` is driver-agnostic: the same test body can run in the fast in-process `HTML_UNIT` engine
and in a real browser, so browser coverage can be a small scheduled subset instead of duplicated
test code. Parameterise the test by mode and construct the `WebTest` accordingly:

```java
static Stream<String> modes() {
    return Stream.of("html-unit", "chrome");
}

@ParameterizedTest
@MethodSource("modes")
void theSameFlowRunsInBothModes(String mode) {
    WebTest webTest = "chrome".equals(mode)
            ? new WebTest(port, false, WebTest.Browser.CHROME) // or a custom WebDriver supplier
            : new WebTest(port, false, WebTest.Browser.HTML_UNIT);
    try {
        webTest.navigateTo("/form")
                .assertThatPageTitleIs("Form Page")
                .setInputValue("#q", "hello")
                .click("#go")
                .assertPageBodyContains("q=hello");
    } finally {
        webTest.close();
    }
}
```

Assertions that behave identically in both modes are content-based (`assertPageBodyContains`,
`assertPageTextContains`), URL-based (`assertCurrentUrlIs`), title-based, and `evaluateScript`
results. HtmlUnit keeps the original window after a `target="_blank"` click but does not expose the
new window for selection; use WebDriver for flows that need to interact with the opened window. HTTP
status is unavailable after WebDriver navigation. For long-running browser suites, share one browser
process across tests with `WebDriverPool`.

## Building and testing

```bash
./gradlew build
```

The test suite spins up local HTTP servers, exercises the HtmlUnit browser, JavaScript polyfills and
form handling, and requires no external configuration.

## Releasing

Releases are published to Maven Central through the Sonatype Central Portal. To cut a release, tag
the commit with a `v`-prefixed version (for example `v0.1.0`) and push the tag; the release workflow
runs the build and uploads the signed artifacts. The `version` defaults to the value in
`gradle.properties` and is overridden by the tag name during the release. The workflow can also be
started manually with an explicit version.

Publishing requires the repository secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
`MAVEN_SIGNING_KEY` and `MAVEN_SIGNING_PASSWORD`.

## Licence

Diglet is licensed under the [Apache License, Version 2.0](LICENSE).
