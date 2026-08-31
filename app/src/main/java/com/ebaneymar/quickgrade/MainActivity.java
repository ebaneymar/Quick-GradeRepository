package com.ebaneymar.quickgrade;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE_CHOOSER = 501;
    private static final int REQ_CAMERA_PERMISSION = 502;

    // MATH-a-PANG-style two-file updater:
    // 1) update-manifest.json
    // 2) QuickGrade_Update.zip
    private static final String SHELL_VERSION = "1.2.0";
    private static final String BUNDLED_RUNTIME_VERSION = "1.4.0";
    private static final String UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/ebaneymar/Quick-GradeRepository/main/update-manifest.json";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingCameraUri;
    private PermissionRequest pendingWebPermissionRequest;
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean automaticUpdateCheckStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        setContentView(webView);

        configureWebView();
        requestCameraPermissionIfNeeded();
        loadQuickGradeFromSecureLocalOrigin();
        mainHandler.postDelayed(() -> {
            if (!automaticUpdateCheckStarted) {
                automaticUpdateCheckStarted = true;
                checkForUpdates(false);
            }
        }, 1800);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsVideo = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            wantsVideo = true;
                            break;
                        }
                    }

                    if (!wantsVideo) {
                        request.deny();
                        return;
                    }

                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingWebPermissionRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                    } else {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermissionRequest == request) pendingWebPermissionRequest = null;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;

                Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
                pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
                pickIntent.setType("image/*");

                Intent cameraIntent = buildCameraIntent();
                Intent chooser = Intent.createChooser(pickIntent, "Choose answer-sheet photo");
                if (cameraIntent != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                }

                try {
                    startActivityForResult(chooser, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "Unable to open photo picker", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
    }

    /**
     * The WebView always uses the same HTTPS base origin so localStorage and IndexedDB survive
     * runtime HTML updates. Updated index.html is stored in internal app storage; if none exists,
     * the bundled asset is used.
     */
    private void loadQuickGradeFromSecureLocalOrigin() {
        try {
            String html = readRuntimeHtml();
            webView.loadDataWithBaseURL(
                    "https://quickgrade.local/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
            );
        } catch (Exception e) {
            Toast.makeText(this, "QuickGrade failed to load: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String readRuntimeHtml() throws Exception {
        File runtime = getRuntimeIndexFile();
        if (runtime.exists() && runtime.length() > 0) {
            try (InputStream in = new FileInputStream(runtime)) {
                return readUtf8(in);
            } catch (Exception ignored) {
                // Fall back to the bundled app if an interrupted update left a bad file.
            }
        }
        try (InputStream in = getAssets().open("index.html")) {
            return readUtf8(in);
        }
    }

    private String readUtf8(InputStream in) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private File getRuntimeDir() {
        return new File(getFilesDir(), "runtime");
    }

    private File getRuntimeIndexFile() {
        return new File(getRuntimeDir(), "index.html");
    }

    private File getRuntimeVersionFile() {
        return new File(getRuntimeDir(), "version.txt");
    }

    private String getCurrentRuntimeVersion() {
        File f = getRuntimeVersionFile();
        if (!f.exists()) return BUNDLED_RUNTIME_VERSION;
        try (InputStream in = new FileInputStream(f)) {
            String v = readUtf8(in).trim();
            return v.isEmpty() ? BUNDLED_RUNTIME_VERSION : v;
        } catch (Exception e) {
            return BUNDLED_RUNTIME_VERSION;
        }
    }

    private Intent buildCameraIntent() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null;
        try {
            File dir = new File(getCacheDir(), "quickgrade_captures");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File photo = new File(dir, "scan_" + System.currentTimeMillis() + ".jpg");
            if (!photo.exists()) photo.createNewFile();
            pendingCameraUri = QuickGradeFileProvider.uriForFile(this, photo);

            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("QuickGrade scan", pendingCameraUri));
            return camera.resolveActivity(getPackageManager()) != null ? camera : null;
        } catch (Exception e) {
            pendingCameraUri = null;
            return null;
        }
    }

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingWebPermissionRequest != null) {
                if (granted) pendingWebPermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                else pendingWebPermissionRequest.deny();
                pendingWebPermissionRequest = null;
            }
            if (!granted) {
                Toast.makeText(this, "Allow Camera permission to use full-screen scanning.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER || fileCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data == null || data.getData() == null) {
                if (pendingCameraUri != null) result = new Uri[]{pendingCameraUri};
            } else {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
        pendingCameraUri = null;
    }

    private void setScannerFullscreen(boolean enabled) {
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){var o=document.getElementById('cameraOverlay');return !!(o&&o.classList.contains('open'));})()",
                    value -> {
                        if ("true".equals(value)) {
                            webView.evaluateJavascript("stopCamera()", null);
                        } else if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            super.onBackPressed();
                        }
                    }
            );
        } else {
            super.onBackPressed();
        }
    }


    private void notifyUpdateStatus(String message) {
        runOnUiThread(() -> {
            String js = "if(window.quickGradeUpdateStatus)window.quickGradeUpdateStatus(" + JSONObject.quote(message) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void checkForUpdates(boolean userInitiated) {
        notifyUpdateStatus(userInitiated ? "Checking for update…" :
                "Shell " + SHELL_VERSION + " · Runtime " + getCurrentRuntimeVersion() + " · checking…");
        updateExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(UPDATE_MANIFEST_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(15000);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", "QuickGrade-Android/" + SHELL_VERSION);
                int code = conn.getResponseCode();
                if (code != 200) throw new IllegalStateException("Manifest HTTP " + code);
                JSONObject manifest;
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    manifest = new JSONObject(readUtf8(in));
                }

                String latest = manifest.optString("version", "").trim();
                String minShell = manifest.optString("min_shell_version", "1.2.0").trim();
                if (latest.isEmpty()) throw new IllegalStateException("Manifest has no version");

                if (compareVersions(SHELL_VERSION, minShell) < 0) {
                    notifyUpdateStatus("New Android shell required (minimum " + minShell + ").");
                    mainHandler.post(() -> new AlertDialog.Builder(this)
                            .setTitle("App update required")
                            .setMessage("This update needs a newer QuickGrade Android shell. Your saved grades remain on this device. Install the newer APK, then reopen QuickGrade.")
                            .setPositiveButton("OK", null)
                            .show());
                    return;
                }

                String current = getCurrentRuntimeVersion();
                if (compareVersions(latest, current) <= 0) {
                    notifyUpdateStatus("Up to date · Shell " + SHELL_VERSION + " · Runtime " + current);
                    if (userInitiated) mainHandler.post(() -> Toast.makeText(this, "QuickGrade is up to date.", Toast.LENGTH_SHORT).show());
                    return;
                }

                mainHandler.post(() -> showUpdateDialog(manifest));
            } catch (Exception e) {
                notifyUpdateStatus("Update check unavailable · " + e.getMessage());
                if (userInitiated) mainHandler.post(() -> Toast.makeText(this, "Could not check update: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void showUpdateDialog(JSONObject manifest) {
        String latest = manifest.optString("version", "new");
        String notes = manifest.optString("notes", "A QuickGrade update is available.");
        new AlertDialog.Builder(this)
                .setTitle("QuickGrade update " + latest)
                .setMessage(notes + "\n\nYour quizzes, roster, scores, scan history, and saved images are kept.")
                .setNegativeButton("Later", (d, w) -> notifyUpdateStatus("Update " + latest + " available"))
                .setPositiveButton("Download & Install", (d, w) -> downloadAndApplyRuntimeUpdate(manifest))
                .show();
    }

    private void downloadAndApplyRuntimeUpdate(JSONObject manifest) {
        notifyUpdateStatus("Downloading update…");
        updateExecutor.execute(() -> {
            File packageFile = new File(getCacheDir(), "QuickGrade_Update.zip");
            try {
                String url = manifest.getString("download_url");
                String expectedSha = manifest.optString("sha256", "").trim().toLowerCase();
                long expectedSize = manifest.optLong("size", 0L);
                String version = manifest.getString("version").trim();

                downloadFile(url, packageFile);
                if (expectedSize > 0 && packageFile.length() != expectedSize) {
                    throw new IllegalStateException("Package size mismatch");
                }
                if (!expectedSha.isEmpty()) {
                    String actual = sha256(packageFile);
                    if (!actual.equalsIgnoreCase(expectedSha)) {
                        throw new SecurityException("SHA-256 verification failed");
                    }
                }

                File tempDir = new File(getFilesDir(), "runtime_new");
                deleteRecursively(tempDir);
                if (!tempDir.mkdirs() && !tempDir.isDirectory()) throw new IllegalStateException("Cannot create update folder");
                unzipSafely(packageFile, tempDir);
                File newIndex = new File(tempDir, "index.html");
                if (!newIndex.exists() || newIndex.length() < 1000) throw new IllegalStateException("Update has no valid index.html");

                File runtimeDir = getRuntimeDir();
                if (!runtimeDir.exists() && !runtimeDir.mkdirs()) throw new IllegalStateException("Cannot create runtime folder");
                File target = getRuntimeIndexFile();
                File tempTarget = new File(runtimeDir, "index.html.new");
                copyFile(newIndex, tempTarget);
                if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace old runtime");
                if (!tempTarget.renameTo(target)) {
                    copyFile(tempTarget, target);
                    tempTarget.delete();
                }
                try (FileWriter fw = new FileWriter(getRuntimeVersionFile(), false)) { fw.write(version); }
                deleteRecursively(tempDir);
                packageFile.delete();

                notifyUpdateStatus("Updated to runtime " + version + " ✓");
                mainHandler.post(() -> {
                    Toast.makeText(this, "QuickGrade updated to " + version, Toast.LENGTH_LONG).show();
                    webView.evaluateJavascript("if(typeof stopCamera==='function')stopCamera();", null);
                    loadQuickGradeFromSecureLocalOrigin();
                });
            } catch (Exception e) {
                packageFile.delete();
                notifyUpdateStatus("Update failed · " + e.getMessage());
                mainHandler.post(() -> new AlertDialog.Builder(this)
                        .setTitle("Update failed")
                        .setMessage(e.getMessage())
                        .setPositiveButton("OK", null)
                        .show());
            }
        });
    }

    private void downloadFile(String urlText, File outFile) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlText + (urlText.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "QuickGrade-Android/" + SHELL_VERSION);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("Package HTTP " + code);
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private void unzipSafely(File zipFile, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new SecurityException("Unsafe ZIP path");
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IllegalStateException("Cannot create update folder");
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Cannot create update folder");
                    try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(out))) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = zin.read(buffer)) != -1) fout.write(buffer, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private void copyFile(File from, File to) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(to))) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private int compareVersions(String a, String b) {
        String[] aa = a.split("\\.");
        String[] bb = b.split("\\.");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            int av = i < aa.length ? parseVersionPart(aa[i]) : 0;
            int bv = i < bb.length ? parseVersionPart(bb[i]) : 0;
            if (av != bv) return av < bv ? -1 : 1;
        }
        return 0;
    }

    private int parseVersionPart(String s) {
        try {
            String digits = s.replaceAll("[^0-9].*$", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void saveDataUrl(String dataUrl, String fileName, String mimeType) {
            runOnUiThread(() -> saveDataUrlInternal(dataUrl, fileName, mimeType));
        }

        @JavascriptInterface
        public void enterScannerFullscreen() {
            runOnUiThread(() -> setScannerFullscreen(true));
        }

        @JavascriptInterface
        public void exitScannerFullscreen() {
            runOnUiThread(() -> setScannerFullscreen(false));
        }

        @JavascriptInterface
        public void checkForUpdates() {
            MainActivity.this.checkForUpdates(true);
        }

        @JavascriptInterface
        public String getShellVersion() {
            return SHELL_VERSION;
        }

        @JavascriptInterface
        public String getRuntimeVersion() {
            return getCurrentRuntimeVersion();
        }
    }

    private void saveDataUrlInternal(String dataUrl, String fileName, String mimeType) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Invalid data URL");
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            String safeName = fileName == null || fileName.trim().isEmpty()
                    ? "QuickGrade_Export"
                    : fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            String safeMime = mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                values.put(MediaStore.Downloads.MIME_TYPE, safeMime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QuickGrade");
                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Could not create download");
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Could not open download");
                    out.write(bytes);
                }
                Toast.makeText(this, "Saved to Downloads/QuickGrade/" + safeName, Toast.LENGTH_LONG).show();
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "QuickGrade");
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, safeName);
                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    out.write(bytes);
                }
                Toast.makeText(this, "Saved: " + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        setScannerFullscreen(false);
        updateExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}
