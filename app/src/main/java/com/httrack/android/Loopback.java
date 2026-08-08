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
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabsIntent;

/** Opens a local file in a browser, served over the loopback mirror server. */
final class Loopback {
  private Loopback() {
  }

  /**
   * Open {@code file} from the server rooted at {@code root}. A Custom Tab floats over the app,
   * which suits a doc page; a whole mirrored site belongs in the browser.
   *
   * @param activity
   *          the caller, used to launch the browser and report failures
   * @param root
   *          the served tree; {@code file} must be inside it
   * @param file
   *          the file to open; ignored when it does not exist
   * @param fragment
   *          URL fragment to append, leading "#" included, or null
   * @param customTab
   *          open over the app rather than switching to the browser
   */
  static void open(final Activity activity, final File root, final File file,
      final String fragment, final boolean customTab) {
    if (root == null || file == null || !file.exists()) {
      Log.w(Loopback.class.getSimpleName(), "nothing to open: " + file);
      return;
    }
    final MirrorServer server;
    try {
      server = MirrorServer.forRoot(root);
    } catch (final IOException e) {
      Log.w(Loopback.class.getSimpleName(), "mirror server failed to start", e);
      Toast.makeText(activity, "Could not start the local mirror server", Toast.LENGTH_SHORT)
          .show();
      return;
    }
    try {
      final String url = url(server.getBaseUrl(), root, file, fragment);
      if (url == null) {
        Toast.makeText(activity, "Cannot browse a file outside the served folder",
            Toast.LENGTH_SHORT).show();
        return;
      }
      final Uri uri = Uri.parse(url);
      if (customTab) {
        new CustomTabsIntent.Builder().build().launchUrl(activity, uri);
      } else {
        activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
      }
    } catch (final Exception e) {
      Log.w(Loopback.class.getSimpleName(), "could not open " + file, e);
      Toast.makeText(activity, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
    }
  }

  /**
   * Loopback URL serving {@code file}, or null if it is not inside {@code root}.
   *
   * @param baseUrl
   *          the running server's base URL
   * @param root
   *          the served tree
   * @param file
   *          the file to link to
   * @param fragment
   *          URL fragment to append, leading "#" included, or null
   * @return the URL to open, or null if the file is outside root
   */
  static String url(final String baseUrl, final File root, final File file, final String fragment)
      throws IOException {
    final String relative = MirrorServer.relativeUrlPath(root, file);
    if (relative == null) {
      return null;
    }
    // The fragment stays raw: relativeUrlPath percent-encodes path segments only.
    return baseUrl + "/" + relative + (fragment == null ? "" : fragment);
  }
}
