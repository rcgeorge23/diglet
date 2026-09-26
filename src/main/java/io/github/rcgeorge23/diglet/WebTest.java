package io.github.rcgeorge23.diglet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.lang.reflect.Array;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.apache.http.cookie.ClientCookie;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.htmlunit.corejs.javascript.Undefined;
import org.htmlunit.util.Cookie;

import org.htmlunit.WebClient;
import org.htmlunit.WebConsole;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.WebResponseData;
import org.htmlunit.StringWebResponse;
import org.htmlunit.WebWindow;
import org.htmlunit.util.WebConnectionWrapper;
import org.htmlunit.HttpMethod;
import org.htmlunit.util.NameValuePair;
import org.htmlunit.Page;
import org.htmlunit.ScriptException;
import org.htmlunit.html.DisabledElement;
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
import org.openqa.selenium.Alert;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.UnhandledAlertException;
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

    private enum DialogType {
        ALERT("alert"),
        CONFIRM("confirm"),
        PROMPT("prompt");

        private final String displayName;

        DialogType(String displayName) {
            this.displayName = displayName;
        }
    }

    private enum DialogAction {
        ACCEPT,
        DISMISS
    }

    /**
     * A one-shot expectation for the next JavaScript dialog. Configure the action before triggering
     * the dialog, then inspect its text afterward.
     */
    public static final class DialogExpectation {
        private final WebTest owner;
        private final DialogType type;
        private DialogAction action = DialogAction.ACCEPT;
        private String promptResponse;
        private String text;
        private boolean triggered;

        private DialogExpectation(WebTest owner, DialogType type) {
            this.owner = owner;
            this.type = type;
        }

        /**
         * Accept the expected dialog. This is the default action.
         *
         * @return this expectation
         */
        public DialogExpectation accept() {
            ensurePending();
            action = DialogAction.ACCEPT;
            return this;
        }

        /**
         * Dismiss the expected confirm or prompt dialog.
         *
         * @return this expectation
         */
        public DialogExpectation dismiss() {
            ensurePending();
            if (type == DialogType.ALERT) {
                throw new IllegalStateException("An alert dialog can only be accepted");
            }
            action = DialogAction.DISMISS;
            return this;
        }

        /**
         * Supply the response for an expected prompt and accept it.
         *
         * @param value the text to send to the prompt
         * @return this expectation
         */
        public DialogExpectation sendKeys(String value) {
            ensurePending();
            if (type != DialogType.PROMPT) {
                throw new IllegalStateException("sendKeys is only available for a prompt dialog");
            }
            promptResponse = java.util.Objects.requireNonNull(value, "value");
            action = DialogAction.ACCEPT;
            return this;
        }

        /**
         * Return the dialog message after the expected dialog has appeared.
         *
         * @return the captured dialog message
         */
        public String getText() {
            if (!triggered) {
                throw new IllegalStateException("The expected dialog has not appeared yet");
            }
            return text;
        }

        private void ensurePending() {
            if (triggered || owner.pendingDialogExpectation != this) {
                throw new IllegalStateException("The dialog expectation is no longer pending");
            }
        }
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
    private static final Pattern JAVASCRIPT_CLASS_SYNTAX = Pattern.compile(
            "\\bclass\\s*(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*)?(?:extends\\s+[^\\{]+)?\\{");
    private static final Pattern JAVASCRIPT_ASYNC_SYNTAX = Pattern.compile(
            "\\basync\\s+function\\b|\\basync\\s*(?:\\([^)]*\\)|[A-Za-z_$][A-Za-z0-9_$]*)\\s*=>|\\bawait\\s+");

    private final String baseUrl;
    private final URI baseUri;
    private final HttpClient client;
    private final CookieManager cookieManager;
    private final boolean automaticallyFollowRedirects;
    private HttpResponse<String> lastResponse;
    private WebResponse lastWebResponse;
    private int lastStatus;
    private boolean lastStatusIsAvailable;
    private final Browser browser;
    private BrowserConsole console;
    private boolean ignoreJavascriptErrors;
    private WebClient htmlClient;
    private HtmlPage htmlPage;
    private boolean currentDocumentIsBrowserPage;
    private WebDriver webDriver;
    private boolean pooledDriver;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Document currentDocument;
    private String renderedHtml;
    private String currentUrl;
    private DialogExpectation pendingDialogExpectation;
    private IllegalStateException pendingHtmlUnitDialogFailure;


    static boolean shouldIgnoreBootstrapScriptError(String details) {
        return details != null && details.contains("bootstrap.bundle.min");
    }

    private boolean isAllowedHtmlUnitHost(String host) {
        if (host.equalsIgnoreCase(baseUri.getHost())) {
            return true;
        }
        return "localhost".equalsIgnoreCase(baseUri.getHost())
                && host.toLowerCase(Locale.ROOT).endsWith(".localhost");
    }

    private static boolean cookiePathMatches(String cookiePath, String requestPath) {
        if (cookiePath == null || cookiePath.isEmpty()) {
            cookiePath = "/";
        }
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        return requestPath.startsWith(cookiePath)
                && (cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/');
    }

    private static final class LocalhostCookieManager extends CookieManager {
        private final Set<CookieIdentity> explicitlyDomainScopedCookies = ConcurrentHashMap.newKeySet();

        private void registerExplicitDomainCookie(HttpCookie cookie) {
            if (isLocalhostCookieDomain(cookie.getDomain())) {
                explicitlyDomainScopedCookies.add(CookieIdentity.from(cookie));
            }
        }

        private void unregisterExplicitDomainCookie(HttpCookie cookie) {
            explicitlyDomainScopedCookies.remove(CookieIdentity.from(cookie));
        }

        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
            super.put(uri, responseHeaders);
            responseHeaders.forEach((header, values) -> {
                if (!"Set-Cookie".equalsIgnoreCase(header) && !"Set-Cookie2".equalsIgnoreCase(header)) {
                    return;
                }
                for (String value : values) {
                    try {
                        HttpCookie.parse(value).forEach(this::registerExplicitDomainCookie);
                    } catch (IllegalArgumentException ignored) {
                        // The standard manager has already processed this header.
                    }
                }
            });
        }

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
            Map<String, List<String>> headers = new HashMap<>(super.get(uri, requestHeaders));
            String host = uri.getHost();
            if (host == null || !host.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
                return headers;
            }

            List<String> domainCookies = getCookieStore().getCookies().stream()
                    .filter(cookie -> explicitlyDomainScopedCookies.contains(CookieIdentity.from(cookie)))
                    .filter(cookie -> !cookie.hasExpired())
                    .filter(cookie -> !cookie.getSecure() || "https".equalsIgnoreCase(uri.getScheme()))
                    .filter(cookie -> cookiePathMatches(cookie.getPath(), uri.getPath()))
                    .map(cookie -> {
                        HttpCookie headerCookie = new HttpCookie(cookie.getName(), cookie.getValue());
                        headerCookie.setVersion(0);
                        return headerCookie.toString();
                    })
                    .toList();
            if (domainCookies.isEmpty()) {
                return headers;
            }

            String existingCookies = String.join("; ", headers.getOrDefault("Cookie", List.of()));
            String additionalCookies = String.join("; ", domainCookies);
            headers.put("Cookie", List.of(existingCookies.isEmpty()
                    ? additionalCookies
                    : existingCookies + "; " + additionalCookies));
            return headers;
        }

        private static boolean isLocalhostCookieDomain(String domain) {
            return "localhost".equalsIgnoreCase(domain) || ".localhost".equalsIgnoreCase(domain);
        }

        private record CookieIdentity(String name, String domain, String path) {
            private static CookieIdentity from(HttpCookie cookie) {
                String domain = cookie.getDomain() == null ? null : cookie.getDomain().toLowerCase(Locale.ROOT);
                String path = cookie.getPath() == null ? "/" : cookie.getPath();
                return new CookieIdentity(cookie.getName(), domain, path);
            }
        }
    }

    private void syncCookiesFromHtmlUnitToHttpClient() {
        if (browser != Browser.HTML_UNIT) {
            return;
        }
        var store = cookieManager.getCookieStore();
        for (Cookie c : htmlClient.getCookieManager().getCookies()) {
            HttpCookie hc = new HttpCookie(c.getName(), c.getValue());
            if (c.toHttpClient() instanceof ClientCookie clientCookie
                    && clientCookie.containsAttribute(ClientCookie.DOMAIN_ATTR)) {
                hc.setDomain(c.getDomain());
                ((LocalhostCookieManager) cookieManager).registerExplicitDomainCookie(hc);
            }
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
        for (HttpCookie hc : cookieManager.getCookieStore().getCookies()) {
            Date expires = hc.getMaxAge() >= 0 ? new Date(System.currentTimeMillis() + hc.getMaxAge() * 1000) : null;
            BasicClientCookie apacheCookie = new BasicClientCookie(hc.getName(), hc.getValue());
            apacheCookie.setDomain(hc.getDomain() != null ? hc.getDomain() : baseUri.getHost());
            apacheCookie.setPath(hc.getPath());
            apacheCookie.setExpiryDate(expires);
            apacheCookie.setSecure(hc.getSecure());
            if (hc.getDomain() != null) {
                apacheCookie.setAttribute(ClientCookie.DOMAIN_ATTR, hc.getDomain());
            }
            if (hc.getPath() != null) {
                apacheCookie.setAttribute(ClientCookie.PATH_ATTR, hc.getPath());
            }
            if (hc.getSecure()) {
                apacheCookie.setAttribute(ClientCookie.SECURE_ATTR, "true");
            }
            Cookie c = new Cookie(apacheCookie);
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
            ((LocalhostCookieManager) cookieManager).unregisterExplicitDomainCookie(hc);
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

    /**
     * Expect the next JavaScript alert. Alerts are accepted by default.
     *
     * @return an expectation whose text can be inspected after the action
     */
    public DialogExpectation expectAlert() {
        return expectDialog(DialogType.ALERT);
    }

    /**
     * Expect the next JavaScript confirm dialog. Confirms are accepted by default.
     *
     * @return an expectation whose outcome can be configured before the action
     */
    public DialogExpectation expectConfirm() {
        return expectDialog(DialogType.CONFIRM);
    }

    /**
     * Expect the next JavaScript prompt dialog. Prompts are accepted with their default value
     * unless {@link DialogExpectation#sendKeys(String)} or {@link DialogExpectation#dismiss()} is
     * called before the action.
     *
     * @return an expectation whose response can be configured before the action
     */
    public DialogExpectation expectPrompt() {
        return expectDialog(DialogType.PROMPT);
    }

    private DialogExpectation expectDialog(DialogType type) {
        if (pendingDialogExpectation != null) {
            throw new IllegalStateException("A JavaScript dialog expectation is already pending");
        }
        pendingDialogExpectation = new DialogExpectation(this, type);
        return pendingDialogExpectation;
    }

    private DialogExpectation handleHtmlUnitDialog(DialogType type, String message) {
        DialogExpectation expectation = pendingDialogExpectation;
        if (expectation == null) {
            if (pendingHtmlUnitDialogFailure == null) {
                pendingHtmlUnitDialogFailure = unexpectedDialog(null, message);
            }
            return dismissedHtmlUnitDialog(type);
        }
        if (expectation.type != type) {
            pendingDialogExpectation = null;
            if (pendingHtmlUnitDialogFailure == null) {
                pendingHtmlUnitDialogFailure = new IllegalStateException("Expected a JavaScript "
                        + expectation.type.displayName + " dialog, but received a " + type.displayName
                        + " dialog: " + message);
            }
            return dismissedHtmlUnitDialog(type);
        }
        pendingDialogExpectation = null;
        expectation.text = message;
        expectation.triggered = true;
        return expectation;
    }

    private DialogExpectation dismissedHtmlUnitDialog(DialogType type) {
        DialogExpectation expectation = new DialogExpectation(this, type);
        expectation.action = DialogAction.DISMISS;
        return expectation;
    }

    private void verifyDialogExpectation(DialogExpectation expectation) {
        if (expectation != null && !expectation.triggered) {
            if (pendingDialogExpectation == expectation) {
                pendingDialogExpectation = null;
            }
            throw new IllegalStateException("Expected a JavaScript " + expectation.type.displayName
                    + " dialog, but no dialog appeared");
        }
    }

    private void handleWebDriverDialog(UnhandledAlertException actionFailure) {
        DialogExpectation expectation = pendingDialogExpectation;
        Alert alert;
        try {
            alert = webDriver.switchTo().alert();
        } catch (NoAlertPresentException noAlert) {
            if (actionFailure != null) {
                String message = actionFailure.getAlertText();
                if (expectation == null) {
                    throw new IllegalStateException("Unexpected JavaScript dialog: " + message, actionFailure);
                }
                pendingDialogExpectation = null;
                throw new IllegalStateException("Could not handle the expected JavaScript "
                        + expectation.type.displayName + " dialog: " + message, actionFailure);
            }
            verifyDialogExpectation(expectation);
            return;
        }

        String message = alert.getText();
        if (expectation == null) {
            alert.dismiss();
            throw unexpectedDialog(null, message);
        }

        expectation.text = message;
        expectation.triggered = true;
        pendingDialogExpectation = null;
        if (expectation.action == DialogAction.DISMISS) {
            alert.dismiss();
        } else {
            if (expectation.type == DialogType.PROMPT && expectation.promptResponse != null) {
                alert.sendKeys(expectation.promptResponse);
            }
            alert.accept();
        }
    }

    private static IllegalStateException unexpectedDialog(DialogType type, String message) {
        String kind = type == null ? "JavaScript" : "JavaScript " + type.displayName;
        return new IllegalStateException("Unexpected " + kind + " dialog: " + message);
    }

    private void updateFromDriver() {
        handleWebDriverDialog(null);
        renderedHtml = webDriver.getPageSource();
        String url = webDriver.getCurrentUrl();
        currentUrl = url;
        currentDocument = parseDocument(renderedHtml, url);
        currentDocumentIsBrowserPage = true;
        lastStatusIsAvailable = false;
    }

    private Supplier<WebDriver> defaultDriverSupplier(Browser browser) {
        return () -> {
            if (browser == Browser.CHROME) {
                ChromeOptions options = new ChromeOptions();
                options.addArguments("--headless=new");
                options.setCapability("unhandledPromptBehavior", "ignore");
                return new ChromeDriver(options);
            } else if (browser == Browser.FIREFOX) {
                FirefoxOptions options = new FirefoxOptions();
                options.addArguments("--headless");
                options.setCapability("unhandledPromptBehavior", "ignore");
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
        this.cookieManager = new LocalhostCookieManager();
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
            htmlClient.setAlertHandler((page, message) -> handleHtmlUnitDialog(DialogType.ALERT, message));
            htmlClient.setConfirmHandler((page, message) ->
                    handleHtmlUnitDialog(DialogType.CONFIRM, message).action == DialogAction.ACCEPT);
            htmlClient.setPromptHandler((page, message, defaultValue) -> {
                DialogExpectation expectation = handleHtmlUnitDialog(DialogType.PROMPT, message);
                if (expectation.action == DialogAction.DISMISS) {
                    return null;
                }
                return expectation.promptResponse == null ? defaultValue : expectation.promptResponse;
            });
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
                    if (url.getHost() != null && !isAllowedHtmlUnitHost(url.getHost())) {
                        return new StringWebResponse("", url);
                    }
                    WebResponse response = super.getResponse(request);
                    return injectHtmlUnitPolyfills(response, request);
                }
            });
            htmlClient.setJavaScriptErrorListener(new JavaScriptErrorListener() {
                @Override
                public void scriptException(HtmlPage page, ScriptException scriptException) {
                    String message = scriptException.getMessage() == null ? scriptException.getClass().getSimpleName() : scriptException.getMessage();
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
                        console.getLogs().add("JavaScript warning: " + message);
                    }
                }
            });
            htmlClient.getWebConsole().setLogger(new WebConsole.Logger() {
                @Override
                public boolean isTraceEnabled() {
                    return true;
                }

                @Override
                public void trace(Object message) {
                    console.getLogs().add(String.valueOf(message));
                }

                @Override
                public boolean isDebugEnabled() {
                    return true;
                }

                @Override
                public void debug(Object message) {
                    console.getLogs().add(String.valueOf(message));
                }

                @Override
                public boolean isInfoEnabled() {
                    return true;
                }

                @Override
                public void info(Object message) {
                    console.getLogs().add(String.valueOf(message));
                }

                @Override
                public boolean isWarnEnabled() {
                    return true;
                }

                @Override
                public void warn(Object message) {
                    console.getLogs().add(String.valueOf(message));
                }

                @Override
                public boolean isErrorEnabled() {
                    return true;
                }

                @Override
                public void error(Object message) {
                    console.getErrors().add(String.valueOf(message));
                }
            });
        } else {
            this.console = new BrowserConsole();
            if (usesWebDriver()) {
                Supplier<WebDriver> supplier = driverSupplier != null ? driverSupplier : defaultDriverSupplier(browser);
                if (supplier instanceof WebDriverPool pool) {
                    this.pooledDriver = true;
                    this.webDriver = pool.get();
                    pool.reset();
                } else {
                    this.webDriver = supplier.get();
                }
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
        if (!htmlClient.getOptions().isJavaScriptEnabled()) {
            return response;
        }

        String contentType = response.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            String content = response.getContentAsString();
            inspectHtmlScripts(content, request.getUrl().toExternalForm());

            String headTag = "<head>";
            int headIndex = content.indexOf(headTag);
            if (headIndex < 0) {
                return response;
            }

            String polyfills = "<script>"
                    + "if(typeof Symbol==='undefined'){window.Symbol=function Symbol(description){return '@@symbol:' + (description||'') + ':' + Math.random().toString(36).slice(2);};window.Symbol.iterator='@@iterator';}"
                    + "if(typeof queueMicrotask==='undefined'){window.queueMicrotask=function queueMicrotask(callback){Promise.resolve().then(callback);};}"
                    + "if(typeof structuredClone==='undefined'){window.structuredClone=function structuredClone(value){return value===undefined?undefined:JSON.parse(JSON.stringify(value));};}"
                    + "if(typeof requestIdleCallback==='undefined'){window.requestIdleCallback=function requestIdleCallback(callback){return window.setTimeout(function(){callback({didTimeout:false,timeRemaining:function timeRemaining(){return 0;}});},1);};window.cancelIdleCallback=function cancelIdleCallback(handle){window.clearTimeout(handle);};}"
                    + "if(typeof ResizeObserver==='undefined'){window.ResizeObserver=function ResizeObserver(callback){this.observe=function(){};this.unobserve=function(){};this.disconnect=function(){};};}"
                    + "</script>";
            String updatedContent = content.substring(0, headIndex + headTag.length()) + polyfills + content.substring(headIndex + headTag.length());
            byte[] updatedBytes = updatedContent.getBytes(StandardCharsets.UTF_8);

            WebResponseData data = new WebResponseData(
                    updatedBytes,
                    response.getStatusCode(),
                    response.getStatusMessage(),
                    response.getResponseHeaders());
            return new WebResponse(data, request, response.getLoadTime());
        }

        if (isJavaScriptResponse(contentType, request.getUrl())) {
            inspectJavaScriptSource(response.getContentAsString(), request.getUrl().toExternalForm());
        }
        return response;
    }

    private static boolean isJavaScriptResponse(String contentType, URL url) {
        String mimeType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String path = url.getPath().toLowerCase(Locale.ROOT);
        return mimeType.contains("javascript") || mimeType.contains("ecmascript")
                || path.endsWith(".js") || path.endsWith(".mjs");
    }

    private void inspectHtmlScripts(String html, String pageUrl) {
        Document document = Jsoup.parse(html, pageUrl);
        for (Element script : document.select("script")) {
            String sourceUrl = script.hasAttr("src") ? script.absUrl("src") : pageUrl + " (inline script)";
            if (sourceUrl.isBlank()) {
                sourceUrl = pageUrl;
            }
            if ("module".equalsIgnoreCase(script.attr("type").trim())) {
                recordUnsupportedJavascript("JavaScript module scripts (<script type=\"module\">)", sourceUrl);
            } else if (!script.hasAttr("src")) {
                inspectJavaScriptSource(script.data(), sourceUrl);
            }
        }
    }

    private void inspectJavaScriptSource(String source, String sourceUrl) {
        if (source == null || source.isEmpty()) {
            return;
        }
        String code = maskJavaScriptCommentsAndStrings(source);
        if (JAVASCRIPT_CLASS_SYNTAX.matcher(code).find()) {
            recordUnsupportedJavascript("ES class syntax", sourceUrl);
        }
        if (JAVASCRIPT_ASYNC_SYNTAX.matcher(code).find()) {
            recordUnsupportedJavascript("async/await syntax", sourceUrl);
        }
    }

    private static String maskJavaScriptCommentsAndStrings(String source) {
        StringBuilder code = new StringBuilder(source);
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                int end = index + 2;
                while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
                    end++;
                }
                maskNonLineBreaks(code, index, end);
                index = end;
            } else if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                end = end < 0 ? source.length() : end + 2;
                maskNonLineBreaks(code, index, end);
                index = end;
            } else if (current == '\'' || current == '"' || current == '`') {
                char quote = current;
                int end = index + 1;
                while (end < source.length()) {
                    char next = source.charAt(end++);
                    if (next == '\\' && end < source.length()) {
                        end++;
                    } else if (next == quote) {
                        break;
                    }
                }
                maskNonLineBreaks(code, index, end);
                index = end;
            } else {
                index++;
            }
        }
        return code.toString();
    }

    private static void maskNonLineBreaks(StringBuilder source, int start, int end) {
        for (int index = start; index < end; index++) {
            char current = source.charAt(index);
            if (current != '\n' && current != '\r') {
                source.setCharAt(index, ' ');
            }
        }
    }

    private void recordUnsupportedJavascript(String feature, String sourceUrl) {
        String diagnostic = "Unsupported JavaScript: HtmlUnit cannot execute " + feature + " in " + sourceUrl
                + "; use WebTest.Browser.CHROME or WebTest.Browser.FIREFOX for this page.";
        if (!console.getErrors().contains(diagnostic)) {
            console.getErrors().add(diagnostic);
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
        return submitFormBySelector(selector, values, files, false);
    }

    /**
     * Fills and submits a form without firing the submit event or running the browser's
     * constraint validation, equivalent to calling {@code form.submit()} from JavaScript.
     *
     * <p>Use this when a test needs to exercise server-side validation of input that a real
     * browser would refuse to submit. Prefer {@link #submitForm} for browser-like behaviour.</p>
     */
    public WebTest forceSubmitForm(String formId, Map<String, String> values) throws IOException, InterruptedException {
        return forceSubmitForm(formId, values, Map.of());
    }

    /**
     * Fills and submits a form without firing the submit event or running the browser's
     * constraint validation, equivalent to calling {@code form.submit()} from JavaScript.
     *
     * <p>Use this when a test needs to exercise server-side validation of input that a real
     * browser would refuse to submit. Prefer {@link #submitForm} for browser-like behaviour.</p>
     */
    public WebTest forceSubmitForm(String formId, Map<String, String> values, Map<String, Path> files) throws IOException, InterruptedException {
        return forceSubmitFormBySelector("#" + formId, values, files);
    }

    /**
     * Fills and submits a form without firing the submit event or running the browser's
     * constraint validation, equivalent to calling {@code form.submit()} from JavaScript.
     *
     * <p>Use this when a test needs to exercise server-side validation of input that a real
     * browser would refuse to submit. Prefer {@link #submitFormBySelector} for browser-like behaviour.</p>
     */
    public WebTest forceSubmitFormBySelector(String selector, Map<String, String> values) throws IOException, InterruptedException {
        return forceSubmitFormBySelector(selector, values, Map.of());
    }

    /**
     * Fills and submits a form without firing the submit event or running the browser's
     * constraint validation, equivalent to calling {@code form.submit()} from JavaScript.
     *
     * <p>Use this when a test needs to exercise server-side validation of input that a real
     * browser would refuse to submit. Prefer {@link #submitFormBySelector} for browser-like behaviour.</p>
     */
    public WebTest forceSubmitFormBySelector(String selector, Map<String, String> values, Map<String, Path> files) throws IOException, InterruptedException {
        return submitFormBySelector(selector, values, files, true);
    }

    private WebTest submitFormBySelector(String selector, Map<String, String> values, Map<String, Path> files, boolean force)
            throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            HtmlForm form = (HtmlForm) htmlPage.querySelector(selector);
            if (form == null) {
                throw new IllegalArgumentException("Form '" + selector + "' not found");
            }
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
            Page page;
            if (force) {
                form.submit((SubmittableElement) null);
                htmlClient.loadDownloadedResponses();
                page = htmlPage.getEnclosingWindow().getEnclosedPage();
            } else {
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
                if (submit instanceof SubmittableElement submittableElement) {
                    form.submit(submittableElement);
                    htmlClient.loadDownloadedResponses();
                    page = htmlPage.getEnclosingWindow().getEnclosedPage();
                } else {
                    page = submit.click();
                }
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
            if (force) {
                ((JavascriptExecutor) webDriver).executeScript("arguments[0].submit();", form);
            } else {
                form.submit();
            }
            updateFromDriver();
            return this;
        }

        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    /**
     * Asserts the status code of the most recent HTTP response.
     *
     * @param status the expected HTTP status
     * @return this WebTest
     * @throws IllegalStateException when no HTTP response status is available for the current page,
     *                                for example after a WebDriver navigation
     */
    public WebTest assertStatusIs(HttpStatus status) {
        ensureStatusIsAvailable();
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

    public WebTest assertPageTextContains(String text) {
        assertThat(pageText()).contains(text);
        return this;
    }

    public WebTest assertPageTextDoesNotContain(String text) {
        assertThat(pageText()).doesNotContain(text);
        return this;
    }

    private String pageText() {
        if (browser == Browser.HTML_UNIT) {
            if (htmlPage != null) {
                return normaliseText(htmlPage.getVisibleText());
            }
            return normaliseText(parseDocument(renderedHtml).text());
        }
        if (usesWebDriver()) {
            return normaliseText(webDriver.findElement(By.tagName("body")).getText());
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    private static String normaliseText(String text) {
        return text == null ? null : text.replaceAll("\s+", " ").trim();
    }

    /**
     * Asserts the current value of a form field matched by its {@code name} attribute.
     * For input elements in a browser page, this checks the live DOM {@code value} property,
     * which may differ from the original HTML {@code value} attribute. Textareas continue to
     * use their current serialized text content; other elements use their {@code value} attribute.
     *
     * @param fieldName the name attribute of the field
     * @param expectedValue the expected field value
     * @return this WebTest
     */
    public WebTest assertFormFieldValue(String fieldName, String expectedValue) {
        String selector = "[name='" + fieldName + "']";
        Element element = currentDocument.selectFirst(selector);
        assertThat(element).as("field '" + fieldName + "' exists").isNotNull();
        String actual;
        if ("input".equals(element.tagName()) && currentDocumentIsBrowserPage) {
            if (browser == Browser.HTML_UNIT && htmlPage != null) {
                DomElement liveElement = htmlPage.querySelector(selector);
                assertThat(liveElement).as("live field '" + fieldName + "' exists").isInstanceOf(HtmlInput.class);
                actual = ((HtmlInput) liveElement).getValue();
            } else if (usesWebDriver()) {
                actual = webDriver.findElement(By.cssSelector(selector)).getDomProperty("value");
            } else {
                actual = element.attr("value");
            }
        } else {
            actual = "textarea".equals(element.tagName()) ? element.text() : element.attr("value");
        }
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

    /**
     * Returns the status code of the most recent HTTP response.
     *
     * @return the HTTP status code
     * @throws IllegalStateException when no HTTP response status is available for the current page,
     *                                for example after a WebDriver navigation
     */
    public int status() {
        ensureStatusIsAvailable();
        return lastStatus;
    }

    private void ensureStatusIsAvailable() {
        if (!lastStatusIsAvailable) {
            throw new IllegalStateException("HTTP response status is unavailable for the current page");
        }
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
            lastStatusIsAvailable = true;
            renderedHtml = lastResponse.body();
            currentDocument = parseDocument(renderedHtml);
            currentDocumentIsBrowserPage = false;
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

    /**
     * Do not fail the test when the page raises JavaScript errors, for example scripts the
     * HtmlUnit engine cannot parse. Diagnostics remain available through {@link #javascriptErrors()}.
     *
     * @return this WebTest
     */
    public WebTest ignoreJavascriptErrors() {
        this.ignoreJavascriptErrors = true;
        return this;
    }

    /**
     * Returns JavaScript errors and unsupported-syntax diagnostics recorded for the current page.
     *
     * @return the JavaScript errors and unsupported-syntax diagnostics collected from the current page
     */
    public List<String> javascriptErrors() {
        return List.copyOf(console.getErrors());
    }

    public WebTest executeScript(String script) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            htmlPage.executeJavaScript(script);
            renderedHtml = serialiseHtmlPage();
            currentDocument = parseDocument(renderedHtml);
            currentDocumentIsBrowserPage = true;
            checkForJavascriptErrors();
            return this;
        }
        if (usesWebDriver()) {
            ((JavascriptExecutor) webDriver).executeScript(script);
            updateFromDriver();
            return this;
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    /**
     * Evaluates a JavaScript expression and wraps its engine-independent Java result in a
     * {@link JsValue}. Numbers are normalized to {@link Double}; arrays and plain objects become
     * recursively normalized {@link List} and {@link Map} values. JavaScript {@code null} and
     * {@code undefined} both become {@code null}.
     * Use {@link #executeScript(String)} for side-effect-only scripts, including scripts that start
     * asynchronous work.
     *
     * @param script JavaScript expression to evaluate
     * @return the normalized JavaScript result
     * @throws IOException if the browser cannot load or update the page
     * @throws InterruptedException if waiting for the browser is interrupted
     * @throws IllegalArgumentException if the result is not a primitive, array, or plain object
     */
    public JsValue evaluateScript(String script) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            Object result = htmlPage.executeJavaScript(script).getJavaScriptResult();
            renderedHtml = serialiseHtmlPage();
            currentDocument = parseDocument(renderedHtml);
            currentDocumentIsBrowserPage = true;
            checkForJavascriptErrors();
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
        return new JsValue(toJavaScriptValue(result, new IdentityHashMap<>()));
    }

    private static Object toJavaScriptValue(Object value, IdentityHashMap<Object, Object> convertedValues) {
        if (value == null || value instanceof Undefined) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof Boolean) {
            return value;
        }

        if (convertedValues.containsKey(value)) {
            return convertedValues.get(value);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            convertedValues.put(value, converted);
            map.forEach((key, nestedValue) ->
                    converted.put(String.valueOf(key), toJavaScriptValue(nestedValue, convertedValues)));
            return converted;
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            convertedValues.put(value, converted);
            for (Object nestedValue : list) {
                converted.add(toJavaScriptValue(nestedValue, convertedValues));
            }
            return converted;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> converted = new ArrayList<>(length);
            convertedValues.put(value, converted);
            for (int index = 0; index < length; index++) {
                converted.add(toJavaScriptValue(Array.get(value, index), convertedValues));
            }
            return converted;
        }
        throw new IllegalArgumentException(
                "Unsupported JavaScript result type; return a primitive, array, or plain object instead");
    }

    private static boolean isInteractable(DomElement element) {
        if (element instanceof HtmlElement htmlElement && !htmlElement.isDisplayed()) {
            return false;
        }
        return !(element instanceof DisabledElement disabledElement) || !disabledElement.isDisabled();
    }

    /**
     * Clicks an element even when it is hidden or disabled, as if the click had been triggered from
     * JavaScript. Prefer {@link #click(String)} for browser-like behaviour.
     *
     * @param selector CSS selector for the element to click
     * @return this WebTest
     */
    public WebTest forceClick(String selector) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            waitFor(document -> document.selectFirst(selector) != null);
            DomElement element = htmlPage.querySelector(selector);
            if (element == null) {
                throw new IllegalArgumentException("Element '" + selector + "' not found");
            }
            WebWindow currentWindow = htmlPage.getEnclosingWindow();
            DialogExpectation expectation = pendingDialogExpectation;
            Page page = ((HtmlElement) element).click(false, false, false, true, true, true, false);
            updateFromClickedPage(page, currentWindow);
            verifyDialogExpectation(expectation);
            return this;
        }
        if (usesWebDriver()) {
            WebElement element = webDriver.findElement(By.cssSelector(selector));
            try {
                ((JavascriptExecutor) webDriver).executeScript("arguments[0].click();", element);
            } catch (UnhandledAlertException ex) {
                handleWebDriverDialog(ex);
            }
            updateFromDriver();
            return this;
        }
        throw new IllegalStateException("Unsupported browser: " + browser);
    }

    public WebTest click(String selector) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            waitFor(doc -> doc.selectFirst(selector) != null);
            DomElement element = htmlPage.querySelector(selector);
            if (element == null) {
                throw new IllegalArgumentException("Element '" + selector + "' not found");
            }
            if (!isInteractable(element)) {
                throw new IllegalStateException("Element '" + selector + "' is not interactable (hidden or disabled)");
            }
            WebWindow currentWindow = htmlPage.getEnclosingWindow();
            DialogExpectation expectation = pendingDialogExpectation;
            Page page = ((HtmlElement) element).click();
            updateFromClickedPage(page, currentWindow);
            verifyDialogExpectation(expectation);
            return this;
        }
        if (usesWebDriver()) {
            WebElement el = webDriver.findElement(By.cssSelector(selector));
            try {
                el.click();
            } catch (UnhandledAlertException ex) {
                handleWebDriverDialog(ex);
            }
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
            currentDocumentIsBrowserPage = true;
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

    /**
     * Replaces the current text in the input or textarea matched by the selector, types the supplied
     * text using browser keyboard events, then blurs the field to trigger commit handlers.
     *
     * @param selector CSS selector for the input or textarea
     * @param text text to type
     * @return this WebTest
     * @throws IOException if a browser operation fails
     * @throws InterruptedException if waiting for the element is interrupted
     */
    public WebTest typeInto(String selector, String text) throws IOException, InterruptedException {
        if (browser == Browser.HTML_UNIT) {
            waitFor(doc -> doc.selectFirst(selector) != null);
            DomElement element = htmlPage.querySelector(selector);
            if (element == null) {
                throw new IllegalArgumentException("Element '" + selector + "' not found");
            }
            if (!isInteractable(element)) {
                throw new IllegalStateException("Element '" + selector + "' is not interactable (hidden or disabled)");
            }
            if (element instanceof HtmlInput input) {
                input.focus();
                input.setValue("");
                input.type(text);
            } else if (element instanceof HtmlTextArea textarea) {
                textarea.focus();
                textarea.setText("");
                textarea.type(text);
            } else {
                throw new IllegalArgumentException("Element '" + selector + "' is not an input or textarea");
            }
            element.removeFocus();
            element.fireEvent("blur");
            renderedHtml = serialiseHtmlPage();
            currentDocument = parseDocument(renderedHtml);
            currentDocumentIsBrowserPage = true;
            checkForJavascriptErrors();
            return this;
        }
        if (usesWebDriver()) {
            WebElement element = webDriver.findElement(By.cssSelector(selector));
            String originalValue = element.getDomProperty("value");
            element.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            element.sendKeys(text);
            ((JavascriptExecutor) webDriver).executeScript("""
                    var element = arguments[0];
                    var originalValue = arguments[1];
                    if (element.value !== originalValue) {
                        var changeDispatched = false;
                        element.addEventListener('change', function() { changeDispatched = true; }, { once: true });
                        element.addEventListener('blur', function() {
                            if (!changeDispatched) {
                                element.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                        }, { once: true, capture: true });
                    }
                    element.blur();
                    """, element, originalValue);
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
            if (browser == Browser.HTML_UNIT && currentDocumentIsBrowserPage && htmlPage != null) {
                renderedHtml = serialiseHtmlPage();
                String pageUrl = htmlPage.getUrl() == null ? null : htmlPage.getUrl().toString();
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
                if (currentDocumentIsBrowserPage) {
                    htmlClient.waitForBackgroundJavaScript(50);
                } else {
                    Thread.sleep(50);
                }
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
        lastStatusIsAvailable = true;
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
        currentDocumentIsBrowserPage = false;
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

    private void updateFromClickedPage(Page clickedPage, WebWindow currentWindow) {
        if (clickedPage.getEnclosingWindow() == currentWindow) {
            updateFromPage(clickedPage);
            return;
        }

        htmlClient.setCurrentWindow(currentWindow);
        updateFromPage(currentWindow.getEnclosedPage());
    }

    private void updateFromPage(Page page) {
        this.htmlPage = page instanceof HtmlPage ? (HtmlPage) page : null;
        currentDocumentIsBrowserPage = htmlPage != null;
        this.lastWebResponse = page.getWebResponse();
        this.lastResponse = null;
        this.lastStatus = lastWebResponse.getStatusCode();
        lastStatusIsAvailable = true;
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
        if (pendingHtmlUnitDialogFailure != null) {
            IllegalStateException failure = pendingHtmlUnitDialogFailure;
            pendingHtmlUnitDialogFailure = null;
            throw failure;
        }
        if (console == null || console.getErrors().isEmpty() || ignoreJavascriptErrors) {
            return;
        }
        throw new RuntimeException("Page JavaScript errors: " + String.join(", ", console.getErrors()));
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
            if (!pooledDriver) {
                try {
                    webDriver.quit();
                } catch (Exception ex) {
                    log.debug("Failed to quit web driver", ex);
                }
            }
            webDriver = null;
        }

        console = null;
        htmlPage = null;
        currentDocumentIsBrowserPage = false;
        currentDocument = null;
        renderedHtml = null;
        currentUrl = null;
    }
}
