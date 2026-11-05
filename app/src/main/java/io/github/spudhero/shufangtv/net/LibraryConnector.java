package io.github.spudhero.shufangtv.net;

import java.io.File;
import java.io.IOException;
import java.util.List;

interface LibraryConnector {
  List<RemoteEntry> list(SourceConfig config, String path) throws IOException;

  RemoteFile open(SourceConfig config, String path) throws IOException;

  void download(SourceConfig config, String path, File target, NetworkLibrary.Progress progress)
      throws IOException;
}
