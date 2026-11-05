package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** A persisted local/USB document grant; does not duplicate the book in TV storage. */
public final class DocumentRemoteFile implements RemoteFile {
  private final ParcelFileDescriptor descriptor;
  private final FileInputStream stream;
  private final FileChannel channel;
  private final long length;

  public DocumentRemoteFile(Context context, String uri) throws IOException {
    descriptor = context.getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
    if (descriptor == null) throw new IOException("无法访问本地文件，请重新授权");
    stream = new FileInputStream(descriptor.getFileDescriptor());
    channel = stream.getChannel();
    try {
      length = channel.size();
      channel.position(0);
    } catch (IOException e) {
      close();
      throw new IOException("此文件提供方不支持随机读取，请改用本地或 USB 文件", e);
    }
  }

  @Override
  public long length() {
    return length;
  }

  @Override
  public synchronized int read(long p, byte[] b, int o, int n) throws IOException {
    return channel.read(ByteBuffer.wrap(b, o, n), p);
  }

  @Override
  public void close() throws IOException {
    try {
      stream.close();
    } finally {
      descriptor.close();
    }
  }
}
