package com.ebaneymar.quickgrade;

import android.Manifest;
import android.app.Activity;
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
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE_CHOOSER = 501;
    private static final int REQ_CAMERA_PERMISSION = 502;
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingCameraUri;
    private PermissionRequest pendingWebPermissionRequest;
    private boolean pendingDirectCapture;
    private boolean pendingFileChooserLaunch;
    private volatile boolean forceDirectCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        setContentView(webView);

        configureWebView();
        webView.loadUrl(APP_URL);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme())
                        && APP_HOST.equals(uri.getHost())
                        && "/assets/index.html".equals(uri.getPath())) {
                    try {
                        return new WebResourceResponse("text/html", "UTF-8", getAssets().open("index.html"));
                    } catch (IOException e) {
                        return null;
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri origin = request.getOrigin();
                    if (!"https".equals(origin.getScheme()) || !APP_HOST.equals(origin.getHost())) {
                        request.deny();
                        return;
                    }
                    boolean wantsVideo = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            wantsVideo = true;
                            break;
                        }
                    }
                    if (wantsVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingWebPermissionRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                    } else {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                pendingDirectCapture = forceDirectCapture || params.isCaptureEnabled();
                forceDirectCapture = false;

                if (pendingDirectCapture
                        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    pendingFileChooserLaunch = true;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                    return true;
                }
                return launchFileChooser(pendingDirectCapture);
            }
        });
    }

    private boolean launchFileChooser(boolean directCapture) {
        Intent cameraIntent = buildCameraIntent();
        Intent intent;

        if (directCapture && cameraIntent != null) {
            intent = cameraIntent;
        } else {
            Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
            pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
            pickIntent.setType("image/*");
            intent = Intent.createChooser(pickIntent, "Scan answer sheet");
            if (cameraIntent != null) {
                intent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
            }
        }

        try {
            startActivityForResult(intent, REQ_FILE_CHOOSER);
            return true;
        } catch (Exception e) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = null;
            pendingCameraUri = null;
            Toast.makeText(this, "Unable to open the camera or photo picker", Toast.LENGTH_LONG).show();
            return false;
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingWebPermissionRequest != null) {
                if (granted) pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
                else pendingWebPermissionRequest.deny();
                pendingWebPermissionRequest = null;
            }
            if (pendingFileChooserLaunch) {
                pendingFileChooserLaunch = false;
                launchFileChooser(granted && pendingDirectCapture);
            }
            if (!granted) {
                Toast.makeText(this, "Camera access is off. Enable it in Settings > Apps > QuickGrade > Permissions.", Toast.LENGTH_LONG).show();
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

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void requestDirectCapture() {
            forceDirectCapture = true;
        }

        @JavascriptInterface
        public void saveDataUrl(String dataUrl, String fileName, String mimeType) {
            runOnUiThread(() -> saveDataUrlInternal(dataUrl, fileName, mimeType));
        }
    }

    private void saveDataUrlInternal(String dataUrl, String fileName, String mimeType) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("Invalid data URL");
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            String safeName = fileName == null || fileName.trim().isEmpty() ? "QuickGrade_Export" : fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
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
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
        }
        super.onDestroy();
    }
}
