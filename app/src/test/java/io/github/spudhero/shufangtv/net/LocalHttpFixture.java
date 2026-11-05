package io.github.spudhero.shufangtv.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A tiny loopback-only HTTP fixture: no external NAS, account, book or SDK-dependent server. */
public final class LocalHttpFixture implements AutoCloseable {
  public interface Handler {
    Reply handle(Incoming request) throws IOException;
  }

  public static final class Incoming {
    public final String method, path;
    public final Map<String, String> headers;

    Incoming(String method, String path, Map<String, String> headers) {
      this.method = method;
      this.path = path;
      this.headers = headers;
    }
  }

  public static final class Reply {
    final int status;
    final byte[] bytes;
    final Map<String, String> headers = new LinkedHashMap<>();

    public Reply(int status, byte[] bytes) {
      this.status = status;
      this.bytes = bytes;
    }

    public Reply header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    static Reply text(int status, String text) {
      return new Reply(status, text.getBytes(StandardCharsets.UTF_8));
    }
  }

  final List<Incoming> requests = new CopyOnWriteArrayList<>();
  final ServerSocket server;
  final ExecutorService workers =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "shufang-http-fixture");
            t.setDaemon(true);
            return t;
          });
  final Thread acceptor;
  public volatile Handler handler;

  public LocalHttpFixture(Handler handler) throws IOException {
    this.handler = handler;
    server = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
    acceptor =
        new Thread(
            () -> {
              while (!server.isClosed()) {
                try {
                  Socket socket = server.accept();
                  workers.submit(() -> serve(socket));
                } catch (SocketException closed) {
                  if (!server.isClosed()) throw new AssertionError(closed);
                } catch (IOException failure) {
                  if (!server.isClosed()) throw new AssertionError(failure);
                }
              }
            },
            "shufang-http-accept");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public String origin() {
    return "http://127.0.0.1:" + server.getLocalPort();
  }

  private void serve(Socket socket) {
    try (Socket owned = socket) {
      socket.setSoTimeout(5000);
      BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
      String[] start = line(input).split(" ", 3);
      if (start.length != 3) return;
      Map<String, String> headers = new LinkedHashMap<>();
      for (String line; !(line = line(input)).isEmpty(); ) {
        int colon = line.indexOf(':');
        if (colon > 0)
          headers.put(
              line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
      }
      int bodyLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
      if (bodyLength > 8192) throw new IOException("Oversized fixture request");
      for (int n = 0; n < bodyLength; n++)
        if (input.read() == -1) throw new IOException("Short fixture request");
      Incoming incoming = new Incoming(start[0], start[1], Collections.unmodifiableMap(headers));
      requests.add(incoming);
      Reply reply = handler.handle(incoming);
      BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
      StringBuilder response =
          new StringBuilder("HTTP/1.1 " + reply.status + " Fixture\r\nConnection: close\r\n");
      if (!reply.headers.containsKey("Content-Length"))
        reply.header("Content-Length", Integer.toString(reply.bytes.length));
      for (Map.Entry<String, String> entry : reply.headers.entrySet())
        response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      response.append("\r\n");
      output.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
      if (!incoming.method.equals("HEAD")) output.write(reply.bytes);
      output.flush();
    } catch (IOException ignored) {
      // Closing a rejected Range response deliberately terminates this connection.
    }
  }

  private static String line(BufferedInputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int c; (c = input.read()) != -1; ) {
      if (c == '\n') return bytes.toString(StandardCharsets.ISO_8859_1.name()).replace("\r", "");
      if (bytes.size() == 8192) throw new IOException("Fixture header too long");
      bytes.write(c);
    }
    throw new IOException("Fixture connection ended");
  }

  @Override
  public void close() throws IOException {
    server.close();
    workers.shutdownNow();
    try {
      acceptor.join(1000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
