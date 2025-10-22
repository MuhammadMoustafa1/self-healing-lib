package com.fawry;

import com.fawry.utilities.Log;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class By extends org.openqa.selenium.By {

    // Static driver shared by all locator instances
    private static AppiumDriver driver;

    // Original locator reference
    private final org.openqa.selenium.By originalBy;

    // Cache for healed locators
    private static final Map<String, org.openqa.selenium.By> healedCache = new ConcurrentHashMap<>();

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
        try {
            if (healedCache.containsKey(locatorKey)) {
                return healedCache.get(locatorKey).findElement(context);
            }
            return originalBy.findElement(context);
        } catch (NoSuchElementException | TimeoutException | InvalidElementStateException e) {
            Log.info("⚠️ Element issue detected for locator: " + locatorKey);
            Log.info("🧠 Triggering healing process...");
            org.openqa.selenium.By healedBy = healLocator(locatorKey);

            if (healedBy != null) {
                healedCache.put(locatorKey, healedBy);
                Log.info("✅ Healing successful. Cached healed locator: " + healedBy);
                return healedBy.findElement(context);
            }

            throw new NoSuchElementException("❌ Failed to heal locator: " + locatorKey, e);
        }
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        String locatorKey = originalBy.toString();
        try {
            if (healedCache.containsKey(locatorKey)) {
                return healedCache.get(locatorKey).findElements(context);
            }
            return originalBy.findElements(context);
        } catch (NoSuchElementException | TimeoutException | InvalidElementStateException e) {
            Log.info("⚠️ Elements issue detected for locator: " + locatorKey);
            Log.info("🧠 Triggering healing process...");
            org.openqa.selenium.By healedBy = healLocator(locatorKey);

            if (healedBy != null) {
                healedCache.put(locatorKey, healedBy);
                Log.info("✅ Healing successful for elements. Cached healed locator: " + healedBy);
                return healedBy.findElements(context);
            }

            throw new NoSuchElementException("❌ Failed to heal elements for locator: " + locatorKey, e);
        }
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
