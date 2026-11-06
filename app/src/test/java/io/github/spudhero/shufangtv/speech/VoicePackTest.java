package io.github.spudhero.shufangtv.speech;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class VoicePackTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private static final byte[] DATA = "original voice fixture".getBytes(StandardCharsets.UTF_8);

  private static byte[] goodArchive() throws IOException {
    try {
      return archive("model.onnx", DATA);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private static String hash(byte[] data) throws Exception {
    StringBuilder hash = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
      hash.append(String.format("%02x", b & 255));
    return hash.toString();
  }

  private static byte[] archive(String path, byte[] data) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar =
        new TarArchiveOutputStream(new BZip2CompressorOutputStream(bytes))) {
      TarArchiveEntry root = new TarArchiveEntry("vits-icefall-zh-aishell3/");
      root.setModTime(0);
      tar.putArchiveEntry(root);
      tar.closeArchiveEntry();
      TarArchiveEntry file = new TarArchiveEntry("vits-icefall-zh-aishell3/" + path);
      file.setModTime(0);
      file.setSize(data.length);
      tar.putArchiveEntry(file);
      tar.write(data);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private static String manifest(String path) throws Exception {
    return manifest(path, archive(path, DATA));
  }

  private static String manifest(String path, byte[] archive) throws Exception {
    return new JSONObject()
        .put("id", "test-voice")
        .put("name", "Test")
        .put(
            "archiveUrl",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2")
        .put("archiveRoot", "vits-icefall-zh-aishell3/")
        .put("archiveBytes", archive.length)
        .put("archiveSha256", hash(archive))
        .put("installedBytes", DATA.length)
        .put(
            "files",
            new JSONObject()
                .put(path, new JSONObject().put("bytes", DATA.length).put("sha256", hash(DATA))))
        .toString();
  }

  @Test
  public void corruptReplacementNeverReplacesPreviouslyWorkingPack() throws Exception {
    VoicePack pack = new VoicePack(temp.getRoot(), manifest("model.onnx"));
    pack.install(url -> new ByteArrayInputStream(goodArchive()), (d, t) -> {}, () -> false);
    assertTrue(pack.installed());
    try {
      pack.install(
          url -> new ByteArrayInputStream(new byte[DATA.length]), (d, t) -> {}, () -> false);
      fail();
    } catch (IOException expected) {
      assertNotNull(expected.getMessage());
    }
    pack.verify(() -> false);
    assertArrayEquals(DATA, Files.readAllBytes(new File(pack.directory, "model.onnx").toPath()));
    assertFalse(new File(temp.getRoot(), "test-voice.staging").exists());
  }

  @Test
  public void cancelledOrTruncatedDownloadHasNoReadyMarker() throws Exception {
    VoicePack pack = new VoicePack(temp.getRoot(), manifest("model.onnx"));
    AtomicInteger cancellation = new AtomicInteger();
    try {
      pack.install(
          url -> new ByteArrayInputStream(goodArchive()),
          (d, t) -> cancellation.incrementAndGet(),
          () -> cancellation.get() > 0);
      fail();
    } catch (InterruptedIOException expected) {
    }
    assertFalse(pack.installed());
    try {
      pack.install(url -> new ByteArrayInputStream(new byte[1]), (d, t) -> {}, () -> false);
      fail();
    } catch (IOException expected) {
    }
    assertFalse(pack.installed());
    assertFalse(new File(temp.getRoot(), "test-voice.staging").exists());
  }

  @Test
  public void validArchiveHashStillRequiresMatchingModelHashAndRejectsEscapingEntries()
      throws Exception {
    byte[] wrong = archive("model.onnx", new byte[DATA.length]);
    VoicePack pack = new VoicePack(temp.getRoot(), manifest("model.onnx", wrong));
    try {
      pack.install(url -> new ByteArrayInputStream(wrong), (d, t) -> {}, () -> false);
      fail();
    } catch (IOException expected) {
    }
    assertFalse(pack.installed());
    byte[] escaping = archive("../escape", DATA);
    VoicePack escape = new VoicePack(temp.getRoot(), manifest("model.onnx", escaping));
    try {
      escape.install(url -> new ByteArrayInputStream(escaping), (d, t) -> {}, () -> false);
      fail();
    } catch (IOException expected) {
    }
    assertFalse(new File(temp.getRoot(), "escape").exists());
  }

  @Test
  public void pathTraversalIsRejectedBeforeAnyFilesAreCreated() throws Exception {
    for (String path :
        new String[] {"../escape", "/absolute", "dict/../../escape", "dict\\escape"}) {
      try {
        new VoicePack(temp.getRoot(), manifest(path));
        fail(path);
      } catch (IOException expected) {
      }
    }
    assertEquals(0, temp.getRoot().list().length);
  }

  @Test
  public void restartRecoversPreviousPackAndFullVerificationDetectsSameLengthCorruption()
      throws Exception {
    String json = manifest("model.onnx");
    VoicePack pack = new VoicePack(temp.getRoot(), json);
    pack.install(url -> new ByteArrayInputStream(goodArchive()), (d, t) -> {}, () -> false);
    assertTrue(pack.directory.renameTo(new File(temp.getRoot(), "test-voice.previous")));
    pack = new VoicePack(temp.getRoot(), json);
    assertTrue(pack.installed());
    Files.write(new File(pack.directory, "model.onnx").toPath(), new byte[DATA.length]);
    try {
      pack.verify(() -> false);
      fail();
    } catch (IOException expected) {
    }
    pack.delete();
    assertFalse(pack.installed());
  }
}
