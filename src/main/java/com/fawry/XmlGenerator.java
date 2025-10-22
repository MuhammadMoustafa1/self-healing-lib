package com.fawry;

import com.fawry.utilities.Log;
import io.appium.java_client.AppiumDriver;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class XmlGenerator {
    public AppiumDriver driver;
    private static final String XML_OUTPUT_DIR = "xml_snapshots";
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static boolean filesCleaned = false;

    public void setDriver(AppiumDriver driver) {
        this.driver = driver;
        this.initializeXmlDirectory();
    }

    private void initializeXmlDirectory() {
        try {
            Files.createDirectories(Paths.get(XML_OUTPUT_DIR));
            if (!filesCleaned) {
                this.cleanUpPreviousFiles();
                filesCleaned = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to initialize XML output directory:");
            e.printStackTrace();
        }
    }

    private void cleanUpPreviousFiles() throws IOException {
        Files.walk(Paths.get(XML_OUTPUT_DIR))
                .filter((x$0) -> Files.isRegularFile(x$0, new LinkOption[0]))
                .filter((path) -> path.toString().endsWith(".xml") || path.toString().endsWith(".txt"))
                .forEach((path) -> {
                    try {
                        Files.delete(path);
                    } catch (IOException var2) {
                        System.err.println("Failed to delete file: " + String.valueOf(path));
                    }
                });
    }

    public void generatePageXML() throws Exception {
        if (this.driver == null) {
            throw new IllegalStateException("Driver has not been set. Call setDriver() first.");
        } else {
            String pageSource = this.driver.getPageSource();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(pageSource)));
            List<String> xpaths = this.generateAllXPaths(document);
            String enhancedXml = this.createEnhancedXml(document, xpaths);
            this.saveXmlToFile(enhancedXml, xpaths);
        }
    }

    private List<String> generateAllXPaths(Document document) {
        List<String> xpaths = new ArrayList<>();
        NodeList allElements = document.getElementsByTagName("*");
        for (int i = 0; i < allElements.getLength(); ++i) {
            Element element = (Element) allElements.item(i);
            String xpath = this.generateXPath(element);
            if (!xpath.isEmpty()) {
                xpaths.add(xpath);
            }
        }
        return xpaths;
    }

    private String generateXPath(Element element) {
        StringBuilder xpath = new StringBuilder();
        Deque<String> hierarchy = new ArrayDeque<>();
        for (Element current = element; current != null; current = current.getParentNode() instanceof Element ? (Element) current.getParentNode() : null) {
            String tagName = current.getTagName();
            String id = current.getAttribute("resource-id");
            if (!id.isEmpty()) {
                hierarchy.addFirst(tagName + "[@resource-id='" + id + "']");
            } else {
                hierarchy.addFirst(tagName);
            }
        }
        for (String level : hierarchy) {
            xpath.append("/").append(level);
        }
        return xpath.toString();
    }

    private String createEnhancedXml(Document document, List<String> xpaths) throws Exception {
        int total = xpaths.size();
        String xpathComment = "<!-- \nGenerated XML with all available XPaths\nTotal XPaths found: " + total + "\nSample XPaths:\n" + String.join("\n", xpaths.subList(0, Math.min(10, xpaths.size()))) + "\n-->\n";
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return xpathComment + writer.toString();
    }

    private void saveXmlToFile(String xmlContent, List<String> xpaths) throws IOException {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMAT);
        Path xmlPath = Paths.get(XML_OUTPUT_DIR, "snapshot_" + timestamp + ".xml");
        Path xpathsPath = Paths.get(XML_OUTPUT_DIR, "xpaths_" + timestamp + ".txt");

        try (BufferedWriter writer = Files.newBufferedWriter(xmlPath)) {
            writer.write(xmlContent);
            Log.info("Saved XML snapshot to: " + String.valueOf(xmlPath.toAbsolutePath()));
        } catch (IOException e) {
            Log.error("Failed to save XML snapshot: " + String.valueOf(xmlPath), e);
            throw e;
        }

        try (BufferedWriter xpathWriter = Files.newBufferedWriter(xpathsPath)) {
            xpathWriter.write("All XPaths found in XML (" + xpaths.size() + " total):\n");
            xpathWriter.write(String.join("\n", xpaths));
            Log.info("Saved XPaths to: " + String.valueOf(xpathsPath.toAbsolutePath()));
        } catch (IOException e) {
            Log.error("Failed to save XPaths file: " + String.valueOf(xpathsPath), e);
            throw e;
        }
    }

    public void clearXmlSnapshotsDirectory() {
        try {
            Files.walk(Paths.get(XML_OUTPUT_DIR))
                    .filter((x$0) -> Files.isRegularFile(x$0, new LinkOption[0]))
                    .forEach((path) -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            Log.error("Failed to delete file: " + String.valueOf(path), e);
                        }
                    });
            Log.info("Cleared xml_snapshots directory.");
        } catch (IOException e) {
            Log.error("Failed to clear xml_snapshots directory.", e);
        }
    }
}
