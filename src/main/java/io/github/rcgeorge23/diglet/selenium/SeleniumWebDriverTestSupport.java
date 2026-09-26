package io.github.rcgeorge23.diglet.selenium;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;

/**
 * Lifecycle-free, Selenium-based interaction helpers for browser tests.
 *
 * <p>The caller owns and configures the WebDriver lifecycle; this class only provides waits and
 * hardened interactions against the supplied driver.</p>
 */
public final class SeleniumWebDriverTestSupport {
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DOCUMENT_READY_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration MODAL_WAIT_TIMEOUT = Duration.ofSeconds(40);

    private final WebDriver driver;
    private final WebDriverWait wait;

    /**
     * Creates helpers with the default 20-second interaction wait.
     *
     * @param driver caller-owned Selenium driver
     */
    public SeleniumWebDriverTestSupport(WebDriver driver) {
        this(driver, new WebDriverWait(Objects.requireNonNull(driver, "driver"), DEFAULT_WAIT_TIMEOUT));
    }

    /**
     * Creates helpers that reuse a caller-provided wait.
     *
     * @param driver the caller-owned browser driver
     * @param wait the interaction wait to reuse
     */
    public SeleniumWebDriverTestSupport(WebDriver driver, WebDriverWait wait) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.wait = Objects.requireNonNull(wait, "wait");
    }

    /**
     * Opens an absolute URL and waits until its document is interactive or complete.
     *
     * @param absoluteUrl absolute URL to open
     */
    public void open(String absoluteUrl) {
        driver.get(Objects.requireNonNull(absoluteUrl, "absoluteUrl"));
        waitForDocumentReady();
    }

    /** Waits until the current document is interactive or complete. */
    public void waitForDocumentReady() {
        new WebDriverWait(driver, DOCUMENT_READY_TIMEOUT)
                .until((ExpectedCondition<Boolean>) currentDriver -> {
                    Object readyState = js(currentDriver).executeScript("return document.readyState");
                    return "interactive".equals(readyState) || "complete".equals(readyState);
                });
    }

    /**
     * Waits for a Bootstrap-style modal to be visible.
     *
     * @param modalId DOM ID of the modal
     */
    public void waitForModalToBeVisible(String modalId) {
        new WebDriverWait(driver, MODAL_WAIT_TIMEOUT)
                .ignoring(StaleElementReferenceException.class)
                .until(currentDriver -> isModalVisible(currentDriver, modalId));
    }

    /**
     * Waits for a Bootstrap-style modal and its backdrop to be hidden.
     *
     * @param modalId DOM ID of the modal
     */
    public void waitForModalToBeHidden(String modalId) {
        new WebDriverWait(driver, MODAL_WAIT_TIMEOUT)
                .ignoring(StaleElementReferenceException.class)
                .until(currentDriver -> {
                    if (isModalVisible(currentDriver, modalId)) {
                        return false;
                    }

                    boolean visibleBackdrop = currentDriver.findElements(By.cssSelector(".modal-backdrop")).stream()
                            .anyMatch(WebElement::isDisplayed);
                    String bodyClasses = currentDriver.findElement(By.tagName("body")).getAttribute("class");
                    boolean bodyStillOwnsModal = bodyClasses != null && bodyClasses.contains("modal-open");
                    boolean anotherModalIsVisible = Boolean.TRUE.equals(js(currentDriver).executeScript(
                            """
                            return Array.from(document.querySelectorAll('.modal.show'))
                                    .some(modal => modal.id !== arguments[0] && modal.getClientRects().length > 0);
                            """,
                            modalId));
                    return anotherModalIsVisible || (!visibleBackdrop && !bodyStillOwnsModal);
                });
    }

    /**
     * Clicks a displayed, enabled element matching a CSS selector.
     *
     * @param cssSelector CSS selector for the element
     */
    public void clickVisible(String cssSelector) {
        click(By.cssSelector(cssSelector));
    }

    /**
     * Waits for a visible, enabled element, scrolls it into view, and clicks it.
     *
     * @param locator Selenium locator for the element
     */
    public void click(By locator) {
        wait.until(currentDriver -> {
            List<WebElement> candidates = currentDriver.findElements(locator);
            for (WebElement element : candidates) {
                try {
                    if (!element.isDisplayed() || !element.isEnabled()) {
                        continue;
                    }
                    js(currentDriver).executeScript(
                            "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", element);
                    js(currentDriver).executeScript("arguments[0].click();", element);
                    return true;
                } catch (StaleElementReferenceException ignored) {
                    // The page refreshed while iterating; wait for a fresh DOM snapshot.
                }
            }
            return null;
        });
    }

    /**
     * Clicks the first visible, enabled element found in locator priority order.
     *
     * @param locators Selenium locators in priority order
     */
    public void clickFirstDisplayed(By... locators) {
        List<By> searchOrder = Arrays.asList(locators);
        wait.until(currentDriver -> {
            for (By locator : searchOrder) {
                List<WebElement> candidates = currentDriver.findElements(locator);
                for (WebElement element : candidates) {
                    try {
                        if (!element.isDisplayed() || !element.isEnabled()) {
                            continue;
                        }
                        js(currentDriver).executeScript(
                                "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", element);
                        js(currentDriver).executeScript("arguments[0].click();", element);
                        return true;
                    } catch (StaleElementReferenceException ignored) {
                        // Continue searching so this wait cycle can recover from DOM refreshes.
                    }
                }
            }
            return null;
        });
    }

    /**
     * Replaces the selected input value using native Ctrl+A and sendKeys events.
     *
     * @param locator Selenium locator for the input
     * @param value replacement value
     */
    public void typeInto(By locator, String value) {
        Objects.requireNonNull(value, "value");
        wait.until(currentDriver -> {
            try {
                WebElement element = currentDriver.findElement(locator);
                if (!element.isDisplayed() || !element.isEnabled()) {
                    return null;
                }
                js(currentDriver).executeScript(
                        "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", element);
                if (value.isEmpty()) {
                    element.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
                } else {
                    element.sendKeys(Keys.chord(Keys.CONTROL, "a"), value);
                }
                return true;
            } catch (StaleElementReferenceException ignored) {
                return null;
            }
        });
    }

    /**
     * Sets a checkbox to selected and dispatches a bubbling change event.
     *
     * @param locator Selenium locator for the checkbox
     */
    public void setCheckbox(By locator) {
        wait.until(currentDriver -> {
            try {
                WebElement element = currentDriver.findElement(locator);
                if (!element.isDisplayed() || !element.isEnabled()) {
                    return null;
                }
                js(currentDriver).executeScript(
                        "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", element);
                if (!element.isSelected()) {
                    js(currentDriver).executeScript(
                            "arguments[0].checked = true; arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                            element);
                }
                return element.isSelected();
            } catch (StaleElementReferenceException ignored) {
                return null;
            }
        });
    }

    /**
     * Selects a radio button and dispatches a bubbling change event.
     *
     * @param locator Selenium locator for the radio input
     */
    public void selectRadio(By locator) {
        wait.until(currentDriver -> {
            try {
                WebElement element = currentDriver.findElement(locator);
                if (!element.isDisplayed() || !element.isEnabled()) {
                    return null;
                }
                js(currentDriver).executeScript(
                        "arguments[0].scrollIntoView({block: 'center', inline: 'center'});", element);
                if (!element.isSelected()) {
                    js(currentDriver).executeScript(
                            "arguments[0].checked = true; arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                            element);
                }
                return element.isSelected();
            } catch (StaleElementReferenceException ignored) {
                return null;
            }
        });
    }

    /**
     * Executes JavaScript in the current page.
     *
     * @param script JavaScript source
     * @param arguments script arguments
     * @return the script result
     */
    public Object executeScript(String script, Object... arguments) {
        return js(driver).executeScript(script, arguments);
    }

    private static boolean isModalVisible(WebDriver driver, String modalId) {
        Object result = js(driver).executeScript(
                """
                const modal = document.getElementById(arguments[0]);
                if (!modal) {
                    return false;
                }
                const classes = modal.className || '';
                const ariaHidden = modal.getAttribute('aria-hidden');
                const style = window.getComputedStyle(modal);
                const hasShowClass = classes.includes('show');
                const ariaVisible = ariaHidden === null || ariaHidden === 'false';
                const displayVisible = style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
                const inLayout = modal.getClientRects().length > 0;
                return hasShowClass && ariaVisible && displayVisible && inLayout;
                """,
                modalId);
        return Boolean.TRUE.equals(result);
    }

    private static JavascriptExecutor js(WebDriver driver) {
        return (JavascriptExecutor) driver;
    }
}
