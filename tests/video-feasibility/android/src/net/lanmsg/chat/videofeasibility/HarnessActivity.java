package net.lanmsg.chat.videofeasibility;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Base64;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Deliberately separate test APK. adb supplies commands, never RTP/media transport. */
public final class HarnessActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private HarnessMedia media;
  private TextView status;
  private volatile boolean visible;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
    status = new TextView(this);
    status.setText("Test-only video endpoint. Use USB adb commands. Generated source is the default.\n"
      + "Keep this activity visible while testing. Stop releases all test media.\n"
      + "No production LAN Messenger data is used.");
    content.addView(status);
    Button permissions = new Button(this); permissions.setText("Grant microphone / optional camera");
    permissions.setOnClickListener(v -> requestPermissions(new String[]{
      "android.permission.RECORD_AUDIO", "android.permission.CAMERA"}, 1));
    content.addView(permissions);
    Button stop = new Button(this); stop.setText("Stop test media");
    stop.setOnClickListener(v -> worker.execute(this::release)); content.addView(stop);
    setContentView(content);
  }
  @Override public void onResume() {
    super.onResume(); visible = true;
    Intent pending = getIntent();
    if (pending != null && pending.hasExtra("cmd")) { setIntent(new Intent()); dispatch(pending); }
  }
  @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
  @Override public void onStop() {
    visible = false;
    worker.execute(this::release);
    super.onStop();
  }
  @Override public void onDestroy() {
    visible = false; worker.execute(this::release); worker.shutdown(); super.onDestroy();
  }
  private void release() {
    if (media != null) { media.close(); media = null; }
    runOnUiThread(() -> status.setText("Test media stopped. No capture active."));
  }
  private static String value(Intent i, String key, String fallback) {
    String v = i.getStringExtra(key); return v == null ? fallback : v;
  }
  private void dispatch(Intent i) {
    String request = value(i, "request", "");
    if (!request.matches("[a-zA-Z0-9_-]{1,64}")) return;
    worker.execute(() -> {
      JSONObject result = new JSONObject();
      try {
        result.put("request", request);
        String command = value(i, "cmd", "stats");
        if (command.equals("stop")) { release(); result.put("stopped", true); }
        else {
          if (!visible) throw new IllegalStateException("Test activity must be visible");
          if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Grant test APK microphone permission first");
          if (media == null) media = new HarnessMedia(this, () -> visible);
          String node = value(i, "node", "a");
          String codec = value(i, "codec", "VP8");
          String profile = value(i, "profile", "");
          String mode = value(i, "mode", "audio");
          String source = value(i, "source", "generated");
          String encoded = value(i, "sdp", "");
          if (encoded.length() > 65_536) throw new IllegalArgumentException("SDP input too large");
          String sdp = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
          switch (command) {
            case "init": result.put("capabilities", media.init(node, codec, profile, mode, source)); break;
            case "offer": result.put("sdp", media.offer(node)); result.put("type", "offer"); break;
            case "answer": result.put("sdp", media.answer(node, sdp)); result.put("type", "answer"); break;
            case "remote": media.remoteAnswer(node, sdp); result.put("applied", true); break;
            case "upgrade":
            case "activate": media.video(node); result.put("sdp", media.offer(node)); result.put("type", "offer"); break;
            case "camera-off": media.stopVideo(node); result.put("stoppedVideo", true); break;
            case "stats": result.put("stats", media.stats(node)); break;
            case "loopback":
              if (!source.equals("generated")) throw new IllegalArgumentException("Loopback uses generated frames; use init/video commands for camera tests");
              result.put("evidence", media.loopback(codec, profile, mode)); break;
            default: throw new IllegalArgumentException("Unknown command");
          }
        }
        result.put("ok", true);
      } catch (Throwable e) {
        try { result.put("ok", false); result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
        catch (Exception ignored) {}
        // A failed media command cannot leave the test camera or microphone running.
        release();
      }
      try {
        File tmp = new File(getFilesDir(), request + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
          out.write(result.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(getFilesDir(), request + ".json"))) throw new IOException("Result rename failed");
      } catch (Exception e) { android.util.Log.e("VideoFeasibility", "Cannot write result", e); }
      final String message = result.optBoolean("ok") ? "Completed " + value(i, "cmd", "stats")
        : result.optString("error");
      runOnUiThread(() -> status.setText(message + "\nUSB adb reads the test result. Keep this screen visible."));
    });
  }
}
