package com.fawry;

import com.fawry.utilities.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIIntegrationService {
    private static final String QWENMOE_API_URL = "http://10.100.55.98:8660/v1/chat/completions";
    private static final String MODEL = "./qwenmoe/content/qwenmoe/";
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    public AIIntegrationService() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Main entry: give it damaged XPaths and an XML snapshot file path.
     */
    public List<String> analyzeAndGenerateXPaths(List<String> damagedXPaths, String xmlSnapshotPath) {
        try {
            String xmlSnapshotContent = readFileContent(xmlSnapshotPath);
            if (xmlSnapshotContent == null) xmlSnapshotContent = "";

            Log.info("XML Snapshot content sent to AI model:\n" + xmlSnapshotContent);

            String prompt = createAnalysisPrompt(damagedXPaths, xmlSnapshotContent);
            Log.info("Sending request to AI model with prompt:\n" + prompt);

            String aiResponse = callQwenMoeAPI(prompt);
            List<String> xpaths = extractXPathFromAIResponse(aiResponse);

            if (xpaths == null || xpaths.isEmpty()) {
                Log.info("Failed to extract valid XPaths from AI response");
                return null;
            }

            Log.info("Generated XPaths from AI analysis: " + xpaths);
            return xpaths;

        } catch (Exception e) {
            Log.error("AI analysis failed", e);
            return null;
        }
    }

//    public List<String> analyzeAndGenerateXPaths(List<String> damagedXPaths, String xmlSnapshotPath) {
//        try {
//            String xmlSnapshotContent = readFileContent(xmlSnapshotPath);
//            if (xmlSnapshotContent == null) xmlSnapshotContent = "";
//
//            String prompt = createAnalysisPrompt(damagedXPaths, xmlSnapshotContent);
//            Log.info("Sending request to AI model with prompt:\n" + prompt);
//
//            String aiResponse = callQwenMoeAPI(prompt);
//            List<String> xpaths = extractXPathFromAIResponse(aiResponse);
//
//            if (xpaths == null || xpaths.isEmpty()) {
//                Log.info("Failed to extract valid XPaths from AI response");
//                return null;
//            }
//
//            Log.info("Generated XPaths from AI analysis: " + xpaths);
//            return xpaths;
//
//        } catch (Exception e) {
//            Log.error("AI analysis failed", e);
//            return null;
//        }
//    }


    private String callQwenMoeAPI(String prompt) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", MODEL);

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);

        requestBody.set("messages", messages);

        Request request = new Request.Builder()
                .url(QWENMOE_API_URL)
                .post(RequestBody.create(
                        requestBody.toString(),
                        MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer EMPTY")
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "empty body";
                Log.info("API request failed. Status: " + response.code() + ", Body: " + errorBody);
                throw new IOException("Unexpected response: " + response);
            }

            String responseBody = response.body().string();
            Log.info("Raw API response: " + responseBody);

            JsonNode root = mapper.readTree(responseBody);
            String aiResponse = root.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText()
                    .trim();

            Log.info("AI model response: " + aiResponse);
            return aiResponse;
        }
    }

    private String readFileContent(String filePath) {
        Path path = Paths.get(filePath);
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append(System.lineSeparator());
            }
            return content.toString().trim();
        } catch (IOException e) {
            Log.error("Unable to read file " + filePath, e);
            return null;
        }
    }

    /**
     * Create prompt for the AI with damaged XPaths and XML snapshot.
     */
    private String createAnalysisPrompt(List<String> damagedLocators, String xmlSnapshot) {
        boolean hasIOS = damagedLocators.stream().anyMatch(l ->
                l.contains("XCUIElementType") || l.contains("name") || l.contains("label"));
        boolean hasAndroid = damagedLocators.stream().anyMatch(l ->
                l.contains("android.widget") || l.contains("resource-id") || l.contains("@text") || l.contains("By.id"));

        String platform;
        String platformExamples;

        if (hasIOS && !hasAndroid) {
            platform = "iOS";
            platformExamples = """
            Input:
            By.id("loginBtn")
            Output:
            //XCUIElementTypeButton[@name='loginBtn']
            """;
        } else if (hasAndroid && !hasIOS) {
            platform = "Android";
            platformExamples = """
            Input:
            By.id("com.example:id/loginBtn")
            Output:
            //android.widget.Button[contains(@resource-id,'loginBtn')]
            """;
        } else {
            platform = "Android and iOS";
            platformExamples = """
            Input:
            By.id("com.example:id/loginBtn") | By.className("XCUIElementTypeButton")
            Output:
            //android.widget.Button[contains(@resource-id,'loginBtn')]
            //XCUIElementTypeButton
            """;
        }

        StringBuilder listBuilder = new StringBuilder();
        for (int i = 0; i < damagedLocators.size(); i++) {
            listBuilder.append(i + 1).append(". ").append(damagedLocators.get(i)).append("\n");
        }

        String rules = """
        - For each damaged locator, return ONLY the single best/corrected locator (one per line, no explanations).
        - Do NOT join multiple XPaths with '|'.
        - For each damaged locator (By.id, By.className, By.cssSelector, By.tagName, By.linkText, By.partialLinkText, XPath), find the closest match in the XML snapshot.
        - If an exact match exists, use it. Otherwise, generate corrected locator(s) using flexible attribute matches (contains(), starts-with(), normalize-space()).
        - Prefer attributes based on platform:
          * iOS → @name, @label, @value
          * Android → @resource-id, @content-desc, @text
        - Preserve relationships (sibling, parent, ancestor) if present.
        - Handle numbers in attribute values and node names correctly.
        - Be case-insensitive when matching attribute values and node names.
        - Support mixed Android and iOS locators in the same input.
        - Support locators like By.cssSelector, By.tagName, By.linkText, By.partialLinkText, or other custom attributes.
        - If a locator contains a word in the middle, you may remove it if needed to find a match.
        - You may change letters at the start or in the middle of attribute values to find the closest match.
        - If a locator contains an index (e.g., [2]), keep the index unchanged in the output.
        - Normalize spaces, tabs, or newlines in attributes before matching.
        - If the tag name changes slightly (e.g., Button → TextView), still return the closest valid locator.
        """;

        String learningHint = String.format("""
        MODEL LEARNING CONTEXT:
        - You are analyzing damaged locators for platform: %s.
        - Learn from the XML snapshot structure below: detect how attributes are used in this app (naming conventions, casing, id patterns, etc.).
        - Use this snapshot as a reference to adapt and correct locators intelligently.
        - Ensure the final suggestions align with the current app’s platform structure.
        """, platform);

        return String.format(
                "ROLE: You are an advanced automation test assistant specializing in self-healing mobile locators.\n" +
                        "PLATFORM: %s\n" +
                        "%s\n" +
                        "TASK: Given a list of damaged locators (By.id, By.className, By.cssSelector, By.tagName, By.linkText, By.partialLinkText, XPath, etc.) and the current XML snapshot, search inside the XML and return the most similar/corrected locator(s).\n\n" +
                        "INPUT:\n" +
                        "1. Damaged Locators:\n%s\n\n" +
                        "2. Current XML Snapshot:\n'''\n%s\n'''\n\n" +
                        "RULES:\n%s\n" +
                        "EXAMPLE:\n%s",
                platform,
                learningHint,
                listBuilder.toString().trim(),
                xmlSnapshot,
                rules,
                platformExamples
        );
    }

//    private String createAnalysisPrompt(List<String> damagedLocators, String xmlSnapshot) {
//        boolean hasIOS = damagedLocators.stream().anyMatch(l ->
//                l.contains("XCUIElementType") || l.contains("name") || l.contains("label"));
//        boolean hasAndroid = damagedLocators.stream().anyMatch(l ->
//                l.contains("android.widget") || l.contains("resource-id") || l.contains("@text") || l.contains("By.id"));
//
//        String platform;
//        String attributeHint;
//        String platformExamples;
//
//        if (hasIOS && !hasAndroid) {
//            platform = "iOS";
//            attributeHint = "@name, @label, @value";
//            platformExamples = """
//                    Input:
//                    //XCUIElementTypeButton[@name='Login']
//                    Output:
//                    //XCUIElementTypeButton[@name='Login']
//                    """;
//        } else if (hasAndroid && !hasIOS) {
//            platform = "Android";
//            attributeHint = "@resource-id, @content-desc, @text";
//            platformExamples = """
//                    Input:
//                    By.id("com.example:id/loginBtn")
//                    Output:
//                    //android.widget.Button[contains(@resource-id,'loginBtn')]
//                    """;
//        } else {
//            platform = "Android and iOS";
//            attributeHint = "Android: @resource-id, @content-desc, @text | iOS: @name, @label, @value";
//            platformExamples = """
//                    Input:
//                    //android.widget.TextView[@text='Login'] | //XCUIElementTypeStaticText[@name='Login']
//                    Output:
//                    //android.widget.TextView[contains(@text,'Login')] | //XCUIElementTypeStaticText[contains(@name,'Login')]
//                    """;
//        }
//
//        StringBuilder listBuilder = new StringBuilder();
//        for (int i = 0; i < damagedLocators.size(); i++) {
//            listBuilder.append(i + 1).append(". ").append(damagedLocators.get(i)).append("\n");
//        }
//
//        String rules = """
//                - For each damaged locator, find the closest match in the XML snapshot.
//                - If an exact match exists, use it. Otherwise, generate corrected locator(s) using flexible attribute matches (contains(), starts-with(), normalize-space()).
//                - Prefer attributes based on platform:
//                  * iOS → @name, @label, @value
//                  * Android → @resource-id, @content-desc, @text
//                - Preserve relationships (sibling, parent, ancestor) if present.
//                - Output ONLY the corrected locator(s), one per line, no explanations.
//                - Handle numbers in attribute values and node names correctly.
//                - Be case-insensitive when matching attribute values and node names.
//                - Support mixed Android and iOS locators in the same input.
//                - Support locators like //input[@data-qa='email'] or other custom attributes.
//                - If a locator contains a word in the middle, you may remove it if needed to find a match.
//                - You may change letters at the start or in the middle of attribute values to find the closest match.
//                - If a locator contains an index (e.g., [2]), keep the index unchanged in the output.
//                - Normalize spaces, tabs, or newlines in attributes before matching.
//                - If multiple elements match closely, return up to 3 best suggestions (one per line).
//                - If the tag name changes slightly (e.g., Button → TextView), still return the closest valid locator.
//                """;
//
//        String learningHint = String.format("""
//                MODEL LEARNING CONTEXT:
//                - You are analyzing damaged locators for platform: %s.
//                - Learn from the XML snapshot structure below: detect how attributes are used in this app (naming conventions, casing, id patterns, etc.).
//                - Use this snapshot as a reference to adapt and correct locators intelligently.
//                - Ensure the final suggestions align with the current app’s platform structure.
//                """, platform);
//
//        return String.format(
//                "ROLE: You are an advanced automation test assistant specializing in self-healing mobile locators.\n" +
//                        "PLATFORM: %s\n" +
//                        "%s\n" +
//                        "TASK: Given a list of damaged locators (XPath, By.id, sibling axes, name, etc.) and the current XML snapshot, search inside the XML and return the most similar/corrected locator(s).\n\n" +
//                        "INPUT:\n" +
//                        "1. Damaged Locators:\n%s\n\n" +
//                        "2. Current XML Snapshot:\n'''\n%s\n'''\n\n" +
//                        "RULES:\n%s\n" +
//                        "EXAMPLE:\n%s",
//                platform,
//                learningHint,
//                listBuilder.toString().trim(),
//                xmlSnapshot,
//                rules,
//                attributeHint,
//                platformExamples
//        );
//    }


    public List<String> extractXPathFromAIResponse(String response) {
        String cleanedResponse = response.replaceAll("^```[a-zA-Z]*", "")
                .replaceAll("```$", "")
                .trim();
        List<String> locators = new java.util.ArrayList<>();
        for (String line : cleanedResponse.split("\\r?\\n")) {
            String locatorLine = line.trim();
            if (!locatorLine.isEmpty() && locatorLine.startsWith("//")) {
                // Take only the first XPath if multiple are joined by '|'
                String firstLocator = locatorLine.split("\\|")[0].trim();
                if (!firstLocator.isEmpty()) {
                    locators.add(firstLocator);
                    Log.info("Extracted Locator: " + firstLocator);
                }
            }
        }
        if (locators.isEmpty()) {
            Log.info("AI response doesn't contain valid locators: " + response);
        }
        return locators;
    }


    /**
     * Example runner: auto-load latest XML snapshot with dummy damaged list
     */
    public List<String> autoAnalyzeAndFix(List<String> damagedXPaths) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        String xmlSnapshotPath = "xml_snapshots/snapshot_" + timestamp + ".xml";
        return analyzeAndGenerateXPaths(damagedXPaths, xmlSnapshotPath);
    }

}
