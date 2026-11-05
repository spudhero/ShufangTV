package io.github.spudhero.shufangtv.net;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class SmbConnector implements LibraryConnector {
  private static final SmbConfig SETTINGS =
      SmbConfig.builder()
          .withTimeout(15, TimeUnit.SECONDS)
          .withSoTimeout(20, TimeUnit.SECONDS)
          .withReadBufferSize(NetworkPaths.MAX_READ)
          .withDfsEnabled(false)
          .build();

  @Override
  public List<RemoteEntry> list(SourceConfig config, String path) throws IOException {
    try (Handle handle = connect(config);
        Directory directory =
            handle.share.openDirectory(
                smbPath(config, path),
                EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                EnumSet.allOf(SMB2ShareAccess.class),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE))) {
      List<RemoteEntry> entries = new ArrayList<>();
      for (FileIdBothDirectoryInformation file : directory) {
        NetworkRetry.checkInterrupted();
        String name = file.getFileName();
        if (name.equals(".") || name.equals("..")) continue;
        if (!NetworkPaths.segment(name)
            || (file.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue())
                != 0) continue;
        if (entries.size() >= NetworkPaths.MAX_ENTRIES)
          throw NetworkPaths.invalid("目录过大，请缩小书库根目录后重试。");
        boolean folder =
            (file.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
        entries.add(
            new RemoteEntry(
                name, NetworkPaths.join(path, name), folder, folder ? -1 : file.getEndOfFile()));
      }
      NetworkPaths.sort(entries);
      return entries;
    } catch (IOException failure) {
      throw translate(failure);
    } catch (RuntimeException failure) {
      throw translate(failure);
    }
  }

  @Override
  public RemoteFile open(SourceConfig config, String path) throws IOException {
    return new SmbRemoteFile(config, path);
  }

  @Override
  public void download(
      SourceConfig config, String path, File target, NetworkLibrary.Progress progress)
      throws IOException {
    try (Handle handle = openFile(config, path);
        InputStream input = handle.file.getInputStream()) {
      AtomicDownload.copy(input, handle.size, target, progress);
    } catch (IOException failure) {
      throw translate(failure);
    } catch (RuntimeException failure) {
      throw translate(failure);
    }
  }

  private static Handle connect(SourceConfig config) throws IOException {
    Handle handle = new Handle();
    try {
      NetworkRetry.checkInterrupted();
      handle.client = new SMBClient(SETTINGS);
      handle.connection = handle.client.connect(config.host, config.port);
      String username = config.username, domain = config.domain;
      int separator = username.indexOf('\\');
      if (separator > 0 && domain.isEmpty()) {
        domain = username.substring(0, separator);
        username = username.substring(separator + 1);
      }
      AuthenticationContext auth =
          username.isEmpty()
              ? AuthenticationContext.anonymous()
              : new AuthenticationContext(username, config.password.toCharArray(), domain);
      handle.session = handle.connection.authenticate(auth);
      Share share = handle.session.connectShare(config.share);
      if (!(share instanceof DiskShare)) {
        share.close();
        throw NetworkPaths.invalid("此 SMB 共享不是文件目录。");
      }
      handle.share = (DiskShare) share;
      return handle;
    } catch (IOException | RuntimeException failure) {
      handle.abort();
      if (failure instanceof IOException) throw translate((IOException) failure);
      throw translate((RuntimeException) failure);
    }
  }

  private static Handle openFile(SourceConfig config, String path) throws IOException {
    Handle handle = connect(config);
    try {
      handle.file =
          handle.share.openFile(
              smbPath(config, path),
              EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
              EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
              EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
              SMB2CreateDisposition.FILE_OPEN,
              EnumSet.of(
                  SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_RANDOM_ACCESS));
      FileAllInformation info = handle.file.getFileInformation();
      if (info.getStandardInformation().isDirectory()
          || (info.getBasicInformation().getFileAttributes()
                  & FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.getValue())
              != 0) {
        throw NetworkPaths.invalid("请选择普通书籍文件，当前版本不读取 SMB 链接。");
      }
      handle.size = info.getStandardInformation().getEndOfFile();
      handle.modified = info.getBasicInformation().getLastWriteTime().getWindowsTimeStamp();
      if (handle.size < 0) throw NetworkPaths.invalid("服务器返回的书籍长度不正确。");
      return handle;
    } catch (IOException | RuntimeException failure) {
      handle.abort();
      if (failure instanceof IOException) throw translate((IOException) failure);
      throw translate((RuntimeException) failure);
    }
  }

  private static String smbPath(SourceConfig config, String relative) throws IOException {
    String path = NetworkPaths.join(config.root, relative);
    for (String segment : path.split("/")) {
      if (segment.indexOf(':') >= 0 || segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0)
        throw NetworkPaths.invalid("SMB 路径包含不支持的字符。");
    }
    return path.replace('/', '\\');
  }

  static NetworkFailure translate(RuntimeException failure) {
    if (failure instanceof SMBApiException) {
      long status = ((SMBApiException) failure).getStatusCode();
      if (status == 0xc000006dL
          || status == 0xc0000022L
          || status == 0xc000006eL
          || status == 0xc0000071L
          || status == 0xc0000072L
          || status == 0xc0000234L) {
        return new NetworkFailure("SMB 账号、密码或访问权限不正确。", false, failure);
      }
      if (status == 0xc00000ccL
          || status == 0xc0000034L
          || status == 0xc000003aL
          || status == 0xc0000103L) {
        return new NetworkFailure("找不到 SMB 共享、目录或书籍，请检查路径。", false, failure);
      }
      boolean transientStatus =
          status == 0xc000020cL
              || status == 0xc000020dL
              || status == 0xc00000c9L
              || status == 0xc00000b5L
              || status == 0xc000035cL
              || status == 0xc00000b0L;
      return new NetworkFailure(
          transientStatus ? "SMB 连接中断，正在重新连接。" : "SMB 服务拒绝了读取请求。", transientStatus, failure);
    }
    Throwable cause = failure.getCause();
    while (cause != null) {
      if (cause instanceof SMBApiException) return translate((SMBApiException) cause);
      if (cause instanceof IOException) {
        NetworkFailure mapped = translate((IOException) cause);
        return new NetworkFailure(mapped.getMessage(), mapped.retryable, failure);
      }
      cause = cause.getCause();
    }
    return new NetworkFailure("SMB 连接失败，请检查服务器和协议设置。", false, failure);
  }

  static NetworkFailure translate(IOException failure) {
    if (failure instanceof NetworkFailure) return (NetworkFailure) failure;
    boolean transientFailure =
        NetworkRetry.retryable(failure) || failure instanceof TransportException;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SMBApiException) return translate((SMBApiException) cause);
      if (cause instanceof javax.net.ssl.SSLException
          || (cause instanceof java.io.InterruptedIOException
              && !(cause instanceof java.net.SocketTimeoutException))) {
        return new NetworkFailure("已取消 SMB 读取或安全连接失败。", false, failure);
      }
      if (cause instanceof java.net.UnknownHostException)
        return new NetworkFailure("找不到 SMB 服务器，请检查地址。", false, failure);
      if (cause instanceof IOException && NetworkRetry.retryable((IOException) cause))
        transientFailure = true;
    }
    return new NetworkFailure(
        transientFailure ? "SMB 网络连接中断，请重试。" : "SMB 读取失败，请检查服务器和本机存储。", transientFailure, failure);
  }

  private static final class SmbRemoteFile implements RemoteFile {
    final SourceConfig config;
    final String path;
    final long size, modified;
    private volatile boolean closed;
    private volatile Handle handle;

    SmbRemoteFile(SourceConfig config, String path) throws IOException {
      this.config = config;
      this.path = path;
      handle = openFile(config, path);
      size = handle.size;
      modified = handle.modified;
    }

    @Override
    public long length() {
      return size;
    }

    @Override
    public synchronized int read(long position, byte[] buffer, int offset, int length)
        throws IOException {
      if (closed) throw NetworkPaths.invalid("在线书籍已经关闭。");
      int wanted = NetworkPaths.readLength(position, size, buffer, offset, length);
      if (wanted <= 0) return wanted;
      return NetworkRetry.run(
          () -> {
            if (closed) throw NetworkPaths.invalid("在线书籍已经关闭。");
            try {
              if (handle == null) {
                Handle next = openFile(config, path);
                if (next.size != size || next.modified != modified) {
                  next.abort();
                  throw NetworkPaths.invalid("远程书籍已改变，请重新打开。");
                }
                handle = next;
                if (closed) {
                  next.abort();
                  throw NetworkPaths.invalid("在线书籍已经关闭。");
                }
              }
              int count = handle.file.read(buffer, position, offset, wanted);
              if (count <= 0) throw new EOFException("在线书籍分段尚未读取完整。");
              return count;
            } catch (RuntimeException failure) {
              Handle previous = handle;
              handle = null;
              if (previous != null) previous.abort();
              throw translate(failure);
            } catch (IOException failure) {
              Handle previous = handle;
              handle = null;
              if (previous != null) previous.abort();
              throw translate(failure);
            }
          });
    }

    @Override
    public void close() {
      closed = true;
      Handle previous = handle;
      handle = null;
      if (previous != null) previous.abort();
    }
  }

  private static final class Handle implements AutoCloseable {
    SMBClient client;
    Connection connection;
    Session session;
    DiskShare share;
    com.hierynomus.smbj.share.File file;
    long size, modified;

    @Override
    public void close() throws IOException {
      IOException first = null;
      AutoCloseable[] owned = {file, share, session, connection, client};
      for (AutoCloseable resource : owned) {
        if (resource == null) continue;
        try {
          resource.close();
        } catch (Exception failure) {
          if (first == null) first = new IOException("关闭 SMB 连接失败。", failure);
        }
      }
      if (first != null) throw first;
    }

    /** Drop the transport first so a failed session cannot block cleanup and the fresh retry. */
    void abort() {
      if (connection != null)
        try {
          connection.close(true);
        } catch (Exception ignored) {
        }
      if (client != null)
        try {
          client.close();
        } catch (Exception ignored) {
        }
    }
  }
}
