package com.oppo.cloud.meta.utils;


import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class XMLFileTool {

    public static class XMLFileReader {
        public static Document loadXMLFile(File file) throws DocumentException {
            SAXReader reader = new SAXReader();
            return reader.read(file);
        }

        public static Document loadXMLFile(String xmlContent) throws DocumentException {
            SAXReader reader = new SAXReader();
            StringReader sr = new StringReader(xmlContent);
            return reader.read(sr);
        }

    }

    public static class XMLFileWriter {
        public static void writeXMLToFile(Document document, File file) throws IOException {
            OutputFormat format = OutputFormat.createPrettyPrint();
            XMLWriter writer = new XMLWriter(Files.newOutputStream(file.toPath()), format);
            writer.write(document);
            writer.close();
        }
    }

    public static Properties convertXMLToProperties(String xmlFilePath) throws Exception {
        SAXReader reader = new SAXReader();
        Document document = reader.read(Files.newInputStream(Paths.get(xmlFilePath)));
        Properties properties = new Properties();

        traverseXML(document.getRootElement(), "", properties);

        return properties;
    }

    private static void traverseXML(Element element, String currentPath, Properties properties) {
        String elementName = element.getName();
        String elementText = element.getTextTrim();
        String newPath = currentPath.isEmpty() ? elementName : currentPath + "." + elementName;

        if (!elementText.isEmpty()) {
            properties.setProperty(newPath, elementText);
        }

        for (Element child : element.elements()) {
            traverseXML(child, newPath, properties);
        }
    }

}
