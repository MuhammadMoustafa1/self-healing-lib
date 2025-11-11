package com.fawry;

import com.fawry.utilities.Log;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class By extends org.openqa.selenium.By {

    // Static driver shared by all locator instances
    private static AppiumDriver driver;

    // Original locator reference
    private final org.openqa.selenium.By originalBy;

    // Cache for healed locators
    private static final Map<String, org.openqa.selenium.By> healedCache = new ConcurrentHashMap<>();
    
    // Default wait timeout (in seconds)
    private static final int DEFAULT_WAIT_TIMEOUT = 10;
    
    // Wait time for scrolling/swiping to complete (in milliseconds)
    private static final int SCROLL_WAIT_TIME_MS = 500;

    // ====== Constructor & Driver Setter ======
    private By(org.openqa.selenium.By by) {
        this.originalBy = by;
    }

    public static void setDriver(AppiumDriver appiumDriver) {
        driver = appiumDriver;
    }

    // ====== Supported Web Locator Factories ======
    public static By xpath(String xpath) { return new By(org.openqa.selenium.By.xpath(xpath)); }
    public static By id(String id) { return new By(org.openqa.selenium.By.id(id)); }
    public static By name(String name) { return new By(org.openqa.selenium.By.name(name)); }
    public static By cssSelector(String selector) { return new By(org.openqa.selenium.By.cssSelector(selector)); }
    public static By className(String className) { return new By(org.openqa.selenium.By.className(className)); }
    public static By tagName(String tagName) { return new By(org.openqa.selenium.By.tagName(tagName)); }
    public static By linkText(String linkText) { return new By(org.openqa.selenium.By.linkText(linkText)); }
    public static By partialLinkText(String partialLinkText) { return new By(org.openqa.selenium.By.partialLinkText(partialLinkText)); }

    // ====== Element Search ======
    @Override
    public WebElement findElement(SearchContext context) {
        String locatorKey = originalBy.toString();
        
        // First, wait for scrolling/swiping to complete
        waitForScrollOrSwipeToComplete();
        
        try {
            // Check if we have a cached healed locator
            if (healedCache.containsKey(locatorKey)) {
                org.openqa.selenium.By cachedBy = healedCache.get(locatorKey);
                return waitForElementVisibility(cachedBy, context);
            }
            
            // Try with original locator, wait for visibility first
            return waitForElementVisibility(originalBy, context);
            
        } catch (NoSuchElementException | TimeoutException | InvalidElementStateException e) {
            Log.info("⚠️ Element issue detected for locator: " + locatorKey);
            Log.info("🧠 Triggering healing process...");
            
            // Wait for scrolling/swiping before healing
            waitForScrollOrSwipeToComplete();
            
            org.openqa.selenium.By healedBy = healLocator(locatorKey);

            if (healedBy != null) {
                healedCache.put(locatorKey, healedBy);
                Log.info("✅ Healing successful. Cached healed locator: " + healedBy);
                
                // Wait for healed element to be visible
                return waitForElementVisibility(healedBy, context);
            }

            throw new NoSuchElementException("❌ Failed to heal locator: " + locatorKey, e);
        }
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        String locatorKey = originalBy.toString();
        
        // First, wait for scrolling/swiping to complete
        waitForScrollOrSwipeToComplete();
        
        try {
            // Check if we have a cached healed locator
            if (healedCache.containsKey(locatorKey)) {
                org.openqa.selenium.By cachedBy = healedCache.get(locatorKey);
                return waitForElementsVisibility(cachedBy, context);
            }
            
            // Try with original locator, wait for visibility first
            return waitForElementsVisibility(originalBy, context);
            
        } catch (NoSuchElementException | TimeoutException | InvalidElementStateException e) {
            Log.info("⚠️ Elements issue detected for locator: " + locatorKey);
            Log.info("🧠 Triggering healing process...");
            
            // Wait for scrolling/swiping before healing
            waitForScrollOrSwipeToComplete();
            
            org.openqa.selenium.By healedBy = healLocator(locatorKey);

            if (healedBy != null) {
                healedCache.put(locatorKey, healedBy);
                Log.info("✅ Healing successful for elements. Cached healed locator: " + healedBy);
                
                // Wait for healed elements to be visible
                return waitForElementsVisibility(healedBy, context);
            }

            throw new NoSuchElementException("❌ Failed to heal elements for locator: " + locatorKey, e);
        }
    }

    // ====== Wait Utilities ======
    
    /**
     * Waits for scrolling or swiping animations to complete by checking if page source stabilizes.
     */
    private void waitForScrollOrSwipeToComplete() {
        if (driver == null) {
            return;
        }
        
        try {
            String previousPageSource = driver.getPageSource();
            Thread.sleep(SCROLL_WAIT_TIME_MS);
            
            int maxAttempts = 5;
            int attempt = 0;
            while (attempt < maxAttempts) {
                String currentPageSource = driver.getPageSource();
                if (currentPageSource.equals(previousPageSource)) {
                    // Page source is stable, scrolling/swiping is complete
                    Log.info("✅ Scrolling/swiping completed - page source is stable");
                    return;
                }
                previousPageSource = currentPageSource;
                Thread.sleep(SCROLL_WAIT_TIME_MS);
                attempt++;
            }
            Log.info("⚠️ Scrolling/swiping may still be in progress, proceeding anyway");
        } catch (Exception e) {
            Log.info("⚠️ Error checking scroll/swipe completion: " + e.getMessage());
            // Continue anyway - don't block execution
        }
    }
    
    /**
     * Waits for element to be visible before returning it.
     */
    private WebElement waitForElementVisibility(org.openqa.selenium.By by, SearchContext context) {
        // If context is WebDriver/AppiumDriver, use WebDriverWait
        if (context instanceof WebDriver) {
            try {
                WebDriverWait wait = new WebDriverWait((WebDriver) context, Duration.ofSeconds(DEFAULT_WAIT_TIMEOUT));
                return wait.until(ExpectedConditions.presenceOfElementLocated(by));
            } catch (TimeoutException e) {
                // If wait times out, try direct findElement as fallback
                Log.info("⚠️ Wait for visibility timed out, trying direct findElement");
                return by.findElement(context);
            }
        }
        
        // If context is WebElement (nested search), try with retries
        if (context instanceof WebElement) {
            int maxRetries = 3;
            for (int i = 0; i < maxRetries; i++) {
                try {
                    WebElement element = by.findElement(context);
                    // Check if element is displayed
                    if (element.isDisplayed()) {
                        return element;
                    }
                } catch (NoSuchElementException e) {
                    if (i < maxRetries - 1) {
                        try {
                            Thread.sleep(500); // Wait 500ms before retry
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    throw e;
                }
            }
        }
        
        // Fallback: direct findElement
        return by.findElement(context);
    }
    
    /**
     * Waits for elements to be visible before returning them.
     */
    private List<WebElement> waitForElementsVisibility(org.openqa.selenium.By by, SearchContext context) {
        // If context is WebDriver/AppiumDriver, use WebDriverWait
        if (context instanceof WebDriver) {
            try {
                WebDriverWait wait = new WebDriverWait((WebDriver) context, Duration.ofSeconds(DEFAULT_WAIT_TIMEOUT));
                wait.until(ExpectedConditions.presenceOfElementLocated(by));
                // After waiting for at least one element, return all found elements
                return by.findElements(context);
            } catch (TimeoutException e) {
                // If wait times out, try direct findElements as fallback
                Log.info("⚠️ Wait for visibility timed out, trying direct findElements");
                return by.findElements(context);
            }
        }
        
        // If context is WebElement (nested search), try with retries
        if (context instanceof WebElement) {
            int maxRetries = 3;
            for (int i = 0; i < maxRetries; i++) {
                List<WebElement> elements = by.findElements(context);
                if (!elements.isEmpty()) {
                    return elements;
                }
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(500); // Wait 500ms before retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        
        // Fallback: direct findElements
        return by.findElements(context);
    }
    
    // ====== Healing Logic ======
    private org.openqa.selenium.By healLocator(String rawLocator) {
        try {
            if (driver == null) {
                Log.info("❌ AppiumDriver not set. Cannot capture XML for healing.");
                return null;
            }

            Log.info("📱 Healing locator type: " + originalBy.getClass().getSimpleName() + " -> " + rawLocator);

            // Clean the locator text for AI input
            String cleanedLocator = rawLocator
                    .replace("By.xpath: ", "")
                    .replace("By.id: ", "")
                    .replace("By.name: ", "")
                    .replace("By.cssSelector: ", "")
                    .replace("By.className: ", "")
                    .replace("By.tagName: ", "")
                    .replace("By.linkText: ", "")
                    .replace("By.partialLinkText: ", "")
                    .trim();

            // Generate XML snapshot of the current page
            XmlGenerator xmlGenerator = new XmlGenerator();
            xmlGenerator.setDriver(driver);
            xmlGenerator.clearXmlSnapshotsDirectory();
            xmlGenerator.generatePageXML();

            // Send locator to AI for analysis and repair
            List<String> healedLocators = new AIIntegrationService().autoAnalyzeAndFix(List.of(cleanedLocator));

            if (healedLocators != null && !healedLocators.isEmpty()) {
                String healedXpath = healedLocators.get(0).trim();
                Log.info("🌐 Returning healed locator: " + healedXpath);
                return org.openqa.selenium.By.xpath(healedXpath);
            }

        } catch (Exception e) {
            Log.info("❌ Healing process failed for locator: " + rawLocator);
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public String toString() {
        return "ByHealable(" + originalBy.toString() + ")";
    }
}
