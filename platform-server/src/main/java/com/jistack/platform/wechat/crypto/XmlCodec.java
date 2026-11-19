package com.jistack.platform.wechat.crypto;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信推送的 XML 都是一层扁平结构，这里只做安全（禁 DTD/外部实体）的首层解析与拼装。
 */
public final class XmlCodec {

    private XmlCodec() {
    }

    public static Map<String, String> parseFlat(String xml) {
        Map<String, String> result = new LinkedHashMap<>();
        if (xml == null || xml.isBlank()) {
            return result;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    result.put(node.getNodeName(), node.getTextContent());
                }
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("XML 解析失败: " + ex.getMessage(), ex);
        }
        return result;
    }

    public static String build(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("<xml>");
        fields.forEach((k, v) -> sb.append('<').append(k).append("><![CDATA[")
                .append(v == null ? "" : v).append("]]></").append(k).append('>'));
        return sb.append("</xml>").toString();
    }

    /** 取 Encrypt 节点内容（安全模式推送的密文所在字段）。 */
    public static String extractEncrypt(String xml) {
        return parseFlat(xml).get("Encrypt");
    }
}
