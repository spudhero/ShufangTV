package io.github.spudhero.shufangtv.speech;

import java.io.IOException;

public final class OfflineSpeechSource implements PcmSpeechEngine.Source {
  private final VoicePackManager manager;
  private AutoCloseable lease;
  private OfflineVoice voice;

  public OfflineSpeechSource(VoicePackManager manager) {
    this.manager = manager;
  }

  @Override
  public void initialize(VoicePack.Cancelled cancelled) throws IOException {
    lease = manager.acquire();
    try {
      manager.pack.verify(cancelled);
      VoicePack.check(cancelled);
      voice = new OfflineVoice(manager.pack.directory);
    } catch (IOException | RuntimeException | LinkageError e) {
      close();
      throw e;
    }
  }

  @Override
  public PcmSpeechEngine.Clip synthesize(String text, float rate, VoicePack.Cancelled cancelled)
      throws IOException {
    VoicePack.check(cancelled);
    short[] samples = voice.synthesize(text, rate);
    VoicePack.check(cancelled);
    return new PcmSpeechEngine.Clip(samples, voice.sampleRate);
  }

  @Override
  public void close() {
    if (voice != null) {
      voice.close();
      voice = null;
    }
    if (lease != null) {
      try {
        lease.close();
      } catch (Exception ignored) {
      }
      lease = null;
    }
  }
}
