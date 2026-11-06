package io.github.spudhero.shufangtv.speech;

import static org.junit.Assert.*;

import android.content.Context;
import io.github.spudhero.shufangtv.net.LocalHttpFixture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class AzureSpeechTest {
  @Test
  public void keysAreEncryptedAndTamperingCannotProduceAUsableConfiguration() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    AzureSettings settings = new AzureSettings(context, new SecretKeySpec(new byte[32], "AES"));
    AzureSettings.Config config =
        new AzureSettings.Config("eastasia", AzureSettings.VOICES[0], "fixtureKeyOnly123456");
    settings.save(config);
    assertEquals(config.key, settings.read().key);
    String saved = context.getSharedPreferences("speech-credentials", 0).getString("encrypted", "");
    assertFalse(saved.contains(config.key));
    context
        .getSharedPreferences("speech-credentials", 0)
        .edit()
        .putString("encrypted", saved.substring(0, saved.length() - 8) + "AAAAAAAA")
        .commit();
    assertFalse(settings.configured());
    try {
      settings.read();
      fail();
    } catch (IOException expected) {
    }
    settings.clear();
    assertNull(settings.read());
    assertEquals(SpeechSettings.Provider.OFFLINE, SpeechSettings.provider(context));
  }

  @Test
  public void restRequestEscapesBookTextAndNeverForwardsKeyOnRedirect() throws Exception {
    AtomicReference<String> ssml = new AtomicReference<>(), key = new AtomicReference<>();
    AtomicInteger redirected = new AtomicInteger();
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request -> {
              if (request.path.equals("/redirect"))
                return new LocalHttpFixture.Reply(302, new byte[0]).header("Location", "/leak");
              if (request.path.equals("/leak")) {
                redirected.incrementAndGet();
                return new LocalHttpFixture.Reply(200, new byte[0]);
              }
              ssml.set(new String(request.body, StandardCharsets.UTF_8));
              key.set(request.headers.get("ocp-apim-subscription-key"));
              assertEquals(
                  "raw-24khz-16bit-mono-pcm", request.headers.get("x-microsoft-outputformat"));
              return new LocalHttpFixture.Reply(200, new byte[] {0x34, 0x12, 0, (byte) 0x80})
                  .header("Content-Type", "audio/pcm");
            })) {
      String root = server.origin();
      AzureSettings.Config config =
          new AzureSettings.Config("eastasia", AzureSettings.VOICES[0], "fixtureOnly123");
      AzureSpeechSource source = new AzureSpeechSource(config, new OkHttpClient(), root + "/audio");
      source.initialize(() -> false);
      PcmSpeechEngine.Clip clip = source.synthesize("<voice>&正文\u0001🌙", 1.5f, () -> false);
      assertEquals(config.key, key.get());
      assertTrue(ssml.get().contains("&lt;voice&gt;&amp;正文🌙"));
      assertTrue(ssml.get().contains("rate='+50%'"));
      assertFalse(ssml.get().contains("\u0001"));
      assertEquals(24_000, clip.sampleRate);
      assertArrayEquals(new short[] {0x1234, Short.MIN_VALUE}, clip.samples);
      AzureSpeechSource redirect =
          new AzureSpeechSource(config, new OkHttpClient(), root + "/redirect");
      redirect.initialize(() -> false);
      try {
        redirect.synthesize("正文", 1f, () -> false);
        fail();
      } catch (IOException expected) {
      }
      assertEquals(0, redirected.get());
    }
  }

  @Test
  public void wrongContentTypeTruncatedPcmAndAuthenticationErrorsAreRejected() throws Exception {
    AtomicInteger mode = new AtomicInteger();
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request ->
                new LocalHttpFixture.Reply(mode.get() == 2 ? 401 : 200, new byte[] {1})
                    .header("Content-Type", mode.get() == 0 ? "text/html" : "audio/pcm"))) {
      AzureSpeechSource source =
          new AzureSpeechSource(
              new AzureSettings.Config("eastasia", AzureSettings.VOICES[0], "fixtureOnly123"),
              new OkHttpClient(),
              server.origin());
      source.initialize(() -> false);
      for (int i = 0; i < 3; i++) {
        mode.set(i);
        try {
          source.synthesize("正文", 1f, () -> false);
          fail();
        } catch (IOException expected) {
          assertFalse(expected.getMessage().contains("fixtureOnly123"));
        }
      }
      for (String region : new String[] {"evil.com/", "user@host", "../eastasia"}) {
        try {
          new AzureSettings.Config(region, AzureSettings.VOICES[0], "fixtureOnly123");
          fail();
        } catch (IOException expected) {
        }
      }
    }
  }
}
