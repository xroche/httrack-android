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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.app.ActionBar;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.FragmentActivity;

import com.httrack.android.jni.HTTrackLib;

/**
 * The user passes a login form or a CAPTCHA here, and the crawl then reuses this browser's
 * cookies and its exact User-Agent.
 *
 * Every host the user reaches is queried on the way out, because WebView hands back cookies per
 * host and a login usually bounces through more than one.
 */
public class BrowserLoginActivity extends FragmentActivity {
  /** The address to open, which {@link BrowserLoginPolicy#startUrl} has already vetted. */
  static final String EXTRA_URL = "com.httrack.android.loginUrl";

  /** Where to write the login bundle the engine will read, the crawl's --bundle-dir. */
  static final String EXTRA_BUNDLE_DIR = "com.httrack.android.bundleDir";

  /** The hosts the crawl starts from, which are asked for cookies along with the visited ones. */
  static final String EXTRA_CRAWL_HOSTS = "com.httrack.android.crawlHosts";

  /** Result: the start URL the engine wrote, which names the bundle it wrote beside it. */
  static final String EXTRA_BUNDLE_URL = "com.httrack.android.bundleUrl";

  /** Result: the user pressed Forget, so the caller drops the bundle its addresses name. */
  static final String EXTRA_FORGOTTEN = "com.httrack.android.forgotten";
  static final String EXTRA_COOKIE_COUNT = "com.httrack.android.cookieCount";
  static final String EXTRA_HOST_COUNT = "com.httrack.android.hostCount";
  static final String EXTRA_DROPPED_COUNT = "com.httrack.android.droppedCount";

  private WebView web;

  private File bundleDir;

  /** The address this window was opened for, which is the one the crawl will start from. */
  private String crawlUrl;

  /** Hosts reached, in visit order, so the login's first host leads the file. */
  private final Set<String> hosts = new LinkedHashSet<String>();

  /**
   * Hosts the crawl will start from. Asked for cookies too, because getCookie answers for any
   * host, and a crawl host that shares a parent domain with a visited one inherits its cookies.
   */
  private final Set<String> crawlHosts = new LinkedHashSet<String>();

  /** What the engine adds to a bundle's name to find its file. */
  private static final String BUNDLE_EXTENSION = ".hts";

  /** Did the user press Forget? The caller then drops the bundle names, however the window ends. */
  private boolean forgotten;

  private static final String STATE_HOSTS = "hosts";

  private static final String STATE_FORGOTTEN = "forgotten";

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_browser_login);

    final Bundle extras = getIntent().getExtras();
    final String url = extras != null ? extras.getString(EXTRA_URL) : null;
    bundleDir = extras != null ? File.class.cast(extras.get(EXTRA_BUNDLE_DIR)) : null;
    final List<String> starts =
        extras != null ? extras.getStringArrayList(EXTRA_CRAWL_HOSTS) : null;
    if (starts != null) {
      crawlHosts.addAll(starts);
    }
    if (url == null || bundleDir == null) {
      Log.w(getClass().getSimpleName(), "no address to log in to");
      finish();
      return;
    }

    crawlUrl = url;
    web = WebView.class.cast(findViewById(R.id.webLogin));
    final WebSettings settings = web.getSettings();
    // A login form usually needs scripts, and often its storage too.
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    // A page here has no business reading our storage, and the mirrors are in it.
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    // A no-op from API 26 on, but it still keeps a password out of the form store below that.
    settings.setSaveFormData(false);
    final CookieManager cookies = CookieManager.getInstance();
    cookies.setAcceptCookie(true);
    // Single sign-on sets its cookies from a third-party frame, so the login needs them.
    cookies.setAcceptThirdPartyCookies(web, true);
    web.setWebViewClient(new LoginClient());
    getOnBackPressedDispatcher().addCallback(this, backCallback);
    if (savedInstanceState == null) {
      web.loadUrl(url);
      return;
    }
    // Recreated: reloading the first address would throw away a login half way through.
    web.restoreState(savedInstanceState);
    final List<String> seen = savedInstanceState.getStringArrayList(STATE_HOSTS);
    if (seen != null) {
      hosts.addAll(seen);
    }
    forgotten = savedInstanceState.getBoolean(STATE_FORGOTTEN, false);
  }

  @Override
  protected void onSaveInstanceState(final Bundle out) {
    super.onSaveInstanceState(out);
    if (web != null) {
      web.saveState(out);
    }
    out.putStringArrayList(STATE_HOSTS, new ArrayList<String>(hosts));
    out.putBoolean(STATE_FORGOTTEN, forgotten);
  }

  @Override
  protected void onDestroy() {
    if (web != null) {
      // destroy() is only safe off the hierarchy, and leaks the window if it is still attached.
      final ViewGroup parent = ViewGroup.class.cast(web.getParent());
      if (parent != null) {
        parent.removeView(web);
      }
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }

  /** Back walks the login's own history first, so a wrong turn does not leave the screen. */
  private final OnBackPressedCallback backCallback = new OnBackPressedCallback(true) {
    @Override
    public void handleOnBackPressed() {
      if (web != null && web.canGoBack()) {
        web.goBack();
      } else {
        reportForgotten();
        finish();
      }
    }
  };

  /**
   * Records where the user went and keeps the window on https. There is deliberately no
   * onReceivedSslError, because its default refuses a certificate we could not verify.
   */
  private final class LoginClient extends WebViewClient {
    @Override
    public void onPageStarted(final WebView view, final String url, final Bitmap favicon) {
      final String host = BrowserLoginPolicy.hostOf(url);
      if (host != null) {
        hosts.add(host);
      }
      // The user is about to type a password, so name the site they are typing it into.
      final ActionBar bar = getActionBar();
      if (bar != null) {
        bar.setSubtitle(host);
      }
    }

    @Override
    public boolean shouldOverrideUrlLoading(final WebView view, final WebResourceRequest request) {
      final String url = request.getUrl().toString();
      if (BrowserLoginPolicy.hostOf(url) != null) {
        return false;
      }
      // No cleartext permission is declared, so http stays blank.
      Toast.makeText(BrowserLoginActivity.this,
          getString(R.string.browser_login_https_only), Toast.LENGTH_LONG).show();
      return true;
    }
  }

  @Override
  public boolean onCreateOptionsMenu(final Menu menu) {
    getMenuInflater().inflate(R.menu.browser_login, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(final MenuItem item) {
    if (item.getItemId() == R.id.action_login_forget) {
      forget();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /**
   * Drop the saved session: the browser's cookies and every bundle the engine would have read.
   *
   * This signs the user out of every site in this app's browser, not just this project's. That
   * is deliberate. CookieManager has no per-site removal, and nothing enumerates what a
   * narrower pass would miss: getCookie answers for one path, so a cookie on a deeper path is
   * invisible to it, a nameless cookie has no name to overwrite, and a session held by an
   * iframe sign-on sits on a host onPageStarted never reported. A narrower Forget was measured
   * and it left all three signed in, which is worse than reaching too far, because the user
   * presses this to change accounts. The store is per app, so splitting it needs a per-project
   * WebView data directory, which means a separate process.
   */
  private void forget() {
    if (bundleDir == null) {
      return;
    }
    CookieManager.getInstance().removeAllCookies(null);
    CookieManager.getInstance().flush();
    hosts.clear();
    final File[] saved = bundleDir.listFiles();
    if (saved != null) {
      for (final File file : saved) {
        if (!file.delete()) {
          Log.w(getClass().getSimpleName(), "could not delete " + file);
        }
      }
    }
    /* A start URL naming a bundle that is gone stops the crawl at startup, by design, so the
       name has to leave the address field with the file. */
    forgotten = true;
    reportForgotten();
    if (web != null) {
      web.clearCache(true);
      web.reload();
    }
    Toast.makeText(this, getString(R.string.browser_login_forgotten), Toast.LENGTH_SHORT).show();
  }

  /** "Cancel" */
  public void onClickLoginCancel(final View view) {
    reportForgotten();
    finish();
  }

  /**
   * Delete every bundle of this project but KEPT, the one just written. A project keeps one
   * login: two bundles covering the same site with different cookie values, or with the user
   * agent of two WebView versions, make the engine refuse to start the crawl at all.
   *
   * @param kept
   *          the name the engine gave the bundle it has just written
   */
  private void dropOtherBundles(final String kept) {
    final File[] saved = bundleDir.listFiles();

    if (kept == null || saved == null) {
      return;
    }
    for (final File file : saved) {
      if (!file.getName().equals(kept + BUNDLE_EXTENSION)) {
        if (!file.delete()) {
          Log.w(getClass().getSimpleName(), "could not delete " + file);
        }
      }
    }
  }

  /** Tell the caller whether Forget ran, because Cancel would otherwise bury it. */
  private void reportForgotten() {
    if (!forgotten) {
      setResult(RESULT_CANCELED);
      return;
    }
    final Intent data = new Intent();
    data.putExtra(EXTRA_FORGOTTEN, true);
    setResult(RESULT_OK, data);
  }

  /** "Use this login" */
  public void onClickLoginDone(final View view) {
    CookieManager.getInstance().flush();
    final Set<String> asked = new LinkedHashSet<String>(hosts);
    asked.addAll(crawlHosts);
    final Map<String, String> byHost = new LinkedHashMap<String, String>();
    for (final String host : asked) {
      final String header = CookieManager.getInstance()
          .getCookie(BrowserLoginPolicy.SCHEME + "://" + host + "/");
      if (header != null && header.length() != 0) {
        byHost.put(host, header);
      }
    }
    final BrowserLoginCookies.Result result = BrowserLoginCookies.file(byHost);
    if (result.cookies == 0) {
      Toast.makeText(this, getString(R.string.browser_login_no_cookies), Toast.LENGTH_LONG).show();
      return;
    }
    if (!bundleDir.isDirectory() && !bundleDir.mkdirs()) {
      Toast.makeText(this, getString(R.string.browser_login_write_failed), Toast.LENGTH_LONG)
          .show();
      return;
    }
    if (!HTTrackLib.loadLibraries()) {
      Toast.makeText(this, getString(R.string.browser_login_write_failed), Toast.LENGTH_LONG)
          .show();
      return;
    }
    /* The engine writes the file: it owns the bundle format, the name, the scope, the caps and
       the address rules, and it reads the result back through its own loader before keeping it.
       It is given the address this window was opened for, NOT the one the login ended on. That
       address is what the crawl starts from, so the bundle is named and scoped for the site
       being copied, and a login that ends on an identity provider adds no second crawl root. */
    final HTTrackLib.LoginBundle bundle = HTTrackLib.writeLoginBundle(result.text,
        bundleDir.getAbsolutePath(), web.getSettings().getUserAgentString(), crawlUrl);
    if (bundle == null) {
      Toast.makeText(this, getString(R.string.browser_login_no_bundle), Toast.LENGTH_LONG).show();
      return;
    }
    dropOtherBundles(BrowserLoginPolicy.bundleNameOf(bundle.url));
    final Intent data = new Intent();
    data.putExtra(EXTRA_BUNDLE_URL, bundle.url);
    // The engine's own counts, because it drops a cookie outside the bundle's scope.
    data.putExtra(EXTRA_COOKIE_COUNT, bundle.cookies);
    data.putExtra(EXTRA_HOST_COUNT, bundle.hosts);
    // Ours, and only the over-long ones, which is what the message the user reads says.
    data.putExtra(EXTRA_DROPPED_COUNT, result.dropped);
    setResult(RESULT_OK, data);
    finish();
  }
}
