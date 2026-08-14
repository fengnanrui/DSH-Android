package com.fengnanrui.dshandroid;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final String PREFS = "dsh_android";
    private static final String PREF_ENDPOINT = "endpoint";
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:3080/";
    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_WEB_MEDIA = 1002;
    private static final int REQUEST_WEB_LOCATION = 1003;
    private static final int REQUEST_DOWNLOAD_STORAGE = 1004;

    private SharedPreferences preferences;
    private LinearLayout toolbar;
    private TextView toolbarTitle;
    private ProgressBar toolbarProgress;
    private FrameLayout content;
    private ScrollView connectionPanel;
    private EditText endpointInput;
    private WebView webView;
    private LinearLayout errorPanel;
    private TextView errorMessage;
    private URI endpoint;
    private String endpointOrigin;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingLocationCallback;
    private String pendingLocationOrigin;
    private DownloadSpec pendingDownload;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        buildUi();
        String saved = preferences.getString(PREF_ENDPOINT, DEFAULT_ENDPOINT);
        endpointInput.setText(saved);
        connect(saved, true);
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (pendingWebPermission != null) pendingWebPermission.deny();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (fullscreenView != null) {
            hideFullscreen();
            return;
        }
        if (connectionPanel.getVisibility() == View.VISIBLE) {
            super.onBackPressed();
            return;
        }
        if (errorPanel.getVisibility() == View.VISIBLE) {
            errorPanel.setVisibility(View.GONE);
            showConnection();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("断开当前服务器？")
                .setMessage("Harness 会继续在服务器上运行，会话不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("断开", (dialog, which) -> showConnection())
                .show();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 248, 246));
        setContentView(root);

        toolbar = buildToolbar();
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        connectionPanel = buildConnectionPanel();
        content.addView(connectionPanel, matchParent());

        webView = buildWebView();
        webView.setVisibility(View.GONE);
        content.addView(webView, matchParent());

        errorPanel = buildErrorPanel();
        errorPanel.setVisibility(View.GONE);
        content.addView(errorPanel, matchParent());

        toolbar.setVisibility(View.GONE);
    }

    private LinearLayout buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), 0, dp(4), 0);
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(2));

        TextView home = toolbarButton("‹", "返回连接设置");
        home.setTextSize(34);
        home.setOnClickListener(v -> showConnection());
        bar.addView(home, new LinearLayout.LayoutParams(dp(44), dp(44)));

        toolbarTitle = new TextView(this);
        toolbarTitle.setText("DSH Android");
        toolbarTitle.setTextColor(Color.rgb(23, 24, 26));
        toolbarTitle.setTextSize(17);
        toolbarTitle.setSingleLine(true);
        toolbarTitle.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(toolbarTitle, new LinearLayout.LayoutParams(0, dp(44), 1f));

        toolbarProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        toolbarProgress.setVisibility(View.GONE);
        bar.addView(toolbarProgress, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView reload = toolbarButton("↻", "重新加载");
        reload.setTextSize(25);
        reload.setOnClickListener(v -> webView.reload());
        bar.addView(reload, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView menu = toolbarButton("⋮", "更多");
        menu.setTextSize(28);
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return bar;
    }

    private ScrollView buildConnectionPanel() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24), dp(46), dp(24), dp(32));
        scroll.addView(column, matchParent());

        TextView badge = new TextView(this);
        badge.setText("DSH");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(24);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setBackground(roundRect(Color.rgb(57, 100, 254), 22));
        column.addView(badge, new LinearLayout.LayoutParams(dp(84), dp(84)));

        TextView title = new TextView(this);
        title.setText("DSH Android");
        title.setTextColor(Color.rgb(23, 24, 26));
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = wrapContent();
        titleParams.topMargin = dp(22);
        column.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("DeepSeek Harness 的非官方移动客户端\n连接到电脑、服务器或 USB 转发的 Harness");
        subtitle.setTextColor(Color.rgb(98, 102, 109));
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams subtitleParams = matchWidthWrap();
        subtitleParams.topMargin = dp(10);
        column.addView(subtitle, subtitleParams);

        TextView label = new TextView(this);
        label.setText("Harness 地址");
        label.setTextColor(Color.rgb(23, 24, 26));
        label.setTextSize(14);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = matchWidthWrap();
        labelParams.topMargin = dp(34);
        column.addView(label, labelParams);

        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setTextSize(16);
        endpointInput.setTextColor(Color.rgb(23, 24, 26));
        endpointInput.setHint("http://127.0.0.1:3080");
        endpointInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setPadding(dp(14), 0, dp(14), 0);
        endpointInput.setBackground(roundRectStroke(Color.WHITE, Color.rgb(210, 213, 218), 13));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        inputParams.topMargin = dp(8);
        column.addView(endpointInput, inputParams);

        Button connect = new Button(this);
        connect.setText("连接 Harness");
        connect.setTextSize(16);
        connect.setTextColor(Color.WHITE);
        connect.setAllCaps(false);
        connect.setBackground(roundRect(Color.rgb(57, 100, 254), 13));
        connect.setOnClickListener(v -> connect(endpointInput.getText().toString(), false));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        connectParams.topMargin = dp(14);
        column.addView(connect, connectParams);

        TextView help = new TextView(this);
        help.setText("USB 测试：电脑运行 dsh web 后执行\nadb reverse tcp:3080 tcp:3080\n\n局域网：让 Harness 监听电脑局域网地址，并优先使用 HTTPS。API 密钥只保存在 Harness 服务端。");
        help.setTextColor(Color.rgb(98, 102, 109));
        help.setTextSize(13);
        help.setLineSpacing(0, 1.25f);
        help.setPadding(dp(14), dp(14), dp(14), dp(14));
        help.setBackground(roundRect(Color.rgb(238, 241, 247), 12));
        LinearLayout.LayoutParams helpParams = matchWidthWrap();
        helpParams.topMargin = dp(24);
        column.addView(help, helpParams);
        return scroll;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView buildWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.rgb(248, 248, 246));
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " DSH-Android/0.1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebViewClient(new HarnessWebViewClient());
        view.setWebChromeClient(new HarnessWebChromeClient());
        view.setDownloadListener(this::requestDownload);
        return view;
    }

    private LinearLayout buildErrorPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(28), dp(28), dp(28), dp(28));
        panel.setBackgroundColor(Color.rgb(248, 248, 246));

        TextView title = new TextView(this);
        title.setText("无法连接 Harness");
        title.setTextColor(Color.rgb(23, 24, 26));
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title, wrapContent());

        errorMessage = new TextView(this);
        errorMessage.setTextColor(Color.rgb(98, 102, 109));
        errorMessage.setTextSize(15);
        errorMessage.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = matchWidthWrap();
        messageParams.topMargin = dp(12);
        panel.addView(errorMessage, messageParams);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setBackground(roundRect(Color.rgb(57, 100, 254), 12));
        retry.setOnClickListener(v -> {
            errorPanel.setVisibility(View.GONE);
            if (endpoint != null) webView.loadUrl(endpoint.toString());
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(dp(180), dp(50));
        retryParams.topMargin = dp(22);
        panel.addView(retry, retryParams);

        Button settings = new Button(this);
        settings.setText("更换服务器");
        settings.setAllCaps(false);
        settings.setOnClickListener(v -> showConnection());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(180), dp(50));
        settingsParams.topMargin = dp(8);
        panel.addView(settings, settingsParams);
        return panel;
    }

    private void connect(String raw, boolean automatic) {
        final URI parsed;
        try {
            parsed = EndpointValidator.parse(raw);
        } catch (URISyntaxException error) {
            if (!automatic) endpointInput.setError(error.getReason());
            return;
        }
        if (!EndpointValidator.isSecureOrLoopback(parsed) && !automatic) {
            new AlertDialog.Builder(this)
                    .setTitle("连接未加密的服务器？")
                    .setMessage("HTTP 会让同一网络中的其他设备看到会话内容。仅在可信局域网中继续，正式使用建议配置 HTTPS。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("仍然连接", (dialog, which) -> beginConnect(parsed))
                    .show();
            return;
        }
        beginConnect(parsed);
    }

    private void beginConnect(URI parsed) {
        endpoint = parsed;
        endpointOrigin = EndpointValidator.origin(parsed);
        endpointInput.setText(parsed.toString());
        preferences.edit().putString(PREF_ENDPOINT, parsed.toString()).apply();
        errorPanel.setVisibility(View.GONE);
        connectionPanel.setVisibility(View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);
        toolbarTitle.setText(parsed.getHost());
        toolbarProgress.setVisibility(View.VISIBLE);
        webView.loadUrl(parsed.toString());
    }

    private void showConnection() {
        webView.stopLoading();
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
        connectionPanel.setVisibility(View.VISIBLE);
        endpointInput.requestFocus();
    }

    private void showError(String detail) {
        toolbarProgress.setVisibility(View.GONE);
        errorMessage.setText(detail + "\n\n请确认 Harness 正在运行、端口正确，且手机能访问该地址。");
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("重新加载");
        menu.getMenu().add("在浏览器中打开");
        menu.getMenu().add("复制服务器地址");
        menu.getMenu().add("清除站点数据");
        menu.getMenu().add("关于");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("重新加载")) webView.reload();
            else if (title.equals("在浏览器中打开")) openExternal(Uri.parse(webView.getUrl()));
            else if (title.equals("复制服务器地址")) copyEndpoint();
            else if (title.equals("清除站点数据")) confirmClearSiteData();
            else if (title.equals("关于")) showAbout();
            return true;
        });
        menu.show();
    }

    private void copyEndpoint() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Harness endpoint", endpoint == null ? "" : endpoint.toString()));
        Toast.makeText(this, "服务器地址已复制", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearSiteData() {
        new AlertDialog.Builder(this)
                .setTitle("清除站点数据？")
                .setMessage("将清除 WebView Cookie 和缓存。Harness 服务端保存的会话、插件与凭据不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清除", (dialog, which) -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteOrigin(endpointOrigin);
                    webView.clearCache(true);
                    webView.clearFormData();
                    webView.reload();
                })
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("DSH Android 0.1.0")
                .setMessage("DeepSeek Harness 的非官方社区 Android 客户端。\n\n基于 MIT 许可的 deepseek-ai/deepseek-harness 与 dataelement/dsh-desktop 的架构和界面约定开发。本应用不隶属于 DeepSeek AI 或 DataElement。")
                .setPositiveButton("确定", null)
                .show();
    }

    private void injectMobileStyles() {
        try {
            String css = readAsset("mobile.css");
            String script = "(function(){document.body.setAttribute('data-dsh-android','');"
                    + "var old=document.getElementById('dsh-android-mobile');if(old)old.remove();"
                    + "var s=document.createElement('style');s.id='dsh-android-mobile';s.textContent="
                    + JSONObject.quote(css) + ";document.head.appendChild(s);"
                    + "document.documentElement.style.setProperty('--dsh-android','1');})()";
            webView.evaluateJavascript(script, null);
        } catch (IOException error) {
            Toast.makeText(this, "移动样式加载失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream stream = getAssets().open(name);
             Scanner scanner = new Scanner(stream, "UTF-8").useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private boolean isSameOrigin(String url) {
        if (endpointOrigin == null) return false;
        try {
            return endpointOrigin.equals(EndpointValidator.origin(EndpointValidator.parse(url)));
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    private void openExternal(Uri uri) {
        if (uri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseFiles(WebChromeClient.FileChooserParams params, ValueCallback<Uri[]> callback) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        Intent intent;
        try {
            intent = params.createIntent();
        } catch (Exception ignored) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        try {
            startActivityForResult(intent, REQUEST_FILE);
        } catch (ActivityNotFoundException error) {
            fileCallback = null;
            callback.onReceiveValue(null);
            Toast.makeText(this, "没有可选择文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && data.getClipData() != null) {
            ClipData clip = data.getClipData();
            result = new Uri[clip.getItemCount()];
            for (int index = 0; index < clip.getItemCount(); index++) {
                result[index] = clip.getItemAt(index).getUri();
            }
        } else if (resultCode == RESULT_OK) {
            result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private void handleWebPermission(PermissionRequest request) {
        if (!isSameOrigin(request.getOrigin().toString())) {
            request.deny();
            return;
        }
        List<String> androidPermissions = new ArrayList<>();
        List<String> resources = Arrays.asList(request.getResources());
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            androidPermissions.add(Manifest.permission.CAMERA);
        }
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            androidPermissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (androidPermissions.isEmpty()) {
            request.grant(request.getResources());
        } else {
            pendingWebPermission = request;
            requestPermissions(androidPermissions.toArray(new String[0]), REQUEST_WEB_MEDIA);
        }
    }

    private void handleLocationPermission(String origin, GeolocationPermissions.Callback callback) {
        if (!isSameOrigin(origin)) {
            callback.invoke(origin, false, false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            callback.invoke(origin, true, false);
        } else {
            pendingLocationOrigin = origin;
            pendingLocationCallback = callback;
            String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}
                    : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(permissions, REQUEST_WEB_LOCATION);
        }
    }

    private void requestDownload(String url, String userAgent, String contentDisposition,
                                 String mimeType, long contentLength) {
        if (!isSameOrigin(url)) {
            openExternal(Uri.parse(url));
            return;
        }
        DownloadSpec spec = new DownloadSpec(url, userAgent, contentDisposition, mimeType);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = spec;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_DOWNLOAD_STORAGE);
            return;
        }
        enqueueDownload(spec, false);
    }

    private void enqueueDownload(DownloadSpec spec, boolean privateDestination) {
        String filename = URLUtil.guessFileName(spec.url, spec.contentDisposition, spec.mimeType);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(spec.url));
        request.setTitle(filename);
        request.setDescription("DSH Android");
        request.setMimeType(spec.mimeType);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.addRequestHeader("User-Agent", spec.userAgent);
        String cookies = CookieManager.getInstance().getCookie(spec.url);
        if (cookies != null && !cookies.isEmpty()) request.addRequestHeader("Cookie", cookies);
        if (privateDestination) {
            File directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) {
                Toast.makeText(this, "无法创建下载目录", Toast.LENGTH_LONG).show();
                return;
            }
            request.setDestinationUri(Uri.fromFile(new File(directory, filename)));
        } else {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        }
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        manager.enqueue(request);
        Toast.makeText(this, privateDestination ? "正在下载到应用目录" : "已加入下载队列", Toast.LENGTH_SHORT).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0;
        for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_WEB_MEDIA && pendingWebPermission != null) {
            if (granted) pendingWebPermission.grant(pendingWebPermission.getResources());
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        } else if (requestCode == REQUEST_WEB_LOCATION && pendingLocationCallback != null) {
            boolean locationGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            pendingLocationCallback.invoke(pendingLocationOrigin, locationGranted, false);
            pendingLocationCallback = null;
            pendingLocationOrigin = null;
        } else if (requestCode == REQUEST_DOWNLOAD_STORAGE && pendingDownload != null) {
            enqueueDownload(pendingDownload, !granted);
            pendingDownload = null;
        }
    }

    private void showHttpAuth(HttpAuthHandler handler, String host, String realm) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), 0, dp(24), 0);
        EditText username = new EditText(this);
        username.setHint("用户名");
        username.setSingleLine(true);
        EditText password = new EditText(this);
        password.setHint("密码");
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(username, matchWidthWrap());
        form.addView(password, matchWidthWrap());
        new AlertDialog.Builder(this)
                .setTitle("服务器认证")
                .setMessage(host + (realm == null || realm.isEmpty() ? "" : " · " + realm))
                .setView(form)
                .setNegativeButton("取消", (dialog, which) -> handler.cancel())
                .setPositiveButton("登录", (dialog, which) -> handler.proceed(
                        username.getText().toString(), password.getText().toString()))
                .setOnCancelListener(dialog -> handler.cancel())
                .show();
    }

    private void showFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;
        toolbar.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        addContentView(view, matchParent());
    }

    private void hideFullscreen() {
        if (fullscreenView == null) return;
        ViewGroup parent = (ViewGroup) fullscreenView.getParent();
        if (parent != null) parent.removeView(fullscreenView);
        fullscreenView = null;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    private final class HarnessWebViewClient extends WebViewClient {
        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            toolbarProgress.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            toolbarProgress.setVisibility(View.GONE);
            injectMobileStyles();
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("http") || scheme.equals("https")) {
                if (isSameOrigin(uri.toString())) return false;
                openExternal(uri);
                return true;
            }
            if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data")) return false;
            openExternal(uri);
            return true;
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showError("网络错误：" + error.getDescription());
            }
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                showError("服务器返回 HTTP " + response.getStatusCode());
            }
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();
            if (error.getUrl() != null && isSameOrigin(error.getUrl())) {
                showError("TLS 证书校验失败。为保护 API 密钥和会话内容，本应用不会忽略证书错误。");
            }
        }

        @Override public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            showHttpAuth(handler, host, realm);
        }
    }

    private final class HarnessWebChromeClient extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int progress) {
            toolbarProgress.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
        }

        @Override public void onReceivedTitle(WebView view, String title) {
            if (title != null && !title.trim().isEmpty() && !title.startsWith("http")) {
                toolbarTitle.setText(title);
            }
        }

        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            chooseFiles(params, callback);
            return true;
        }

        @Override public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> handleWebPermission(request));
        }

        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            handleLocationPermission(origin, callback);
        }

        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            showFullscreen(view, callback);
        }

        @Override public void onHideCustomView() {
            hideFullscreen();
        }

        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            WebView popup = new WebView(MainActivity.this);
            popup.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView ignored, WebResourceRequest request) {
                    openExternal(request.getUrl());
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }
    }

    private static final class DownloadSpec {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;

        DownloadSpec(String url, String userAgent, String contentDisposition, String mimeType) {
            this.url = url;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
        }
    }

    private TextView toolbarButton(String text, String description) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setContentDescription(description);
        button.setTextColor(Color.rgb(42, 45, 50));
        button.setGravity(Gravity.CENTER);
        button.setBackground(selectableBackground());
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private android.graphics.drawable.Drawable selectableBackground() {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        return getDrawable(value.resourceId);
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundRectStroke(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = roundRect(color, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidthWrap(float weight) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private static boolean hasNetwork(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        return network != null && network.isConnected();
    }
}
