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

import android.os.Bundle;
import android.os.Parcelable;

/**
 * What the options screen saves across a recreation, kept out of OptionsActivity so both
 * directions run over seams: production stores into a Bundle, the tests into a plain map.
 */
public final class OptionsInstanceState {
  /** No tab is open: the menu is showing. */
  public static final int NO_PANE = -1;

  private OptionsInstanceState() {
  }

  /** The bundle slots the state occupies. **/
  public interface Store {
    void putInt(final String key, final int value);

    int getInt(final String key, final int defaultValue);

    void putParcelable(final String key, final Parcelable value);

    Parcelable getParcelable(final String key);
  }

  /** The options screen the state is taken from and given back to. **/
  public interface Screen {
    /** Flush the visible tab's widgets into the map, which is what gets saved. */
    void flushVisibleTab();

    Parcelable serializeMap();

    void unserializeMap(final Parcelable map);

    /** Index in tabClasses of the open tab, NO_PANE on the menu. */
    int visiblePane();

    void openPane(final int index);
  }

  /** Store over the real thing. **/
  public static class BundleStore implements Store {
    private final Bundle bundle;

    public BundleStore(final Bundle bundle) {
      this.bundle = bundle;
    }

    @Override
    public void putInt(final String key, final int value) {
      bundle.putInt(key, value);
    }

    @Override
    public int getInt(final String key, final int defaultValue) {
      return bundle.getInt(key, defaultValue);
    }

    @Override
    public void putParcelable(final String key, final Parcelable value) {
      bundle.putParcelable(key, value);
    }

    @Override
    public Parcelable getParcelable(final String key) {
      return bundle.getParcelable(key);
    }
  }

  /** Save what it takes to re-open SCREEN as the user left it. **/
  public static void save(final Screen screen, final Store store,
      final int versionCode) {
    // Edits on the visible tab only reach the map when that tab is left.
    screen.flushVisibleTab();

    store.putInt(HTTrackActivity.VERSION_CODE_NAME, versionCode);
    // The live map, as HTTrackActivity does; safe only because the restoring mapper is a new one.
    store.putParcelable(HTTrackActivity.MAP_NAME, screen.serializeMap());
    store.putInt(HTTrackActivity.PANE_NAME, screen.visiblePane());
  }

  /** Restore SCREEN from STORE; false when the bundle held nothing usable. **/
  public static boolean restore(final Screen screen, final Store store,
      final int versionCode) {
    // Another build renumbers R.id, so its map keys and pane index name other fields and tabs.
    if (store.getInt(HTTrackActivity.VERSION_CODE_NAME, 0) != versionCode) {
      return false;
    }

    // Without a map, keep the one onCreate took from the intent.
    final Parcelable map = store.getParcelable(HTTrackActivity.MAP_NAME);
    if (map == null) {
      return false;
    }

    // The map first: opening a tab loads its fields from it.
    screen.unserializeMap(map);
    final int pane = store.getInt(HTTrackActivity.PANE_NAME, NO_PANE);
    if (pane != NO_PANE) {
      screen.openPane(pane);
    }
    return true;
  }
}
