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
  - Set input values and trigger associated handlers with `setInputValue`.
  - Wait for asynchronous DOM changes with `waitFor`.
  - Execute JavaScript snippets with `executeScript` and access resulting DOM changes.
  - Evaluate JavaScript expressions and capture their return values with `evaluateScript`.
- Verify page content using helpers like `assertThatPageTitleIs`, `assertPageBodyContains`,
  `assertFormFieldValue` and `assertThatElementWithIdIsPresent`.
- Follow redirects explicitly with `followRedirect` or configure automatic redirect following when
  constructing the class.
- Send JSON or form-encoded POST requests directly using `postJson` and `postForm`.
- Access the raw response body with `body()` for parsing JSON or other content types.
- Retrieve the HTTP status code of the last response using `status()`.
- Automatically load external scripts and images referenced by the page, and fail the test if any
  resource or script fails to load, a script throws, or a script logs to `console.error`. Use
  `ignoreJavascriptErrors()` to opt out and `javascriptErrors()` to inspect the collected errors.
- Support the `fetch` API (including method, headers and body options) within page JavaScript,
  enabling tests to trigger AJAX-driven UI updates.

`submitForm` and `submitFormBySelector` fire the submit event and honour constraint validation, so
page JavaScript validation, AJAX submit handlers and `preventDefault` behave as they would in a
browser. Use `forceSubmitForm` or `forceSubmitFormBySelector` to bypass both (as a JavaScript
`form.submit()` call would) when a test needs to reach server-side validation of input the browser
would normally block.

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
