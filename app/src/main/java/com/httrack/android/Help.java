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

import android.app.Activity;
import android.util.Log;

/**
 * Contextual help: which bundled doc page documents which screen, the way WinHTTrack sends each
 * option tab to its own help page.
 */
final class Help {
  // Sections of the shared GUI guide. "#droid/<id>" is guide.js's scheme: it picks the Android
  // variant, then scrolls to <id>. Without the "droid/" the guide guesses from the user agent.
  static final String PAGE_START = "guide.html#droid/step-start";
  static final String PAGE_PROJECT_NAME = "guide.html#droid/step-project";
  static final String PAGE_PROJECT_SETUP = "guide.html#droid/step-address";
  static final String PAGE_OPTIONS = "guide.html#droid/options";
  static final String PAGE_MIRROR_PROGRESS = "guide.html#droid/step-run";
  static final String PAGE_FINISHED = "guide.html#droid/step-done";

  private Help() {
  }

  /**
   * Doc page for the wizard pane at {@code paneId}, one of HTTrackActivity's LAYOUT_* positions.
   * An unknown pane gets the guide's first step.
   */
  static String pageForPane(final int paneId) {
    switch (paneId) {
    case HTTrackActivity.LAYOUT_PROJECT_NAME:
      return PAGE_PROJECT_NAME;
    case HTTrackActivity.LAYOUT_PROJECT_SETUP:
      return PAGE_PROJECT_SETUP;
    case HTTrackActivity.LAYOUT_MIRROR_PROGRESS:
      return PAGE_MIRROR_PROGRESS;
    case HTTrackActivity.LAYOUT_FINISHED:
      return PAGE_FINISHED;
    default:
      return PAGE_START;
    }
  }

  /**
   * Doc page for option tab {@code tabClass}, or the options overview when it declares none or no
   * tab is open.
   */
  static String pageForTab(final Class<?> tabClass) {
    if (tabClass == null) {
      return PAGE_OPTIONS;
    }
    final OptionsActivity.HelpPage page = OptionsActivity.HelpPage.class.cast(tabClass
        .getAnnotation(OptionsActivity.HelpPage.class));
    return page != null ? page.value() : PAGE_OPTIONS;
  }

  /**
   * Open doc page {@code page} over the app.
   *
   * @param activity
   *          the caller
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
    final int hash = page.indexOf('#');
    final String name = hash == -1 ? page : page.substring(0, hash);
    final String fragment = hash == -1 ? null : page.substring(hash);
    Loopback.open(activity, resourceRoot, new File(new File(resourceRoot, "html"), name), fragment,
        true);
  }
}
