package com.fawry;
import com.fawry.AIIntegrationService;
import com.fawry.XmlGenerator;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;

import java.time.Duration;
import java.util.*;

public class ElementValidator {
    private static AppiumDriver driver;

    public ElementValidator(AppiumDriver driver) {
        this.driver = driver;
    }

    static boolean isPresent(By element) {
        try {
            return !driver.findElements(element).isEmpty();
        } catch (Exception e) {
            System.out.println("Error occurred while checking presence of element: " + element);
            e.printStackTrace();
            return false;
        }
    }

    private static boolean scrollToFindElement(By locator) {
        int maxScrolls = 5;
        for (int i = 0; i < maxScrolls; i++) {
            try {
                if (isPresent(locator)) {
                    return true;
                }
                swipeFromCenterWithDirection(SwipeDirection.Down);
            } catch (Exception e) {
                System.out.println("Scroll attempt " + (i + 1) + " failed for locator: " + locator);
                e.printStackTrace();
            }
        }
        return false;
    }

    public static List<By> validateAndHealElements(List<By> originalElements) {
        List<By> updatedElements = new ArrayList<>();
        List<Integer> missingIndexes = new ArrayList<>();
        List<String> damagedXpaths = new ArrayList<>();

        try {
            System.out.println("***** Validating Elements *****");

            XmlGenerator xmlGenerator = new XmlGenerator();
            AIIntegrationService aiService = new AIIntegrationService();
            xmlGenerator.setDriver(driver);

            for (int i = 0; i < originalElements.size(); i++) {
                By element = originalElements.get(i);
                if (scrollToFindElement(element)) {
                    System.out.println("Element found: " + element);
                    updatedElements.add(element);
                } else {
                    System.out.println("Element not found (even after scrolling): " + element);
                    missingIndexes.add(i);
                    damagedXpaths.add(element.toString().replace("By.xpath: ", ""));
                    updatedElements.add(null);
                }
            }

            if (!missingIndexes.isEmpty()) {
                System.out.println("Missing " + missingIndexes.size() + " elements, attempting AI self-healing...");
                xmlGenerator.generatePageXML();

                List<String> fixedXpaths = aiService.autoAnalyzeAndFix(damagedXpaths);

                if (fixedXpaths != null && !fixedXpaths.isEmpty()) {
                    for (int j = 0; j < missingIndexes.size(); j++) {
                        int idx = missingIndexes.get(j);
                        if (j < fixedXpaths.size() && fixedXpaths.get(j) != null && !fixedXpaths.get(j).isEmpty()) {
                            updatedElements.set(idx, By.xpath(fixedXpaths.get(j).trim()));
                            System.out.println("AI generated new XPath for index " + idx + ": " + fixedXpaths.get(j).trim());
                        } else {
                            updatedElements.set(idx, originalElements.get(idx));
                            System.out.println("Fallback to original XPath for index " + idx);
                        }
                    }
                } else {
                    System.out.println("AI returned no xpaths. Falling back to originals.");
                    for (int idx : missingIndexes) {
                        updatedElements.set(idx, originalElements.get(idx));
                    }
                }
            }

            for (By element : updatedElements) {
                if (element == null || !scrollToFindElement(element)) {
                    System.out.println("Element still not found after AI healing: " + element);
                    return Collections.emptyList();
                }
            }

            System.out.println("***** All Elements Validated *****");
            return updatedElements;

        } catch (Exception e) {
            System.out.println("Error in validateAndHealElements");
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    public static boolean swipeFromCenterWithDirection(SwipeDirection direction) {
        return swipeFromCenterWithDirection(direction, 300);
    }

    public enum SwipeDirection {
        Up,
        Down,
        Left,
        Right
    }
    public static boolean swipeFromCenterWithDirection(SwipeDirection direction, int delta) {
        try {
            int startX = driver.manage().window().getSize().width / 2;
            int startY = driver.manage().window().getSize().height / 2;
            int endX = startX;
            int endY = startY;
            switch (direction) {
                case Up:
                    endY = startY - delta;
                    break;
                case Down:
                    endY = startY + delta;
                    break;
                case Left:
                    endX = startX - delta;
                    break;
                case Right:
                    endX = startX + delta;
                    break;
            }
            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
            Sequence swipe = new Sequence(finger, 1);
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(0), PointerInput.Origin.viewport(), startX, startY));
            swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(500), PointerInput.Origin.viewport(), endX, endY));
            swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
            driver.perform(Collections.singletonList(swipe));
            return true;
        } catch (Exception e) {
            System.out.println( "swipeFromCenterWithDirection");
            return false;
        }
    }
}
