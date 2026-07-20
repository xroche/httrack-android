/*
HTTrack Android Java Interface.

HTTrack Website Copier, Offline Browser for Windows and Unix
Copyright (C) Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.httrack.android;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.FragmentActivity;

/**
 * In-app viewer for locally mirrored pages and bundled help. Since Android 10 the
 * mirror lives in the app-private external dir, which no other app can read, so
 * handing an {@code index.html} to an external browser fails. This loads the
 * {@code file://} page inside HTTrack's own process, the only context that can
 * read it; relative sub-resources resolve because they are read directly rather
 * than granted one-by-one to another app. Remote (http/https) links are opened
 * externally. Launched with {@link #EXTRA_URL}.
 */
public class BrowseActivity extends FragmentActivity {
  /** Extra: the {@code file://} URL of the local page to open. */
  public static final String EXTRA_URL = "com.httrack.android.browseUrl";

  private WebView webView;

  /**
   * Whether a URL scheme renders inside the WebView (local content) rather than
   * being handed to an external browser. Only local/inline schemes stay in-app;
   * anything remote (http, https, content, ...) leaves the app.
   */
  static boolean isLocalScheme(final String scheme) {
    return "file".equals(scheme) || "data".equals(scheme)
        || "about".equals(scheme);
  }

  @Override
  @SuppressLint("SetJavaScriptEnabled") // mirrored pages need JS; cross-file access stays off (below)
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_browse);
    webView = findViewById(R.id.webView);

    final WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(true); // default false on API 30+; required to load file://
    settings.setAllowContentAccess(false);
    // Deny a mirrored script reading OTHER local files via file:// XHR.
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setBuiltInZoomControls(true);
    settings.setDisplayZoomControls(false);
    settings.setUseWideViewPort(true);
    settings.setLoadWithOverviewMode(true);

    webView.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(final WebView view,
          final WebResourceRequest request) {
        final Uri uri = request.getUrl();
        // Keep local navigation in the WebView; send remote links to a real browser.
        if (isLocalScheme(uri.getScheme())) {
          return false;
        }
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (final Exception e) {
          Log.d(getClass().getSimpleName(), "no external handler for " + uri, e);
        }
        return true;
      }
    });

    // Back walks WebView history first, then leaves; via the dispatcher for
    // predictive-back consistency with the other activities.
    getOnBackPressedDispatcher().addCallback(this,
        new OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
            if (webView.canGoBack()) {
              webView.goBack();
            } else {
              setEnabled(false);
              getOnBackPressedDispatcher().onBackPressed();
            }
          }
        });

    if (savedInstanceState == null) {
      final String url = getIntent().getStringExtra(EXTRA_URL);
      if (url != null) {
        webView.loadUrl(url);
      } else {
        finish();
      }
    }
  }

  @Override
  protected void onSaveInstanceState(final Bundle outState) {
    super.onSaveInstanceState(outState);
    webView.saveState(outState);
  }

  @Override
  protected void onRestoreInstanceState(final Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);
    webView.restoreState(savedInstanceState);
  }
}
