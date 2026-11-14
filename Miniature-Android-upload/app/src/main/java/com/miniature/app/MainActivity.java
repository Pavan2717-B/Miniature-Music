package com.miniature.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILES = 1, REQ_TREE = 2;

    private WebView web;
    private ValueCallback<Uri[]> chooserCb;

    // Files found while scanning a picked folder, served to the page at https://HOST/saf/<id>
    private final Map<Integer, Uri> safUris = new ConcurrentHashMap<>();
    private final Map<Integer, String> safMimes = new ConcurrentHashMap<>();
    private final AtomicInteger safNext = new AtomicInteger();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        try { startForegroundService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) {}

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/saf/", new WebViewAssetLoader.PathHandler() {
                    @Override public WebResourceResponse handle(String path) {
                        try {
                            int id = Integer.parseInt(path);
                            Uri u = safUris.get(id);
                            if (u == null) return null;
                            InputStream in = getContentResolver().openInputStream(u);
                            String m = safMimes.get(id);
                            return new WebResourceResponse(m == null ? "application/octet-stream" : m, null, in);
                        } catch (Exception e) { return null; }
                    }
                })
                .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF03060D);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                try {
                    if (url.startsWith("intent:")) {
                        startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME));
                    } else if (!HOST.equals(r.getUrl().getHost())) {
                        startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                    } else return false;
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Couldn't open that", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooserCb != null) chooserCb.onReceiveValue(null);
                chooserCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILES);
                } catch (Exception e) {
                    chooserCb = null;
                    return false;
                }
                return true;
            }
        });

        // Blob downloads (e.g. "Export backup") -> save to Downloads
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (!url.startsWith("blob:")) return;
            String name = URLUtil.guessFileName(url, cd, mime);
            if (name == null || name.startsWith("downloadfile")) name = "miniature-backup.json";
            String js = "(function(){var x=new XMLHttpRequest();x.open('GET'," + JSONObject.quote(url) + ",true);"
                    + "x.responseType='blob';x.onload=function(){var r=new FileReader();r.onloadend=function(){"
                    + "AndroidBridge.saveFile(" + JSONObject.quote(name) + "," + JSONObject.quote(mime == null ? "application/octet-stream" : mime)
                    + ",String(r.result).split(',')[1])};r.readAsDataURL(x.response)};x.send()})()";
            web.evaluateJavascript(js, null);
        });

        web.loadUrl("https://" + HOST + "/index.html");
    }

    /** Methods callable from the page as window.AndroidBridge.* */
    private class Bridge {
        @JavascriptInterface public void pickFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_TREE);
            });
        }

        @JavascriptInterface public void saveFile(String name, String mime, String b64) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    o.write(Base64.decode(b64, Base64.DEFAULT));
                }
                toast("Saved to Downloads: " + name);
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
        }
    }

    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILES) {
            if (chooserCb != null) {
                chooserCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                chooserCb = null;
            }
        } else if (req == REQ_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            toast("Scanning folder…");
            new Thread(() -> scanTree(tree)).start();
        }
    }

    private void scanTree(Uri tree) {
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            String name = rootId;
            try (Cursor c = getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            }
            JSONArray arr = new JSONArray();
            walk(tree, rootId, arr, 0);
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("files", arr);
            final String js = "window.__onFolder&&window.__onFolder(JSON.parse(" + JSONObject.quote(o.toString()) + "))";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception e) {
            toast("Couldn't read folder: " + e.getMessage());
        }
    }

    private void walk(Uri tree, String docId, JSONArray out, int depth) throws Exception {
        if (depth > 10) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        java.util.ArrayList<String> subdirs = new java.util.ArrayList<>();
        try (Cursor c = getContentResolver().query(kids, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0), nm = c.getString(1), mime = c.getString(2);
                long mod = c.isNull(3) ? 0 : c.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) { subdirs.add(id); continue; }
                boolean audio = (mime != null && mime.startsWith("audio/"))
                        || (nm != null && nm.toLowerCase().matches(".*\\.(mp3|m4a|aac|wav|ogg|oga|opus|flac)$"));
                if (!audio) continue;
                int n = safNext.incrementAndGet();
                safUris.put(n, DocumentsContract.buildDocumentUriUsingTree(tree, id));
                String m = (mime != null && mime.startsWith("audio/")) ? mime : "audio/mpeg";
                safMimes.put(n, m);
                JSONObject f = new JSONObject();
                f.put("url", "https://" + HOST + "/saf/" + n);
                f.put("name", nm);
                f.put("type", m);
                f.put("mod", mod);
                out.put(f);
            }
        }
        for (String d : subdirs) walk(tree, d, out, depth + 1);
    }

    @Override public void onBackPressed() { moveTaskToBack(true); } // keep music playing
}
