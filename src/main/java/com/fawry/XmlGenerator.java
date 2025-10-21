package com.fawry;

import io.appium.java_client.AppiumDriver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class XmlGenerator{
    public AppiumDriver driver;
    private static final String XML_OUTPUT_DIR = "xml_snapshots";
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static boolean filesCleaned = false;

    public void setDriver(AppiumDriver driver) {
        this.driver = driver;
        initializeXmlDirectory();
    }

    private void initializeXmlDirectory() {
        try {
            Files.createDirectories(Paths.get(XML_OUTPUT_DIR));
            if (!filesCleaned) {
                cleanUpPreviousFiles();
                filesCleaned = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize XML output directory:");
            e.printStackTrace();
        }
    }

    private void cleanUpPreviousFiles() throws IOException {
        Files.walk(Paths.get(XML_OUTPUT_DIR))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".xml") || path.toString().endsWith(".txt"))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Failed to delete file: " + path);
                    }
                });
    }

    public void generatePageXML() throws Exception {
        if (driver == null) {
            throw new IllegalStateException("Driver has not been set. Call setDriver() first.");
        }

        String pageSource = driver.getPageSource();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new InputSource(new StringReader(pageSource)));

        List<String> xpaths = generateAllXPaths(document);
        String enhancedXml = createEnhancedXml(document, xpaths);
        saveXmlToFile(enhancedXml, xpaths);
    }

    private List<String> generateAllXPaths(Document document) {
        List<String> xpaths = new ArrayList<>();
        NodeList allElements = document.getElementsByTagName("*");

        for (int i = 0; i < allElements.getLength(); i++) {
            Element element = (Element) allElements.item(i);
            String xpath = generateXPath(element);
            if (!xpath.isEmpty()) {
                xpaths.add(xpath);
            }
        }
        return xpaths;
    }

    private String generateXPath(Element element) {
        StringBuilder xpath = new StringBuilder();
        Deque<String> hierarchy = new ArrayDeque<>();

        Element current = element;
        while (current != null) {
            String tagName = current.getTagName();
            String id = current.getAttribute("resource-id");

            if (!id.isEmpty()) {
                hierarchy.addFirst(tagName + "[@resource-id='" + id + "']");
            } else {
                hierarchy.addFirst(tagName);
            }
            current = current.getParentNode() instanceof Element ?
                    (Element) current.getParentNode() : null;
        }

        for (String level : hierarchy) {
            xpath.append("/").append(level);
        }

        return xpath.toString();
    }

    private String createEnhancedXml(Document document, List<String> xpaths) throws Exception {
        String xpathComment = "<!-- \n" +
                "Generated XML with all available XPaths\n" +
                "Total XPaths found: " + xpaths.size() + "\n" +
                "Sample XPaths:\n" +
                String.join("\n", xpaths.subList(0, Math.min(10, xpaths.size()))) +
                "\n-->\n";

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));

        return xpathComment + writer.toString();
    }

    private void saveXmlToFile(String xmlContent, List<String> xpaths) throws IOException {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        String filename = XML_OUTPUT_DIR + "/snapshot_" + timestamp + ".xml";
        Path filePath = Paths.get(filename);

        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            writer.write(xmlContent);
            System.out.println("Saved XML snapshot to: " + filePath.toAbsolutePath());

            String xpathFilename = XML_OUTPUT_DIR + "/xpaths_" + timestamp + ".txt";
            Path xpathFilePath = Paths.get(xpathFilename);
            try (BufferedWriter xpathWriter = Files.newBufferedWriter(xpathFilePath)) {
                xpathWriter.write("All XPaths found in XML (" + xpaths.size() + " total):\n");
                xpathWriter.write(String.join("\n", xpaths));
                System.out.println("Saved XPaths to: " + xpathFilePath.toAbsolutePath());
            }
        }
    }
}
