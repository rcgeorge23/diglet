package io.github.rcgeorge23.diglet;

import java.util.function.Supplier;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/**
 * Shares a single WebDriver across multiple WebTest instances.
 *
 * <p>Browser processes are expensive to start, so reusing one process across tests keeps
 * real-browser coverage affordable. Cookies and web storage are cleared on each use so tests stay
 * isolated. Close the pool to quit the browser process.</p>
 */
public final class WebDriverPool implements Supplier<WebDriver>, AutoCloseable {

    private final Supplier<WebDriver> supplier;
    private WebDriver driver;
    private boolean closed;

    public WebDriverPool(Supplier<WebDriver> supplier) {
        this.supplier = supplier;
    }

    @Override
    public WebDriver get() {
        if (closed) {
            throw new IllegalStateException("WebDriverPool is closed");
        }
        if (driver == null) {
            driver = supplier.get();
        }
        return driver;
    }

    /**
     * Clears cookies and web storage so the next test starts from a clean browser state.
     */
    public void reset() {
        WebDriver current = get();
        current.manage().deleteAllCookies();
        if (current instanceof JavascriptExecutor javascriptExecutor) {
            try {
                javascriptExecutor.executeScript("window.localStorage.clear(); window.sessionStorage.clear();");
            } catch (RuntimeException ignored) {
                // Storage may be unavailable (for example on about:blank); nothing to clear.
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }
}
