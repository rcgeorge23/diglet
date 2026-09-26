package io.github.rcgeorge23.diglet.selenium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.os.ExecutableFinder;

/** Creates headless Chrome WebDriver instances with consistent test defaults. */
public final class ChromeDriverFactory {

    private static final Dimension DEFAULT_WINDOW_SIZE = new Dimension(1920, 1080);
    private static final Duration IMPLICIT_WAIT = Duration.ofSeconds(10);
    private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(90);

    private ChromeDriverFactory() {
    }

    /**
     * Creates a headless Chrome driver and applies Diglet's default viewport, waits, and logging.
     * Chrome and ChromeDriver must be installed by the caller or available on {@code PATH}.
     *
     * @return the configured Chrome WebDriver
     */
    public static WebDriver createChromeDriver() {
        ChromeOptions options = new ChromeOptions();
        configureChromeBinary(options);
        configureDriverExecutable();
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-background-timer-throttling");
        options.addArguments("--disable-backgrounding-occluded-windows");
        options.addArguments("--disable-renderer-backgrounding");
        options.addArguments("--disable-features=TranslateUI");
        options.addArguments("--disable-ipc-flooding-protection");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--remote-debugging-port=0");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        // Parallel Chrome tests can momentarily starve renderers; return once the DOM is
        // available and let the test helpers perform their explicit readiness waits.
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        LoggingPreferences loggingPreferences = new LoggingPreferences();
        loggingPreferences.enable(LogType.PERFORMANCE, Level.ALL);
        options.setCapability("goog:loggingPrefs", loggingPreferences);

        WebDriver driver = new ChromeDriver(options);
        hideWebdriverFlag(driver);
        resetDriverDefaults(driver);
        return driver;
    }

    /**
     * Restores the standard viewport and timeout values on an existing driver.
     *
     * @param driver WebDriver to configure
     */
    public static void resetDriverDefaults(WebDriver driver) {
        driver.manage().window().setSize(DEFAULT_WINDOW_SIZE);
        driver.manage().timeouts().implicitlyWait(IMPLICIT_WAIT);
        // Keep the browser from failing a navigation during temporary CI contention.
        driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);
    }

    private static void hideWebdriverFlag(WebDriver driver) {
        try {
            Map<String, Object> params = Map.of(
                    "source", "Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
            ((HasCdp) driver).executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", params);
        } catch (Exception ignored) {
            // CDP not available; anti-automation flag still disabled via command-line args
        }
    }

    private static void configureChromeBinary(ChromeOptions options) {
        String configuredBinary = System.getProperty("webdriver.chrome.binary");
        if (isBlank(configuredBinary)) {
            configuredBinary = System.getenv("CHROME_BINARY");
        }
        if (isBlank(configuredBinary)) {
            configuredBinary = System.getenv("CHROME_BIN");
        }
        if (isBlank(configuredBinary)) {
            configuredBinary = new ExecutableFinder().find("google-chrome");
        }
        if (isBlank(configuredBinary)) {
            configuredBinary = new ExecutableFinder().find("chromium");
        }
        if (isBlank(configuredBinary)) {
            configuredBinary = new ExecutableFinder().find("chromium-browser");
        }
        if (!isBlank(configuredBinary)) {
            options.setBinary(configuredBinary);
        }
    }

    private static void configureDriverExecutable() {
        String configuredDriver = System.getProperty("webdriver.chrome.driver");
        if (isBlank(configuredDriver)) {
            configuredDriver = System.getenv("CHROMEDRIVER_PATH");
        }
        if (isBlank(configuredDriver)) {
            configuredDriver = findChromeWebDriver();
        }
        if (isBlank(configuredDriver)) {
            configuredDriver = new ExecutableFinder().find("chromedriver");
        }
        if (!isBlank(configuredDriver)) {
            System.setProperty("webdriver.chrome.driver", configuredDriver);
        }
    }

    private static String findChromeWebDriver() {
        String configuredDriver = System.getenv("CHROMEWEBDRIVER");
        if (isBlank(configuredDriver)) {
            return null;
        }

        Path path = Path.of(configuredDriver);
        if (Files.isDirectory(path)) {
            String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "chromedriver.exe"
                    : "chromedriver";
            path = path.resolve(executable);
        }
        return Files.isExecutable(path) ? path.toString() : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
