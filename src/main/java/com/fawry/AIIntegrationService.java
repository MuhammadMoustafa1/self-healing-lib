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
                Log.info("Failed to extract valid locators from AI response");
                return null;
            }

            Log.info("Generated locators from AI analysis: " + xpaths);
            return xpaths;

        } catch (Exception e) {
            Log.error("AI analysis failed", e);
            return null;
        }
    }

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
     * Prompt builder that now prefers By.id() locators when possible.
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
                    By.id("com.example:id/loginBtn")
                    """;
        } else {
            platform = "Android and iOS";
            platformExamples = """
                    Input:
                    By.id("com.example:id/loginBtn") | By.className("XCUIElementTypeButton")
                    Output:
                    By.id("com.example:id/loginBtn")
                    //XCUIElementTypeButton[@name='loginBtn']
                    """;
        }

        StringBuilder listBuilder = new StringBuilder();
        for (int i = 0; i < damagedLocators.size(); i++) {
            listBuilder.append(i + 1).append(". ").append(damagedLocators.get(i)).append("\n");
        }

        String rules = """
                - For each damaged locator, return ONLY one corrected locator (one per line, no explanations).
                - This new type for pos device if damage locator contain this prefer return this type returning a native locator (e.g., By.id("com.fawry.retailer:id/...")) if the exact resource-id exists in the XML.
                - Only use XPath (//android.widget.* or //XCUIElementType*) if a matching id or accessibility attribute is not found.
                - Treat any resource-id starting with "com.fawry.retailer:id/" as a valid direct locator and return it using By.id("com.fawry.retailer:id/...").
                - Do NOT combine multiple locators with '|'.
                - For Android: Prefer resource-id → By.id("package:id/...").
                - For iOS: Prefer @name or @label → //XCUIElementType*[@name='...'].
                - Use contains() or starts-with() only when exact match is not available.
                - Be case-insensitive when matching attributes.
                - Maintain index positions (e.g., [1], [2]) if present.
                - If tag names differ slightly, still return the closest valid match.
                """;

        String learningHint = String.format("""
                MODEL LEARNING CONTEXT:
                - You are analyzing damaged locators for platform: %s.
                - Learn from the XML snapshot structure below: detect how attributes are used in this app (naming conventions, casing, id patterns, etc.).
                - Use this snapshot as a reference to adapt and correct locators intelligently.
                - Ensure final suggestions align with the platform’s locator strategy (By.id preferred for Android).
                """, platform);

        return String.format(
                "ROLE: You are an advanced automation test assistant specializing in self-healing mobile locators.\n" +
                        "PLATFORM: %s\n" +
                        "%s\n" +
                        "TASK: Given a list of damaged locators (By.id, By.className, XPath, etc.) and the current XML snapshot, search inside the XML and return the most similar/corrected locator(s).\n\n" +
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

    /**
     * Extract both By.id() and XPath style locators from the AI response.
     */
    public List<String> extractXPathFromAIResponse(String response) {
        String cleanedResponse = response.replaceAll("^```[a-zA-Z]*", "")
                .replaceAll("```$", "")
                .trim();

        List<String> locators = new java.util.ArrayList<>();
        for (String line : cleanedResponse.split("\\r?\\n")) {
            String locatorLine = line.trim();
            if (!locatorLine.isEmpty() &&
                    (locatorLine.startsWith("//") || locatorLine.startsWith("By."))) {

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
     * Example runner.
     */
    public List<String> autoAnalyzeAndFix(List<String> damagedXPaths) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        String xmlSnapshotPath = "xml_snapshots/snapshot_" + timestamp + ".xml";
        return analyzeAndGenerateXPaths(damagedXPaths, xmlSnapshotPath);
    }
}
