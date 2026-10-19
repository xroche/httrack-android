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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
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

  /** Where to write the cookies.txt the engine will read. */
  static final String EXTRA_COOKIES_FILE = "com.httrack.android.cookiesFile";

  /** Results: the browser's own User-Agent, and what the file ended up holding. */
  static final String EXTRA_USER_AGENT = "com.httrack.android.userAgent";
  static final String EXTRA_COOKIE_COUNT = "com.httrack.android.cookieCount";
  static final String EXTRA_HOST_COUNT = "com.httrack.android.hostCount";
  static final String EXTRA_DROPPED_COUNT = "com.httrack.android.droppedCount";

  private static final Charset UTF8 = Charset.forName("UTF-8");

  private WebView web;

  private File cookiesFile;

  /** Hosts reached, in visit order, so the login's first host leads the file. */
  private final Set<String> hosts = new LinkedHashSet<String>();

  private static final String STATE_HOSTS = "hosts";

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_browser_login);

    final Bundle extras = getIntent().getExtras();
    final String url = extras != null ? extras.getString(EXTRA_URL) : null;
    cookiesFile = extras != null ? File.class.cast(extras.get(EXTRA_COOKIES_FILE)) : null;
    if (url == null || cookiesFile == null) {
      Log.w(getClass().getSimpleName(), "no address to log in to");
      finish();
      return;
    }

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
  }

  @Override
  protected void onSaveInstanceState(final Bundle out) {
    super.onSaveInstanceState(out);
    if (web != null) {
      web.saveState(out);
    }
    out.putStringArrayList(STATE_HOSTS, new ArrayList<String>(hosts));
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
        setResult(RESULT_CANCELED);
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

  /** Drop the saved session: the browser's cookies and the file the engine would have read. */
  private void forget() {
    if (cookiesFile == null) {
      return;
    }
    CookieManager.getInstance().removeAllCookies(null);
    CookieManager.getInstance().flush();
    hosts.clear();
    if (cookiesFile.exists() && !cookiesFile.delete()) {
      Log.w(getClass().getSimpleName(), "could not delete " + cookiesFile);
    }
    if (web != null) {
      web.clearCache(true);
      web.reload();
    }
    Toast.makeText(this, getString(R.string.browser_login_forgotten), Toast.LENGTH_SHORT).show();
  }

  /** "Cancel" */
  public void onClickLoginCancel(final View view) {
    setResult(RESULT_CANCELED);
    finish();
  }

  /** "Use this login" */
  public void onClickLoginDone(final View view) {
    CookieManager.getInstance().flush();
    final Map<String, String> byHost = new LinkedHashMap<String, String>();
    for (final String host : hosts) {
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
    if (!writeOwnerOnly(cookiesFile, result.text)) {
      Toast.makeText(this, getString(R.string.browser_login_write_failed), Toast.LENGTH_LONG)
          .show();
      return;
    }
    final Intent data = new Intent();
    data.putExtra(EXTRA_USER_AGENT, web.getSettings().getUserAgentString());
    data.putExtra(EXTRA_COOKIE_COUNT, result.cookies);
    data.putExtra(EXTRA_HOST_COUNT, result.hosts);
    data.putExtra(EXTRA_DROPPED_COUNT, result.dropped);
    setResult(RESULT_OK, data);
    finish();
  }

  /**
   * Write TEXT to FILE, readable by this app alone.
   *
   * The file holds a live session, so it writes a .new beside it and renames, because a write
   * that failed halfway would leave the previous login truncated and the crawl with nothing.
   *
   * @return true when FILE now holds TEXT
   */
  static boolean writeOwnerOnly(final File file, final String text) {
    final File dir = file.getParentFile();
    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
      return false;
    }
    final File staged = new File(file.getPath() + ".new");
    try (final OutputStream out = new FileOutputStream(staged)) {
      out.write(text.getBytes(UTF8));
    } catch (final IOException e) {
      Log.w(BrowserLoginActivity.class.getSimpleName(), "cannot write " + staged, e);
      staged.delete();
      return false;
    }
    // Before the rename, so the name the engine reads is never briefly world-readable. The
    // second argument is ownerOnly, so the pair clears everyone then grants the owner back.
    staged.setReadable(false, false);
    staged.setWritable(false, false);
    staged.setReadable(true, true);
    staged.setWritable(true, true);
    if (!staged.renameTo(file)) {
      Log.w(BrowserLoginActivity.class.getSimpleName(), "cannot rename onto " + file);
      staged.delete();
      return false;
    }
    return true;
  }
}
