package io.github.spudhero.shufangtv.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.SAXParserFactory;
import okhttp3.HttpUrl;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

final class DavListing {
  private DavListing() {}

  static List<RemoteEntry> parse(byte[] xml, WebDavEndpoint endpoint, HttpUrl directory)
      throws IOException {
    String probe =
        new String(xml, java.nio.charset.StandardCharsets.UTF_8)
            .toUpperCase(Locale.ROOT)
            .replace("\u0000", "");
    if (probe.contains("<!DOCTYPE") || probe.contains("<!ENTITY"))
      throw NetworkPaths.invalid("WebDAV 目录包含不支持的 XML 声明。");
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      XMLReader reader = factory.newSAXParser().getXMLReader();
      reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
      reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      Handler handler = new Handler(endpoint, directory);
      reader.setContentHandler(handler);
      reader.setErrorHandler(handler);
      reader.setEntityResolver(
          (publicId, systemId) -> {
            throw new SAXException("External entities are not supported");
          });
      reader.parse(new InputSource(new ByteArrayInputStream(xml)));
      if (!handler.multistatus) throw NetworkPaths.invalid("服务器未返回标准 WebDAV 目录。");
      List<RemoteEntry> result = new ArrayList<>(handler.entries.values());
      NetworkPaths.sort(result);
      return result;
    } catch (NetworkFailure failure) {
      throw failure;
    } catch (Exception failure) {
      throw new NetworkFailure("无法解析 WebDAV 目录，请检查服务地址。", false, failure);
    }
  }

  private static final class Handler extends DefaultHandler {
    final WebDavEndpoint endpoint;
    final HttpUrl directory;
    final Map<String, RemoteEntry> entries = new LinkedHashMap<>();
    boolean multistatus;
    int depth, responseDepth, propstatDepth, count;
    String href, captureName;
    StringBuilder capture;
    Properties properties, accepted;

    Handler(WebDavEndpoint endpoint, HttpUrl directory) {
      this.endpoint = endpoint;
      this.directory = directory;
    }

    @Override
    public void startElement(
        String namespace, String local, String qualified, Attributes attributes)
        throws SAXException {
      depth++;
      if (depth > 32) throw new SAXException("XML nesting limit");
      if (!"DAV:".equals(namespace)) return;
      if (depth == 1) {
        if (!"multistatus".equals(local)) throw new SAXException("Not a multistatus");
        multistatus = true;
      }
      if ("response".equals(local)) {
        if (++count > NetworkPaths.MAX_ENTRIES + 1) throw new SAXException("Too many entries");
        responseDepth = depth;
        href = null;
        accepted = null;
      } else if (responseDepth > 0 && "propstat".equals(local)) {
        propstatDepth = depth;
        properties = new Properties();
      } else if (responseDepth > 0 && "href".equals(local) && depth == responseDepth + 1) {
        captureName = local;
        capture = new StringBuilder();
      } else if (propstatDepth > 0
          && ("status".equals(local) || "getcontentlength".equals(local))) {
        captureName = local;
        capture = new StringBuilder();
      } else if (propstatDepth > 0 && "collection".equals(local)) {
        properties.directory = true;
      }
    }

    @Override
    public void characters(char[] chars, int start, int length) throws SAXException {
      if (capture == null) return;
      if (capture.length() + length > 8192) throw new SAXException("XML text limit");
      capture.append(chars, start, length);
    }

    @Override
    public void endElement(String namespace, String local, String qualified) {
      if ("DAV:".equals(namespace)) {
        if (capture != null && local.equals(captureName)) {
          String value = capture.toString().trim();
          if ("href".equals(local)) href = value;
          else if (properties != null && "status".equals(local))
            properties.success = value.matches("HTTP/\\d(?:\\.\\d)? 2\\d\\d(?: .*)?");
          else if (properties != null && "getcontentlength".equals(local)) {
            try {
              properties.size = Long.parseLong(value);
              if (properties.size < 0) properties.size = -1;
            } catch (NumberFormatException ignored) {
              properties.size = -1;
            }
          }
          capture = null;
          captureName = null;
        }
        if ("propstat".equals(local) && depth == propstatDepth) {
          if (properties != null && properties.success) {
            if (accepted == null) accepted = properties;
            else {
              accepted.directory |= properties.directory;
              if (properties.size >= 0) accepted.size = properties.size;
            }
          }
          properties = null;
          propstatDepth = 0;
        } else if ("response".equals(local) && depth == responseDepth) {
          if (accepted != null && href != null) {
            String path = endpoint.childPath(directory, href);
            if (path != null) {
              String name = path.substring(path.lastIndexOf('/') + 1);
              entries.putIfAbsent(
                  path,
                  new RemoteEntry(
                      name, path, accepted.directory, accepted.directory ? -1 : accepted.size));
            }
          }
          responseDepth = 0;
        }
      }
      depth--;
    }
  }

  private static final class Properties {
    boolean success, directory;
    long size = -1;
  }
}
