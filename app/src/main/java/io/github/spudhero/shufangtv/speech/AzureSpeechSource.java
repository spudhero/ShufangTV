package io.github.spudhero.shufangtv.speech;

import java.io.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Official Azure Speech REST, used only when explicitly selected and configured. */
public final class AzureSpeechSource implements PcmSpeechEngine.Source {
  private final AzureSettings settings;
  private AzureSettings.Config config;
  private final OkHttpClient client;
  private final String endpointOverride;
  private volatile Call call;

  public AzureSpeechSource(AzureSettings settings) {
    this.settings = settings;
    endpointOverride = null;
    client =
        new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();
  }

  AzureSpeechSource(AzureSettings.Config config, OkHttpClient client, String endpoint) {
    this.settings = null;
    this.config = config;
    this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
    endpointOverride = endpoint;
  }

  @Override
  public void initialize(VoicePack.Cancelled cancelled) throws IOException {
    VoicePack.check(cancelled);
    if (settings != null) config = settings.read();
    if (config == null) throw new IOException("请先配置网络语音的地区与密钥");
  }

  @Override
  public PcmSpeechEngine.Clip synthesize(String text, float rate, VoicePack.Cancelled cancelled)
      throws IOException {
    VoicePack.check(cancelled);
    if (text.length() > 512 || !Float.isFinite(rate) || rate < .5f || rate > 2)
      throw new IOException("无效的网络语音请求");
    String xml =
        "<speak version='1.0' xml:lang='zh-CN'><voice name='"
            + config.voice
            + "'><prosody rate='"
            + String.format(Locale.ROOT, "%+d%%", Math.round((rate - 1) * 100))
            + "'>"
            + escape(text)
            + "</prosody></voice></speak>";
    Call pending =
        client.newCall(
            new Request.Builder()
                .url(endpointOverride != null ? endpointOverride : config.endpoint())
                .header("Ocp-Apim-Subscription-Key", config.key)
                .header("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
                .header("User-Agent", "ShufangTV")
                .post(RequestBody.create(xml, MediaType.get("application/ssml+xml; charset=utf-8")))
                .build());
    call = pending;
    try {
      VoicePack.check(cancelled);
      try (Response response = pending.execute()) {
        if (response.code() == 401 || response.code() == 403)
          throw new IOException("网络语音认证失败，请检查地区和密钥");
        if (response.code() == 429) throw new IOException("网络语音请求受限，请稍后重试");
        if (!response.isSuccessful() || response.body() == null)
          throw new IOException("网络语音服务暂不可用，请稍后重试");
        String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
        if (!(type.startsWith("audio/") || type.startsWith("application/octet-stream")))
          throw new IOException("网络语音返回了无效音频");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = response.body().byteStream()) {
          byte[] buffer = new byte[8192];
          int n;
          while ((n = input.read(buffer)) != -1) {
            VoicePack.check(cancelled);
            if (bytes.size() + n > 24_000 * 2 * 90) throw new IOException("网络语音片段过长");
            bytes.write(buffer, 0, n);
          }
        }
        byte[] raw = bytes.toByteArray();
        if (raw.length == 0 || raw.length % 2 != 0) throw new IOException("网络语音返回了无效音频");
        short[] pcm = new short[raw.length / 2];
        for (int i = 0; i < pcm.length; i++)
          pcm[i] = (short) ((raw[i * 2] & 255) | raw[i * 2 + 1] << 8);
        return new PcmSpeechEngine.Clip(pcm, 24_000);
      }
    } finally {
      if (call == pending) call = null;
    }
  }

  static String escape(String text) {
    StringBuilder out = new StringBuilder();
    text.codePoints()
        .filter(
            c ->
                c == 9
                    || c == 10
                    || c == 13
                    || (c >= 32 && c <= 0xd7ff)
                    || (c >= 0xe000 && c <= 0xfffd)
                    || (c >= 0x10000 && c <= 0x10ffff))
        .forEach(
            c -> {
              if (c == '&') out.append("&amp;");
              else if (c == '<') out.append("&lt;");
              else if (c == '>') out.append("&gt;");
              else out.appendCodePoint(c);
            });
    return out.toString();
  }

  @Override
  public void cancel() {
    Call pending = call;
    if (pending != null) pending.cancel();
  }

  @Override
  public void close() {
    cancel();
  }
}
