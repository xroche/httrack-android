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
import java.io.IOException;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabsIntent;

/**
 * Contextual help: opens the bundled doc page matching the screen the user asked from, the way
 * WinHTTrack sends each option tab to its own step9_optN.html.
 */
final class Help {
  // Pages the wizard panes ask for. A bundle without the anchors just opens at the top of the page.
  static final String PAGE_START = "android.html#start";
  static final String PAGE_PROJECT_NAME = "android.html#name-the-project";
  static final String PAGE_PROJECT_SETUP = "android.html#enter-the-address";
  static final String PAGE_OPTIONS = "android.html#options";
  static final String PAGE_MIRROR_PROGRESS = "android.html#run-the-mirror";
  static final String PAGE_FINISHED = "android.html#browse-the-result";

  private Help() {
  }

  /**
   * Open bundled doc page {@code page} (a filename, optionally with a "#anchor") in a Custom Tab,
   * served over the loopback mirror server rooted at the resources cache. A Custom Tab keeps the
   * user one back tap from the screen they asked from, and renders these desktop-width pages
   * better than a WebView we would have to own.
   *
   * @param activity
   *          the caller, used to launch the tab and report failures
   * @param resourceRoot
   *          the extracted resources cache, parent of the "html" doc directory
   * @param page
   *          doc page name relative to "html", with an optional URL fragment
   */
  static void show(final Activity activity, final File resourceRoot, final String page) {
    if (resourceRoot == null) {
      Log.w(Help.class.getSimpleName(), "no resources bundle, cannot show help");
      return;
    }
    final int fragment = page.indexOf('#');
    final String name = fragment == -1 ? page : page.substring(0, fragment);
    final File file = new File(new File(resourceRoot, "html"), name);
    if (!file.exists()) {
      Log.w(Help.class.getSimpleName(), "no such help page: " + name);
      return;
    }
    try {
      final MirrorServer server = MirrorServer.forRoot(resourceRoot);
      final String url = url(server.getBaseUrl(), resourceRoot, page);
      if (url == null) {
        return;
      }
      new CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url));
    } catch (final Exception e) {
      Log.w(Help.class.getSimpleName(), "could not open help", e);
      Toast.makeText(activity, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
    }
  }

  /**
   * Loopback URL serving doc page {@code page} from {@code resourceRoot}, or null if it resolves
   * outside the bundle.
   *
   * @param baseUrl
   *          the running server's base URL
   * @param resourceRoot
   *          the extracted resources cache, the server's root
   * @param page
   *          doc page name relative to "html", with an optional URL fragment
   * @return the URL to open, or null if the page is not inside the bundle
   */
  static String url(final String baseUrl, final File resourceRoot, final String page)
      throws IOException {
    final int fragment = page.indexOf('#');
    final String name = fragment == -1 ? page : page.substring(0, fragment);
    final String relative = MirrorServer.relativeUrlPath(resourceRoot,
        new File(new File(resourceRoot, "html"), name));
    if (relative == null) {
      return null;
    }
    // The fragment is appended raw: relativeUrlPath percent-encodes path segments only.
    return baseUrl + "/" + relative + (fragment == -1 ? "" : page.substring(fragment));
  }
}
