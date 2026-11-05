package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import io.github.spudhero.shufangtv.cloud.CloudAccount;
import io.github.spudhero.shufangtv.cloud.CloudHttp;
import io.github.spudhero.shufangtv.cloud.CloudProvider;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Poll only while the TV displays this authorization request. Never persist an unconfirmed login.
 */
public final class CloudLoginDialog implements AutoCloseable {
  private final Activity activity;
  private final CloudProvider provider;
  private final ExecutorService io;
  private final Consumer<CloudAccount> authorized;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final AlertDialog dialog;
  private final ImageView image;
  private final TextView status;
  private Future<?> pending;
  private boolean closed;
  private CloudProvider.LoginChallenge challenge;
  private int transientFailures;

  public CloudLoginDialog(
      Activity activity,
      CloudProvider provider,
      ExecutorService io,
      Consumer<CloudAccount> authorized) {
    this.activity = activity;
    this.provider = provider;
    this.io = io;
    this.authorized = authorized;
    LinearLayout content = horizontal(activity);
    content.setGravity(Gravity.CENTER_VERTICAL);
    content.setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 20));
    image = new ImageView(activity);
    image.setBackgroundColor(0xffffffff);
    String scanner =
        provider.id().equals("uc")
            ? "UC 浏览器"
            : provider.id().equals("quark") ? "夸克" : provider.label();
    image.setContentDescription("使用手机上的" + scanner + "扫描此二维码登录");
    add(content, image, 220, 220);
    LinearLayout right = vertical(activity);
    right.setPadding(dp(activity, 24), 0, 0, 0);
    add(right, heading(activity, "扫码连接自己的网盘", 19), -1, 38);
    TextView instructions =
        text(activity, "打开手机上的" + scanner + "，使用扫一扫并确认登录。\n\n登录后选择书籍文件夹，即可在线阅读。", 14, MUTED);
    add(right, instructions, -1, 136);
    status = text(activity, "正在获取二维码…", 13, ACCENT);
    add(right, status, -1, 56);
    add(content, right, 300, -2);
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle(provider.label() + " · 扫码登录")
            .setView(content)
            .setNegativeButton("取消", null)
            .setNeutralButton("刷新二维码", null)
            .create();
    dialog.setOnDismissListener(d -> close());
  }

  public void show() {
    dialog.show();
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> restart());
    restart();
  }

  private void restart() {
    if (closed) return;
    handler.removeCallbacksAndMessages(null);
    if (pending != null) pending.cancel(true);
    challenge = null;
    transientFailures = 0;
    image.setImageDrawable(null);
    status.setText("正在获取二维码…");
    // A generation counter rejects results from a request superseded by refresh.
    int current = ++generation;
    pending =
        io.submit(
            () -> {
              try {
                CloudProvider.LoginChallenge ticket = provider.beginLogin();
                Bitmap qr = ticket.state.optBoolean("qrIsImage") ? image(ticket) : qr(ticket.qrUrl);
                handler.post(
                    () -> {
                      if (closed || current != generation) {
                        qr.recycle();
                        return;
                      }
                      challenge = ticket;
                      image.setImageBitmap(qr);
                      status.setText("等待扫码，请在手机上确认登录");
                      schedule(current);
                    });
              } catch (Exception e) {
                fail(current, safeMessage(e));
              }
            });
  }

  private int generation;

  private void schedule(int current) {
    if (closed || challenge == null || current != generation) return;
    handler.postDelayed(() -> poll(current), Math.max(1500, challenge.pollIntervalMs));
  }

  private void poll(int current) {
    if (closed || current != generation || challenge == null) return;
    if (System.currentTimeMillis() >= challenge.expiresAt) {
      status.setText("二维码已过期，请选择“刷新二维码”");
      return;
    }
    CloudProvider.LoginChallenge ticket = challenge;
    pending =
        io.submit(
            () -> {
              try {
                CloudProvider.LoginResult result = provider.pollLogin(ticket);
                handler.post(
                    () -> {
                      if (closed || current != generation) return;
                      transientFailures = 0;
                      if (result.status == CloudProvider.Status.AUTHORIZED
                          && result.account != null) {
                        dialog.dismiss();
                        authorized.accept(result.account);
                      } else if (result.status == CloudProvider.Status.EXPIRED
                          || result.status == CloudProvider.Status.DENIED) {
                        status.setText(
                            result.message == null || result.message.isEmpty()
                                ? "本次登录已结束，请刷新二维码"
                                : result.message);
                      } else {
                        status.setText(
                            result.status == CloudProvider.Status.SCANNED
                                ? "已扫码，请在手机上确认"
                                : "等待扫码，请在手机上确认登录");
                        schedule(current);
                      }
                    });
              } catch (Exception e) {
                handler.post(
                    () -> {
                      if (closed || current != generation) return;
                      if (++transientFailures <= 2) {
                        status.setText("网络暂时中断，正在重试…");
                        schedule(current);
                      } else status.setText(safeMessage(e) + "，请刷新二维码重试");
                    });
              }
            });
  }

  private void fail(int current, String message) {
    handler.post(
        () -> {
          if (!closed && current == generation) status.setText(message);
        });
  }

  private static String safeMessage(Exception e) {
    // Provider errors are localized; never show raw URLs, cookies, or server response bodies.
    String message = e.getMessage();
    return e instanceof java.io.IOException && message != null && !message.contains("://")
        ? message
        : "扫码连接未完成，请检查网络";
  }

  private static Bitmap image(CloudProvider.LoginChallenge ticket) throws java.io.IOException {
    okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(ticket.qrUrl);
    if (!ticket.providerId.equals("baidu")
        || url == null
        || !url.isHttps()
        || !url.username().isEmpty()
        || !url.password().isEmpty()
        || !(url.host().equals("baidu.com") || url.host().endsWith(".baidu.com")))
      throw new java.io.IOException("官方二维码图片地址无效，请重试");
    try (okhttp3.Response response =
        CloudHttp.client(ticket.account)
            .newCall(new okhttp3.Request.Builder().url(url).build())
            .execute()) {
      if (response.code() != 200
          || response.body() == null
          || response.body().contentLength() > 1024 * 1024)
        throw new java.io.IOException("无法加载官方二维码，请刷新重试");
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = response.body().byteStream().read(buffer)) != -1) {
        if (output.size() + n > 1024 * 1024) throw new java.io.IOException("二维码图片过大");
        output.write(buffer, 0, n);
      }
      byte[] bytes = output.toByteArray();
      android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
      if (bounds.outWidth < 32
          || bounds.outHeight < 32
          || bounds.outWidth > 2048
          || bounds.outHeight > 2048) throw new java.io.IOException("官方二维码图片无效，请重试");
      Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      if (bitmap == null) throw new java.io.IOException("官方二维码图片无法显示");
      return bitmap;
    }
  }

  public static Bitmap qr(String value) throws Exception {
    BitMatrix matrix =
        new QRCodeWriter()
            .encode(
                value,
                BarcodeFormat.QR_CODE,
                660,
                660,
                Collections.singletonMap(EncodeHintType.CHARACTER_SET, "UTF-8"));
    int[] pixels = new int[660 * 660];
    for (int y = 0; y < 660; y++)
      for (int x = 0; x < 660; x++)
        pixels[y * 660 + x] = matrix.get(x, y) ? 0xff111111 : 0xffffffff;
    return Bitmap.createBitmap(pixels, 660, 660, Bitmap.Config.ARGB_8888);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    generation++;
    handler.removeCallbacksAndMessages(null);
    if (pending != null) pending.cancel(true);
    if (dialog.isShowing()) dialog.dismiss();
  }
}
