package io.github.rcgeorge23.diglet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.Date;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.htmlunit.corejs.javascript.Undefined;
import org.htmlunit.util.Cookie;

import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.WebResponseData;
import org.htmlunit.StringWebResponse;
import org.htmlunit.util.WebConnectionWrapper;
import org.htmlunit.HttpMethod;
import org.htmlunit.util.NameValuePair;
import org.htmlunit.Page;
import org.htmlunit.ScriptException;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlFileInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlTextArea;
import org.htmlunit.html.SubmittableElement;
import org.htmlunit.javascript.JavaScriptErrorListener;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.Select;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple fluent API for performing web interactions in tests.
 */
@Slf4j
public class WebTest implements AutoCloseable {

    public enum Browser {
        HTML_UNIT,
        CHROME,
        FIREFOX
    }

    /**
     * Matches self-closing syntax for non-void HTML elements, for example {@code <select .../>}.
     * HtmlUnit serialises empty elements this way, but Jsoup treats a self-closed {@code select}
     * as an open select and drops all following markup until a closing tag, so these elements are
     * expanded before the serialised page is parsed. Void elements are deliberately excluded.
     */
    private static final Pattern NON_VOID_SELF_CLOSING_ELEMENT = Pattern.compile(
            "<(?!area\\b|base\\b|br\\b|col\\b|embed\\b|hr\\b|img\\b|input\\b|link\\b|meta\\b|param\\b|source\\b|track\\b|wbr\\b)"
                    + "([a-zA-Z][a-zA-Z0-9]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)/>");

    private final String baseUrl;
    private final URI baseUri;
    private final HttpClient client;
    private final CookieManager cookieManager;
    private final boolean automaticallyFollowRedirects;
    private HttpResponse<String> lastResponse;
    private WebResponse lastWebResponse;
    private int lastStatus;
    private final Browser browser;
    private BrowserConsole console;
    private WebClient htmlClient;
    private HtmlPage htmlPage;
    private WebDriver webDriver;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Document currentDocument;
    private String renderedHtml;
    private String currentUrl;


    static boolean shouldIgnoreBootstrapScriptError(String details) {
        return details != null && details.contains("bootstrap.bundle.min");
    }
    private void syncCookiesFromHtmlUnitToHttpClient() {
        if (browser != Browser.HTML_UNIT) {
            return;
        }
        var store = cookieManager.getCookieStore();
        for (Cookie c : htmlClient.getCookieManager().getCookies()) {
            HttpCookie hc = new HttpCookie(c.getName(), c.getValue());
            hc.setDomain(c.getDomain());
            hc.setPath(c.getPath());
            hc.setSecure(c.isSecure());
            if (c.getExpires() != null) {
                long maxAge = (c.getExpires().getTime() - System.currentTimeMillis()) / 1000;
                hc.setMaxAge(maxAge);
            }
            store.add(baseUri, hc);
        }
    }

    private void syncCookiesFromHttpClientToHtmlUnit() {
        if (browser != Browser.HTML_UNIT) {
            return;
        }
        var htmlStore = htmlClient.getCookieManager();
        htmlStore.clearCookies();
        for (HttpCookie hc : cookieManager.getCookieStore().get(baseUri)) {
            Date expires = hc.getMaxAge() >= 0 ? new Date(System.currentTimeMillis() + hc.getMaxAge() * 1000) : null;
            Cookie c = new Cookie(baseUri.getHost(), hc.getName(), hc.getValue(), hc.getPath(), expires, hc.getSecure());
            htmlStore.addCookie(c);
        }
    }

    private void syncCookiesFromWebDriverToHttpClient() {
        if (!usesWebDriver()) {
            return;
        }
        var store = cookieManager.getCookieStore();
        for (org.openqa.selenium.Cookie c : webDriver.manage().getCookies()) {
            HttpCookie hc = new HttpCookie(c.getName(), c.getValue());
            hc.setDomain(c.getDomain());
            hc.setPath(c.getPath());
            hc.setSecure(c.isSecure());
            if (c.getExpiry() != null) {
                long maxAge = (c.getExpiry().getTime() - System.currentTimeMillis()) / 1000;
                hc.setMaxAge(maxAge);
            }
            store.add(baseUri, hc);
        }
    }

    private void syncCookiesFromHttpClientToWebDriver() {
        if (!usesWebDriver()) {
            return;
        }
        webDriver.manage().deleteAllCookies();
        for (HttpCookie hc : cookieManager.getCookieStore().get(baseUri)) {
            org.openqa.selenium.Cookie.Builder builder = new org.openqa.selenium.Cookie.Builder(hc.getName(), hc.getValue())
                    .domain(baseUri.getHost())
                    .path(hc.getPath())
                    .isSecure(hc.getSecure());
            if (hc.getMaxAge() >= 0) {
                builder.expiresOn(new Date(System.currentTimeMillis() + hc.getMaxAge() * 1000));
            }
            webDriver.manage().addCookie(builder.build());
        }
    }

    private boolean usesWebDriver() {
        return browser == Browser.CHROME || browser == Browser.FIREFOX;
    }

    private void updateFromDriver() {
        renderedHtml = webDriver.getPageSource();
        String url = webDriver.getCurrentUrl();
        currentUrl = url;
        currentDocument = parseDocument(renderedHtml, url);
        lastStatus = 200;
    }

    private Supplier<WebDriver> defaultDriverSupplier(Browser browser) {
        return () -> {
            if (browser == Browser.CHROME) {
                ChromeOptions options = new ChromeOptions();
                options.addArguments("--headless=new");
                return new ChromeDriver(options);
            } else if (browser == Browser.FIREFOX) {
                FirefoxOptions options = new FirefoxOptions();
                options.addArguments("--headless");
                return new FirefoxDriver(options);
            } else {
                throw new IllegalArgumentException("Unsupported browser: " + browser);
            }
        };
    }

    public WebTest(int port) {
        this(port, false, Browser.HTML_UNIT);
    }

    public WebTest(int port, boolean automaticallyFollowRedirects) {
        this(port, automaticallyFollowRedirects, Browser.HTML_UNIT);
    }

    public WebTest(int port, boolean automaticallyFollowRedirects, Browser browser) {
        this(port, automaticallyFollowRedirects, browser, null);
    }

    public WebTest(int port, boolean automaticallyFollowRedirects, Browser browser, Supplier<WebDriver> driverSupplier) {
        this.baseUrl = "http://localhost:" + port;
        this.baseUri = URI.create(baseUrl);
        this.automaticallyFollowRedirects = automaticallyFollowRedirects;
        this.browser = browser;
        this.cookieManager = new CookieManager();
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient.Redirect redirectPolicy = automaticallyFollowRedirects
                ? HttpClient.Redirect.NORMAL
                : HttpClient.Redirect.NEVER;
        this.client = HttpClient.newBuilder()
                .cookieHandler(this.cookieManager)
                .followRedirects(redirectPolicy)
                .build();

        if (browser == Browser.HTML_UNIT) {
            this.console = new BrowserConsole();
            this.htmlClient = new WebClient();
            // Disable HtmlUnit response caching so we always fetch the latest page content.
            // When the application allows caching headers (for production static assets),
            // HtmlUnit will otherwise reuse cached HTML responses between requests which
            // causes our WebTest flows to observe stale pages and hang while waiting for
            // updates that never arrive.
            htmlClient.getCache().setMaxSize(0);
            htmlClient.getCache().clear();
            htmlClient.getOptions().setHistoryPageCacheLimit(0);
            htmlClient.getOptions().setRedirectEnabled(automaticallyFollowRedirects);
            htmlClient.getOptions().setThrowExceptionOnScriptError(false);
            htmlClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            htmlClient.getCookieManager().setCookiesEnabled(true);
            htmlClient.getOptions().setFetchPolyfillEnabled(true);
            htmlClient.setWebConnection(new WebConnectionWrapper(htmlClient.getWebConnection()) {
                @Override
                public WebResponse getResponse(WebRequest request) throws IOException {
                    var url = request.getUrl();
                    if (url.getHost() != null && !url.getHost().equalsIgnoreCase(baseUri.getHost())) {
                        return new StringWebResponse("", url);
                    }
                    WebResponse response = super.getResponse(request);
                    return injectHtmlUnitPolyfills(response, request);
                }
            });
            htmlClient.setJavaScriptErrorListener(new JavaScriptErrorListener() {
                @Override
                public void scriptException(HtmlPage page, ScriptException scriptException) {
                    String message = scriptException.getMessage();
                    if (!shouldIgnoreBootstrapScriptError(message)) {
                        console.getErrors().add(message);
                    }
                }

                @Override
                public void timeoutError(HtmlPage page, long allowedTime, long executionTime) {
                    console.getErrors().add("Timeout executing script");
                }

                @Override
                public void malformedScriptURL(HtmlPage page, String url, MalformedURLException malformedURLException) {
                    console.getErrors().add("Malformed script URL: " + url);
                }

                @Override
                public void loadScriptError(HtmlPage page, java.net.URL scriptUrl, Exception exception) {
                    console.getErrors().add("Error loading script: " + scriptUrl);
                }

                @Override
                public void warn(String message, String sourceName, int line, String lineSource, int lineOffset) {
                    if (!shouldIgnoreBootstrapScriptError(sourceName)) {
                        console.getErrors().add("JavaScript warning: " + message);
                    }
                }
            });
        } else {
            this.console = new BrowserConsole();
            if (usesWebDriver()) {
                Supplier<WebDriver> supplier = driverSupplier != null ? driverSupplier : defaultDriverSupplier(browser);
                this.webDriver = supplier.get();
            }
        }
    }

    /**
     * Enables or disables JavaScript before loading pages with the HtmlUnit browser.
     *
     * <p>Server-rendered integration tests can disable JavaScript to avoid downloading
     * and evaluating every browser bundle when they only need to inspect the response
     * markup. Real-browser and JavaScript behaviour tests should keep the default.</p>
     */
    public WebTest withJavaScriptEnabled(boolean enabled) {
        if (browser != Browser.HTML_UNIT) {
            throw new IllegalStateException("JavaScript configuration is only available for the HtmlUnit browser");
        }
        htmlClient.getOptions().setJavaScriptEnabled(enabled);
        return this;
    }

    private WebResponse injectHtmlUnitPolyfills(WebResponse response, WebRequest request) {
        if (response == null) {
            return null;
        }

        String contentType = response.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            return response;
        }

        String content = response.getContentAsString();
        String headTag = "<head>";
        int headIndex = content.indexOf(headTag);
        if (headIndex < 0) {
            return response;
        }

        String symbolPolyfill = "<script>if(typeof Symbol==='undefined'){window.Symbol=function Symbol(description){return '@@symbol:' + (description||'') + ':' + Math.random().toString(36).slice(2);};window.Symbol.iterator='@@iterator';}</script>";
        String updatedContent = content.substring(0, headIndex + headTag.length()) + symbolPolyfill + content.substring(headIndex + headTag.length());
        byte[] updatedBytes = updatedContent.getBytes(StandardCharsets.UTF_8);

        WebResponseData data = new WebResponseData(
                updatedBytes,
                response.getStatusCode(),
                response.getStatusMessage(),
                response.getResponseHeaders());
        return new WebResponse(data, request, response.getLoadTime());
    }

    private static void disableClientSideValidation(HtmlForm form) {
        for (String tagName : List.of("input", "select", "textarea")) {
            for (DomElement element : form.getElementsByAttribute(tagName, "required", "*")) {
                element.removeAttribute("required");
            }
        }
    }

    public WebTest submitForm(String formId, Map<String, String> values) throws IOException, InterruptedException {
        return submitForm(formId, values, Map.of());
    }

    public WebTest submitForm(String formId, Map<String, String> values, Map<String, Path> files) throws IOException, InterruptedException {
        return submitFormBySelector("#" + formId, values, files);
    }

    public WebTest submitFormBySelector(String selector, Map<String, String> values) throws IOException, InterruptedException {
        return submitFormBySelector(selector, values, Map.of());
    }

    public WebTest submitFormBySelector(String selector, Map<String, String> values, Map<String, Path> files) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            HtmlForm form = (HtmlForm) htmlPage.querySelector(selector);
            if (form == null) {
                throw new IllegalArgumentException("Form '" + selector + "' not found");
            }
            form.setAttribute("novalidate", "novalidate");
            disableClientSideValidation(form);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                DomElement field = form.getFirstByXPath(".//*[@name='" + entry.getKey() + "']");
                if (field instanceof HtmlCheckBoxInput checkboxInput) {
                    checkboxInput.setChecked(parseBooleanFieldValue(entry.getValue()));
                } else if (field instanceof HtmlRadioButtonInput) {
                    DomElement selectedOption = form.getFirstByXPath(
                            ".//input[@type='radio' and @name='" + entry.getKey() + "' and @value='" + entry.getValue() + "']");
                    if (selectedOption instanceof HtmlRadioButtonInput radioButtonInput) {
                        radioButtonInput.setChecked(true);
                    }
                } else if (field instanceof HtmlInput input) {
                    input.setValue(entry.getValue());
                } else if (field instanceof HtmlTextArea area) {
                    area.setText(entry.getValue());
                } else if (field instanceof HtmlSelect select) {
                    select.setSelectedAttribute(entry.getValue(), true);
                }
            }
            for (Map.Entry<String, Path> entry : files.entrySet()) {
                DomElement field = form.getFirstByXPath(".//*[@name='" + entry.getKey() + "']");
                if (field instanceof HtmlFileInput fileInput) {
                    Path filePath = entry.getValue();
                    fileInput.setValueAttribute(filePath.toString());
                    fileInput.setFiles(filePath.toFile());
                }
            }
            HtmlElement submit = form.getFirstByXPath(
                    ".//button[not(@type) or translate(@type, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')='submit']"
                            + "|.//input[translate(@type, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')='submit']");
            if (submit == null) {
                String formId = form.getId();
                if (formId != null && !formId.isBlank()) {
                    String externalSubmitXpath = "//*[@form='" + formId + "' and (local-name()='button' or local-name()='input')"
                            + " and (not(@type) or translate(@type, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')='submit')][1]";
                    submit = htmlPage.getDocumentElement().getFirstByXPath(externalSubmitXpath);
                }
            }
            if (submit == null) {
                throw new IllegalArgumentException("Form '" + selector + "' does not contain a submit button");
            }
            if (submit != null) {
                DomElement dropdownMenu = submit.getEnclosingElement("ul");
                if (dropdownMenu != null && dropdownMenu.getAttribute("class").contains("dropdown-menu")) {
                    String existingClass = dropdownMenu.getAttribute("class");
                    if (!existingClass.contains("show")) {
                        dropdownMenu.setAttribute("class", existingClass + " show");
                    }
                    String style = dropdownMenu.getAttribute("style");
                    dropdownMenu.setAttribute("style", (style == null || style.isBlank() ? "" : style + ";") + "display:block");
                }
            }
            Page page;
            if (submit instanceof SubmittableElement submittableElement) {
                WebRequest submitRequest = form.getWebRequest(submittableElement);
                page = htmlClient.getPage(submitRequest);
            } else {
                page = submit.click();
            }
            updateFromPage(page);
            return this;
        }

        if (usesWebDriver()) {
            WebElement form = webDriver.findElement(By.cssSelector(selector));
            for (Map.Entry<String, String> entry : values.entrySet()) {
                WebElement field = form.findElement(By.name(entry.getKey()));
                String tag = field.getTagName();
                if ("input".equals(tag) || "textarea".equals(tag)) {
                    String type = field.getAttribute("type");
                    if ("checkbox".equalsIgnoreCase(type)) {
                        boolean shouldBeChecked = parseBooleanFieldValue(entry.getValue());
                        if (field.isSelected() != shouldBeChecked) {
                            field.click();
                        }
                    } else if ("radio".equalsIgnoreCase(type)) {
                        WebElement radioButton = form.findElement(By.cssSelector(
                                "input[type='radio'][name='" + entry.getKey() + "'][value='" + entry.getValue() + "']"));
                        if (!radioButton.isSelected()) {
                            radioButton.click();
                        }
                    } else {
                        field.clear();
                        field.sendKeys(entry.getValue());
                    }
                } else if ("select".equals(tag)) {
                    new Select(field).selectByValue(entry.getValue());
                }
            }
            for (Map.Entry<String, Path> entry : files.entrySet()) {
                WebElement field = form.findElement(By.name(entry.getKey()));
                field.sendKeys(entry.getValue().toString());
            }
            form.submit();
            updateFromDriver();
            return this;
        }

        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    public WebTest assertStatusIs(HttpStatus status) {
        assertThat(lastStatus).isEqualTo(status.value());
        return this;
    }

    public WebTest assertRedirectIs(String location) {
        String actual;
        if (browser == Browser.HTML_UNIT) {
            if (lastWebResponse != null) {
                actual = lastWebResponse.getResponseHeaderValue("Location");
            } else if (lastResponse != null) {
                actual = lastResponse.headers().firstValue("Location").orElse(null);
            } else {
                actual = null;
            }
        } else {
            actual = lastResponse.headers().firstValue("Location").orElse(null);
        }
        assertThat(actual).isNotNull().isEqualTo(location);
        return this;
    }

    public WebTest assertRedirectEndsWith(String locationSuffix) {
        String actual;
        if (browser == Browser.HTML_UNIT) {
            if (lastWebResponse != null) {
                actual = lastWebResponse.getResponseHeaderValue("Location");
            } else if (lastResponse != null) {
                actual = lastResponse.headers().firstValue("Location").orElse(null);
            } else {
                actual = null;
            }
        } else {
            actual = lastResponse.headers().firstValue("Location").orElse(null);
        }
        assertThat(actual).isNotNull().endsWith(locationSuffix);
        return this;
    }

    public WebTest assertThatPageTitleIs(String expectedTitle) {
        assertThat(currentDocument.title()).isEqualTo(expectedTitle);
        return this;
    }

    public WebTest assertThatElementWithIdIsPresent(String elementId) {
        assertThat(currentDocument.getElementById(elementId)).isNotNull();
        return this;
    }

    public WebTest assertPageBodyContains(String text) {
        assertThat(renderedHtml).contains(text);
        return this;
    }

    public WebTest assertPageBodyDoesNotContain(String text) {
        assertThat(renderedHtml).doesNotContain(text);
        return this;
    }

    public WebTest assertFormFieldValue(String fieldName, String expectedValue) {
        Element element = currentDocument.selectFirst("[name='" + fieldName + "']");
        assertThat(element).as("field '" + fieldName + "' exists").isNotNull();
        String actual = "textarea".equals(element.tagName()) ? element.text() : element.attr("value");
        assertThat(actual).isEqualTo(expectedValue);
        return this;
    }

    public WebTest assertCurrentUrlIs(String expectedUrl) {
        if (browser == Browser.HTML_UNIT) {
            assertThat(htmlPage.getUrl().toString()).isEqualTo(expectedUrl);
        } else {
            assertThat(currentUrl).isEqualTo(expectedUrl);
        }
        return this;
    }

    public Document document() {
        return currentDocument;
    }

    public String body() {
        if (renderedHtml == null) {
            throw new IllegalStateException("No response available");
        }
        return renderedHtml;
    }

    public int status() {
        return lastStatus;
    }

    public Optional<String> responseHeader(String name) {
        if (browser == Browser.HTML_UNIT && lastWebResponse != null) {
            return Optional.ofNullable(lastWebResponse.getResponseHeaderValue(name));
        }
        if (lastResponse == null) {
            return Optional.empty();
        }
        return lastResponse.headers().firstValue(name);
    }

    public WebTest then() {
        return this;
    }

    public WebTest and() {
        return this;
    }

    public WebTest navigateTo(String path) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            Page page = htmlClient.getPage(url(path));
            updateFromPage(page);
            return this;
        }
        if (usesWebDriver()) {
            syncCookiesFromHttpClientToWebDriver();
            webDriver.get(url(path));
            updateFromDriver();
            syncCookiesFromWebDriverToHttpClient();
            return this;
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    public WebTest followRedirect() throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            if (lastWebResponse != null && lastStatus >= 300 && lastStatus < 400) {
                String location = lastWebResponse.getResponseHeaderValue("Location");
                log.info("Following redirect to {}", location);
                if (location != null) {
                    Page page = htmlClient.getPage(location.startsWith("http") ? location : url(location));
                    updateFromPage(page);
                }
            }
            return this;
        }
        if (usesWebDriver()) {
            return this; // real browsers follow redirects automatically
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    public WebTest postJson(String path, Map<String, ?> body) throws IOException, InterruptedException {
        String json = body.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + toJsonValue(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        if (browser == Browser.HTML_UNIT) {
            syncCookiesFromHtmlUnitToHttpClient();
            lastResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            syncCookiesFromHttpClientToHtmlUnit();
            lastStatus = lastResponse.statusCode();
            renderedHtml = lastResponse.body();
            currentDocument = parseDocument(renderedHtml);
            return this;
        }
        send(request);
        return this;
    }

    public WebTest postForm(String path, Map<String, ?> body) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            List<NameValuePair> params = body.entrySet().stream()
                    .map(e -> new NameValuePair(e.getKey(), e.getValue().toString()))
                    .collect(Collectors.toList());
            WebRequest request = new WebRequest(new URL(url(path)), HttpMethod.POST);
            request.setRequestParameters(params);
            Page page = htmlClient.getPage(request);
            updateFromPage(page);
            return this;
        }
        String formBody = body.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" +
                        URLEncoder.encode(e.getValue().toString(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        send(request);
        return this;
    }

    private String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            return collection.stream()
                    .map(this::toJsonValue)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> "\"" + entry.getKey() + "\":" + toJsonValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String escaped = value.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    public WebTest executeScript(String script) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            htmlPage.executeJavaScript(script);
            renderedHtml = serialiseHtmlPage();
            currentDocument = parseDocument(renderedHtml);
            return this;
        }
        if (usesWebDriver()) {
            ((JavascriptExecutor) webDriver).executeScript(script);
            updateFromDriver();
            return this;
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    public JsValue evaluateScript(String script) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            Object result = htmlPage.executeJavaScript(script).getJavaScriptResult();
            renderedHtml = serialiseHtmlPage();
            currentDocument = parseDocument(renderedHtml);
            return normaliseJavaScriptResult(result);
        }
        if (usesWebDriver()) {
            Object result = ((JavascriptExecutor) webDriver).executeScript("return (" + script + ");");
            updateFromDriver();
            return normaliseJavaScriptResult(result);
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    private static JsValue normaliseJavaScriptResult(Object result) {
        if (result instanceof Undefined) {
            return new JsValue(null);
        }
        return new JsValue(result);
    }

    public WebTest click(String selector) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            waitFor(doc -> doc.selectFirst(selector) != null);
            DomElement element = htmlPage.querySelector(selector);
            if (element == null) {
                throw new IllegalArgumentException("Element '" + selector + "' not found");
            }
            Page page = ((HtmlElement) element).click();
            updateFromPage(page);
            return this;
        }
        if (usesWebDriver()) {
            WebElement el = webDriver.findElement(By.cssSelector(selector));
            el.click();
            updateFromDriver();
            return this;
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    /**
     * Sets the value of the input or textarea matched by the selector and triggers an
     * {@code input} event so any attached handlers execute just as they would in a real
     * browser.
     */
    public WebTest setInputValue(String selector, String value) throws IOException, InterruptedException {
        String escapedSelector = selector.replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedValue = value.replace("\\", "\\\\").replace("\"", "\\\"");
        if (browser == Browser.HTML_UNIT) {
            waitFor(doc -> doc.selectFirst(selector) != null);
            htmlPage.executeJavaScript("var el = document.querySelector(\"" + escapedSelector + "\"); " +
                    "if(el){el.value=\"" + escapedValue + "\"; el.dispatchEvent(new Event('input'));}");
            renderedHtml = serialiseHtmlPage();
            currentDocument = parseDocument(renderedHtml);
            checkForJavascriptErrors();
            return this;
        }
        if (usesWebDriver()) {
            WebElement el = webDriver.findElement(By.cssSelector(selector));
            el.clear();
            el.sendKeys(value);
            updateFromDriver();
            return this;
        }

        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    public WebTest waitFor(Predicate<Document> condition) throws IOException, InterruptedException {
        return waitFor(condition, Duration.ofSeconds(5));
    }

    public WebTest waitFor(Predicate<Document> condition, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (browser == Browser.HTML_UNIT) {
                if (htmlPage != null) {
                    renderedHtml = serialiseHtmlPage();
                }
                String pageUrl = null;
                if (htmlPage != null && htmlPage.getUrl() != null) {
                    pageUrl = htmlPage.getUrl().toString();
                } else if (lastWebResponse != null && lastWebResponse.getWebRequest() != null
                        && lastWebResponse.getWebRequest().getUrl() != null) {
                    pageUrl = lastWebResponse.getWebRequest().getUrl().toString();
                }
                if (pageUrl != null) {
                    currentUrl = pageUrl;
                }
                currentDocument = parseDocument(renderedHtml, pageUrl);
            }
            if (condition.test(currentDocument)) {
                checkForJavascriptErrors();
                return this;
            }
            if (browser == Browser.HTML_UNIT) {
                htmlClient.waitForBackgroundJavaScript(50);
            }
        }
        throw new IllegalStateException("Condition not met within " + timeout.toMillis() + "ms");
    }

    protected WebTest get(String path) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            Page page = htmlClient.getPage(url(path));
            updateFromPage(page);
            return this;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .GET()
                .build();
        send(request);
        currentUrl = url(path); // Set currentUrl for HttpClient requests
        return this;
    }

    public HttpResponse<byte[]> getBytes(String path) throws IOException, InterruptedException {
        syncCookiesFromHtmlUnitToHttpClient();
        syncCookiesFromWebDriverToHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        syncCookiesFromHttpClientToHtmlUnit();
        syncCookiesFromHttpClientToWebDriver();
        return response;
    }

    protected void send(HttpRequest request) throws IOException, InterruptedException {
        syncCookiesFromHtmlUnitToHttpClient();
        syncCookiesFromWebDriverToHttpClient();
        lastWebResponse = null;
        lastResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
        syncCookiesFromHttpClientToHtmlUnit();
        syncCookiesFromHttpClientToWebDriver();
        lastStatus = lastResponse.statusCode();
        URI responseUri = lastResponse.uri();
        String responseUrl = null;
        if (responseUri != null) {
            responseUrl = responseUri.toString();
        } else if (request != null && request.uri() != null) {
            responseUrl = request.uri().toString();
        }
        if (responseUrl != null) {
            currentUrl = responseUrl;
        }
        currentDocument = parseDocument(lastResponse.body(), responseUrl);
        renderedHtml = currentDocument.outerHtml();
        // Set currentUrl for webdriver requests
        if (browser != Browser.HTML_UNIT && request.uri() != null) {
            currentUrl = request.uri().toString();
        }
    }

    protected String url(String path) {
        return baseUri.resolve(path).toString();
    }

    private String serialiseHtmlPage() {
        return NON_VOID_SELF_CLOSING_ELEMENT.matcher(htmlPage.asXml()).replaceAll("<$1$2></$1>");
    }

    private Document parseDocument(String html) {
        return parseDocument(html, null);
    }

    private Document parseDocument(String html, String explicitBase) {
        String base = explicitBase;
        if (base == null || base.isBlank()) {
            if (browser == Browser.HTML_UNIT && htmlPage != null && htmlPage.getUrl() != null) {
                base = htmlPage.getUrl().toString();
            } else if (currentUrl != null && !currentUrl.isBlank()) {
                base = currentUrl;
            } else if (lastResponse != null && lastResponse.uri() != null) {
                base = lastResponse.uri().toString();
            } else if (lastWebResponse != null && lastWebResponse.getWebRequest() != null
                    && lastWebResponse.getWebRequest().getUrl() != null) {
                base = lastWebResponse.getWebRequest().getUrl().toString();
            } else {
                base = baseUri.toString();
            }
        }
        return Jsoup.parse(html, base);
    }

    private void updateFromPage(Page page) {
        this.htmlPage = page instanceof HtmlPage ? (HtmlPage) page : null;
        this.lastWebResponse = page.getWebResponse();
        this.lastResponse = null;
        this.lastStatus = lastWebResponse.getStatusCode();
        if (htmlPage != null) {
            renderedHtml = serialiseHtmlPage();
        } else {
            // UnexpectedPage is used for both textual API responses and binary files.
            // Preserve text/JSON/XML for callers that inspect response bodies, but do
            // not decode arbitrary binary bytes and feed them into jsoup.
            renderedHtml = isTextualResponse(lastWebResponse.getContentType())
                    ? Optional.ofNullable(lastWebResponse.getContentAsString()).orElse("")
                    : "";
        }
        String pageUrl = null;
        if (htmlPage != null && htmlPage.getUrl() != null) {
            pageUrl = htmlPage.getUrl().toString();
        } else if (lastWebResponse != null && lastWebResponse.getWebRequest() != null
                && lastWebResponse.getWebRequest().getUrl() != null) {
            pageUrl = lastWebResponse.getWebRequest().getUrl().toString();
        }
        if (pageUrl != null) {
            currentUrl = pageUrl;
        }
        currentDocument = parseDocument(renderedHtml, pageUrl);
        checkForJavascriptErrors();
        syncCookiesFromHtmlUnitToHttpClient();
    }

    private boolean isTextualResponse(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mimeType = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return mimeType.startsWith("text/")
                || mimeType.equals("application/json")
                || mimeType.endsWith("+json")
                || mimeType.equals("application/xml")
                || mimeType.endsWith("+xml")
                || mimeType.equals("application/javascript");
    }

    private void checkForJavascriptErrors() {
        if (console != null && !console.getErrors().isEmpty()) {
            if (browser == Browser.HTML_UNIT) {
                console.getErrors().forEach(System.err::println);
                console.clear();
                return;
            }
            throw new RuntimeException("JavaScript console errors: " + String.join(", ", console.getErrors()));
        }
        if (console != null) {
            console.clear();
        }
    }

    private static boolean parseBooleanFieldValue(String value) {
        if (value == null) {
            return false;
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return normalised.equals("on") || normalised.equals("true") || normalised.equals("1") || normalised.equals("yes");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        if (htmlClient != null) {
            htmlClient.close();
            htmlClient = null;
        }

        if (webDriver != null) {
            try {
                webDriver.quit();
            } catch (Exception ex) {
                log.debug("Failed to quit web driver", ex);
            }
            webDriver = null;
        }

        console = null;
        htmlPage = null;
        currentDocument = null;
        renderedHtml = null;
        currentUrl = null;
    }
}
