package com.fawry;

import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;

import java.time.Duration;
import java.util.*;

public class ElementValidator {

    public final AppiumDriver driver;

    public ElementValidator(AppiumDriver driver) {
        this.driver = driver;
    }

    /**
     * Check if an element is present and displayed on screen.
     */
    private boolean isElementPresent(By locator) {
        try {
            List<WebElement> elements = driver.findElements(locator);
            return !elements.isEmpty() && elements.get(0).isDisplayed();
        } catch (Exception e) {
            System.out.println("[WARN] Failed to check element presence: " + locator);
            return false;
        }
    }

    /**
     * Try scrolling several times to locate an element.
     */
    private boolean scrollToFindElement(By locator) {
        int maxScrolls = 5;
        for (int i = 0; i < maxScrolls; i++) {
            if (isElementPresent(locator)) return true;
            swipeFromCenterWithDirection(SwipeDirection.DOWN, 350);
        }
        return false;
    }

    /**
     * Main validation & healing workflow.
     */
    public List<By> validateAndHealElements(List<By> originalLocators) {
        List<By> updatedLocators = new ArrayList<>(originalLocators);
        List<Integer> missingIndexes = new ArrayList<>();
        List<String> damagedXpaths = new ArrayList<>();

        try {
            System.out.println("===== Element Validation Started =====");

            XmlGenerator xmlGenerator = new XmlGenerator();
            xmlGenerator.setDriver(driver);

            AIIntegrationService aiService = new AIIntegrationService();

            // Step 1: Validate & detect missing elements
            for (int i = 0; i < originalLocators.size(); i++) {
                By locator = originalLocators.get(i);

                if (isElementPresent(locator)) {
                    System.out.println("[OK] Element found directly: " + locator);
                } else if (scrollToFindElement(locator)) {
                    System.out.println("[OK] Element found after scroll: " + locator);
                } else {
                    System.out.println("[MISSING] Element not found: " + locator);
                    missingIndexes.add(i);
                    damagedXpaths.add(locator.toString().replace("By.xpath: ", ""));
                }
            }

            // Step 2: If any missing → AI healing
            if (!missingIndexes.isEmpty()) {
                System.out.println("[AI] Healing " + missingIndexes.size() + " missing elements...");
                xmlGenerator.generatePageXML();
                List<String> healedXpaths = aiService.autoAnalyzeAndFix(damagedXpaths);

                for (int j = 0; j < missingIndexes.size(); j++) {
                    int idx = missingIndexes.get(j);
                    String healed = healedXpaths != null && j < healedXpaths.size() ? healedXpaths.get(j) : null;

                    if (healed != null && !healed.isEmpty()) {
                        updatedLocators.set(idx, By.xpath(healed.trim()));
                        System.out.println("[AI] Fixed XPath at index " + idx + ": " + healed);
                    } else {
                        System.out.println("[FALLBACK] No AI fix; keeping original: " + originalLocators.get(idx));
                    }
                }
            }

            // Step 3: Re-validate healed elements
            for (By locator : updatedLocators) {
                if (!isElementPresent(locator) && !scrollToFindElement(locator)) {
                    System.out.println("[FAIL] Still not found after healing: " + locator);
                }
            }

            System.out.println("===== Element Validation Completed =====");
            return updatedLocators;

        } catch (Exception e) {
            System.out.println("[ERROR] Validation failed: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Generic swipe helper.
     */
    private boolean swipeFromCenterWithDirection(SwipeDirection direction, int delta) {
        try {
            int width = driver.manage().window().getSize().width;
            int height = driver.manage().window().getSize().height;
            int startX = width / 2;
            int startY = height / 2;
            int endX = startX;
            int endY = startY;

            switch (direction) {
                case UP:    endY = startY - delta; break;
                case DOWN:  endY = startY + delta; break;
                case LEFT:  endX = startX - delta; break;
                case RIGHT: endX = startX + delta; break;
            }

            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence swipe = new Sequence(finger, 1);
            swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
            swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(500), PointerInput.Origin.viewport(), endX, endY));
            swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

            driver.perform(Collections.singletonList(swipe));
            return true;
        } catch (Exception e) {
            System.out.println("[WARN] Swipe action failed: " + e.getMessage());
            return false;
        }
    }

    public enum SwipeDirection {
        UP, DOWN, LEFT, RIGHT
    }
}