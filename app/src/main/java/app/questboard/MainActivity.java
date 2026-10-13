package app.questboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 依頼掲示板アプリの外枠。
 * 画面そのものは assets/www/index.html（Web技術）で作り、ここでは
 * ・画面の表示 ・カメラ/ギャラリー ・戻るボタン ・振動 ・自動更新
 * を受け持つ。
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final int REQ_FILE = 10;
    private static final int REQ_INSTALL_PERMISSION = 11;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private String pendingApkUrl;
    private File downloadedApk;
    private boolean checkedOnLaunch = false;
    private boolean downloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFF1B120D);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                cameraUri = null;

                Intent intent;
                if (params.isCaptureEnabled()) {
                    try {
                        File dir = new File(getCacheDir(), "camera");
                        if (!dir.exists()) dir.mkdirs();
                        File photo = File.createTempFile("shot", ".jpg", dir);
                        cameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", photo);
                        intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (IOException e) {
                        finishFileChooser(null);
                        return true;
                    }
                } else {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                }

                try {
                    startActivityForResult(intent, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    finishFileChooser(null);
                    Toast.makeText(MainActivity.this, "カメラや写真のアプリが見つかりませんでした", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    private void finishFileChooser(Uri[] result) {
        if (fileCallback != null) fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK) {
                if (cameraUri != null) {
                    result = new Uri[]{cameraUri};
                } else if (data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            finishFileChooser(result);
        } else if (requestCode == REQ_INSTALL_PERMISSION) {
            if (canInstall() && downloadedApk != null) {
                installApk(downloadedApk);
            } else {
                callJs("QB.onUpdateError(" + JSONObject.quote("更新するには「このアプリからのインストール」を許可してください。") + ")");
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.QB && QB.handleBack) ? QB.handleBack() : 'false'", value -> {
            if (!"\"true\"".equals(value)) {
                MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------
    // 画面（JavaScript）との橋渡し。JS からは window.Android.xxx() で呼べる。
    // ---------------------------------------------------------------
    private class Bridge {
        @JavascriptInterface
        public String getVersionName() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public void vibrate(String patternJson) {
            try {
                JSONArray a = new JSONArray(patternJson);
                long[] pattern = new long[a.length() + 1];
                pattern[0] = 0;
                for (int i = 0; i < a.length(); i++) pattern[i + 1] = a.getLong(i);
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void checkUpdate(boolean manual) {
            main.post(() -> checkForUpdate(manual));
        }

        @JavascriptInterface
        public void startUpdate() {
            main.post(MainActivity.this::downloadUpdate);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            main.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }

        @JavascriptInterface
        public void pageReady() {
            main.post(() -> {
                if (!checkedOnLaunch) {
                    checkedOnLaunch = true;
                    checkForUpdate(false);
                }
            });
        }
    }

    private void callJs(String code) {
        main.post(() -> web.evaluateJavascript("window.QB && " + code, null));
    }

    // ---------------------------------------------------------------
    // 自動更新：GitHub の最新リリースを見て、新しい版があれば知らせる
    // ---------------------------------------------------------------
    private void checkForUpdate(boolean manual) {
        final String repo = BuildConfig.UPDATE_REPO;
        if (repo == null || repo.isEmpty()) {
            if (manual) callJs("QB.onUpdateError(" + JSONObject.quote("この版には更新先が設定されていません。") + ")");
            return;
        }
        io.execute(() -> {
            try {
                HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases/latest");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                JSONObject release = new JSONObject(readText(c.getInputStream()));
                c.disconnect();

                int latest = parseVersion(release.optString("tag_name", ""));
                String notes = release.optString("body", "");
                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.optString("name", "").endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }

                if (latest > BuildConfig.VERSION_CODE && apkUrl != null) {
                    pendingApkUrl = apkUrl;
                    callJs("QB.onUpdateAvailable(" + JSONObject.quote(String.valueOf(latest)) + "," + JSONObject.quote(notes) + "," + JSONObject.quote(apkUrl) + ")");
                } else if (manual) {
                    callJs("QB.onUpToDate()");
                }
            } catch (Exception e) {
                if (manual) {
                    callJs("QB.onUpdateError(" + JSONObject.quote("新しい版を確かめられませんでした。通信状態を確かめて、もう一度試してください。") + ")");
                }
            }
        });
    }

    private void downloadUpdate() {
        if (pendingApkUrl == null || downloading) return;
        downloading = true;
        final String url = pendingApkUrl;
        io.execute(() -> {
            File dir = new File(getCacheDir(), "updates");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "update.apk");
            try {
                HttpURLConnection c = open(url);
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                callJs("QB.onUpdateProgress(" + pct + ")");
                            }
                        }
                    }
                }
                c.disconnect();
                main.post(() -> {
                    downloading = false;
                    downloadedApk = out;
                    if (canInstall()) {
                        installApk(out);
                    } else {
                        Toast.makeText(this, "次の画面で「この提供元のアプリを許可」をオンにして、戻ってください", Toast.LENGTH_LONG).show();
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                        try {
                            startActivityForResult(i, REQ_INSTALL_PERMISSION);
                        } catch (ActivityNotFoundException e) {
                            callJs("QB.onUpdateError(" + JSONObject.quote("設定画面を開けませんでした。設定から、このアプリのインストール許可をオンにしてください。") + ")");
                        }
                    }
                });
            } catch (Exception e) {
                main.post(() -> downloading = false);
                callJs("QB.onUpdateError(" + JSONObject.quote("新しい版を取得できませんでした。通信状態を確かめて、もう一度試してください。") + ")");
            }
        });
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void installApk(File apk) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            callJs("QB.onUpdateError(" + JSONObject.quote("インストール画面を開けませんでした。") + ")");
        }
    }

    // ---------------------------------------------------------------
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "QuestBoard-Android");
        return c;
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int parseVersion(String tag) {
        String digits = tag.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
