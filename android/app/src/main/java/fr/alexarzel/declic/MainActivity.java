package fr.alexarzel.declic;

import android.Manifest;
import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

/**
 * Déclic : l'appli web (assets/www/index.html) servie dans une WebView.
 * Les fichiers sont servis depuis https://appassets.androidplatform.net/ pour que
 * localStorage, IndexedDB et fetch fonctionnent comme sur un vrai site.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int FILE_REQUEST = 42;

    private WebView web;
    private volatile boolean usingLive;
    private volatile String pendingUpdate = "";
    private long lastStepsPush = 0;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String ttsPending;
    private final SensorEventListener stepListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            Steps.onTotal(MainActivity.this, e.values[0]);
            long now = System.currentTimeMillis();
            if (now - lastStepsPush > 15000) {
                lastStepsPush = now;
                web.evaluateJavascript("window.onDeclicSteps && window.onDeclicSteps()", null);
                Widget.refresh(MainActivity.this);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private void startSteps() {
        if (!Steps.enabled(this) || !Steps.permitted(this)) return;
        Sensor s = Steps.sensor(this);
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (s != null && sm != null) sm.registerListener(stepListener, s, SensorManager.SENSOR_DELAY_NORMAL);
        Steps.scheduleMidnight(this);
    }

    private void stopSteps() {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm != null) sm.unregisterListener(stepListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSteps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSteps();
        Widget.refresh(this);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    /** Lecture à voix haute (mode séance guidée). */
    private void say(String text) {
        if (tts == null) {
            ttsPending = text;
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.FRANCE);
                    ttsReady = true;
                    if (ttsPending != null) tts.speak(ttsPending, TextToSpeech.QUEUE_FLUSH, null, "declic");
                }
                ttsPending = null;
            });
            return;
        }
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "declic");
        else ttsPending = text;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 8 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startSteps();
            web.evaluateJavascript("window.onDeclicSteps && window.onDeclicSteps(); window.render && render()", null);
        }
    }
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/live/", new WebViewAssetLoader.InternalStoragePathHandler(this, liveDir()))
                .build();
        usingLive = LiveUpdate.hasUsablePage(this);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);

        web.addJavascriptInterface(new Bridge(), "ElanAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, FILE_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(startUrl());
        }

        // Cherche une nouvelle version en arrière-plan.
        new Thread(() -> {
            LiveUpdate.Result r = LiveUpdate.check(getApplicationContext(), usingLive);
            if (r == LiveUpdate.Result.NONE) return;
            pendingUpdate = r == LiveUpdate.Result.WEB_READY ? "web" : "apk";
            runOnUiThread(() -> web.evaluateJavascript(
                    "window.onDeclicUpdate && window.onDeclicUpdate('" + pendingUpdate + "')", null));
        }).start();
    }

    private java.io.File liveDir() {
        java.io.File d = LiveUpdate.dir(this);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private String startUrl() {
        return "https://" + HOST + (usingLive ? "/live/index.html" : "/assets/www/index.html");
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7));
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_REQUEST || fileCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.elanBack && window.elanBack()) ? '1' : '0'", value -> {
            if (!"\"1\"".equals(value)) finish();
        });
    }

    /** Fonctions appelables depuis la page : window.ElanAndroid.xxx() */
    private class Bridge {
        @JavascriptInterface
        public int versionCode() {
            return BuildConfig.VERSION_CODE;
        }

        @JavascriptInterface
        public String versionName() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void setReminder(final String json) {
            Reminders.save(MainActivity.this, json);
        }

        @JavascriptInterface
        public void requestNotifications() {
            askNotificationPermission();
        }

        @JavascriptInterface
        public void markTrained(final String day) {
            Reminders.markTrained(MainActivity.this, day);
        }

        @JavascriptInterface
        public void testNotification(final String text) {
            askNotificationPermission();
            Reminders.show(MainActivity.this, text);
        }

        @JavascriptInterface
        public boolean stepsAvailable() {
            return Steps.available(MainActivity.this);
        }

        @JavascriptInterface
        public boolean stepsPermitted() {
            return Steps.enabled(MainActivity.this) && Steps.permitted(MainActivity.this);
        }

        @JavascriptInterface
        public String stepsJson() {
            return Steps.json(MainActivity.this);
        }

        @JavascriptInterface
        public void requestSteps() {
            Steps.prefs(MainActivity.this).edit().putBoolean("enabled", true).apply();
            runOnUiThread(() -> {
                if (Steps.permitted(MainActivity.this)) {
                    startSteps();
                    web.evaluateJavascript("window.onDeclicSteps && window.onDeclicSteps(); window.render && render()", null);
                } else if (Build.VERSION.SDK_INT >= 29) {
                    requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 8);
                }
            });
        }

        @JavascriptInterface
        public void shareText(final String name, final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, name);
                i.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(i, "Enregistrer ma sauvegarde Déclic"));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(() -> say(text));
        }

        @JavascriptInterface
        public void markWater(final String day, final int count, final int goal) {
            Water.mark(MainActivity.this, day, count, goal);
            Widget.refresh(MainActivity.this);
        }

        @JavascriptInterface
        public void setWater(final String json) {
            Water.save(MainActivity.this, json);
            if (json.contains("\"on\":true")) askNotificationPermission();
        }

        @JavascriptInterface
        public void updateWidget(final String json) {
            Widget.save(MainActivity.this, json);
        }

        @JavascriptInterface
        public String pendingUpdate() {
            return pendingUpdate;
        }

        @JavascriptInterface
        public String apkUrl() {
            return LiveUpdate.APK_URL;
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> {
                usingLive = LiveUpdate.hasUsablePage(MainActivity.this);
                pendingUpdate = "";
                web.loadUrl(startUrl());
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(() -> openExternal(Uri.parse(url)));
        }
    }
}
