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

import java.util.ArrayList;
import java.util.List;

import com.httrack.android.jni.HTTrackLib;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;
import android.widget.TextView;

/**
 * The suggestions behind the Accept headers box. Each row stays free text and an empty one means
 * automatic, so a suggestion inserts the value a browser sends and never its own label.
 */
public final class AcceptPresets {
  private AcceptPresets() {
  }

  /** Index of a row's winprofile.ini key in {@link #ROWS}. */
  static final int KEY = 0;

  /** Index of a row's engine kind word, empty for the default row. */
  static final int KIND = 1;

  /**
   * The rows, in the engine's display order. A kind is one of the Sec-Fetch-Dest words
   * hts_accept_preset() takes, and the keys are the ones winprofile-keys.tsv states.
   */
  static final String ROWS[][] = { { "AcceptDefault", "" },
      { "AcceptDocument", "document" }, { "AcceptIframe", "iframe" },
      { "AcceptFrame", "frame" }, { "AcceptImage", "image" },
      { "AcceptStyle", "style" }, { "AcceptScript", "script" },
      { "AcceptFont", "font" }, { "AcceptAudio", "audio" },
      { "AcceptVideo", "video" }, { "AcceptObject", "object" },
      { "AcceptEmbed", "embed" } };

  /** Browsers the engine holds a table for, with the label each one's value is offered under. */
  private static final String BROWSERS[] = { "chrome", "firefox", "legacy" };

  private static final int LABELS[] = { R.string.accept_preset_chrome,
      R.string.accept_preset_firefox, R.string.accept_preset_legacy };

  /** The catch-all, offered on every row under itself as a label. */
  private static final String ANY = "*/*";

  /**
   * What the dropdown of one row offers. No browser value at all when the native library did not
   * load, since the engine is the only place they are kept.
   *
   * @param context the context holding the labels
   * @param kind    the row's kind word, empty for the default row
   * @return one suggestion per browser holding a value, then the catch-all
   */
  static List<Preset> suggestions(final Context context, final String kind) {
    final String labels[] = new String[BROWSERS.length];
    final String values[] = new String[BROWSERS.length];
    final boolean loaded = HTTrackLib.loadLibraries();
    for (int i = 0; i < BROWSERS.length; i++) {
      labels[i] = context.getString(LABELS[i]);
      values[i] = loaded ? HTTrackLib.getAcceptPreset(BROWSERS[i], kind) : null;
    }
    return presets(labels, values);
  }

  /**
   * The same, over values already read. A browser the engine has no value for is left out and
   * never offered empty, since picking that would clear the field and mean automatic. The
   * Default row is the live case: only the legacy table answers for it.
   *
   * @param labels the label of each browser
   * @param values its value, null or empty where it has none
   * @return the suggestions, the catch-all last
   */
  static List<Preset> presets(final String labels[], final String values[]) {
    final List<Preset> presets = new ArrayList<Preset>();
    for (int i = 0; i < values.length; i++) {
      if (values[i] != null && values[i].length() != 0) {
        presets.add(new Preset(labels[i], values[i]));
      }
    }
    presets.add(new Preset(ANY, ANY));
    return presets;
  }

  /**
   * Offer every row its browser values. Called on each showing of the tab, so the adapters follow
   * a configuration change.
   *
   * @param view the inflated tab
   */
  static void offerPresets(final View view) {
    for (final String row[] : ROWS) {
      final Integer id = OptionsMapper.fieldsNameToId.get(row[KEY]);
      if (id == null) {
        continue;
      }
      final AutoCompleteTextView field = AutoCompleteTextView.class.cast(view
          .findViewById(id.intValue()));
      field.setAdapter(new PresetAdapter(field.getContext(), suggestions(
          field.getContext(), row[KIND])));
      field.setOnClickListener(clicked -> field.showDropDown());
    }
  }

  /**
   * One suggestion. Its text is the value and not the label, because the text of the entry
   * picked is what the field is left holding.
   */
  static final class Preset {
    final String label;
    final String value;

    Preset(final String label, final String value) {
      this.label = label;
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /* A fixed menu rather than a completion of what was typed. The field holds a media type and
     the entries are browser names, so filtering would empty the dropdown on the first keystroke. */
  private static class PresetAdapter extends ArrayAdapter<Preset> {
    private final List<Preset> presets;

    PresetAdapter(final Context context, final List<Preset> presets) {
      super(context, android.R.layout.simple_dropdown_item_1line, presets);
      this.presets = presets;
    }

    @Override
    public View getView(final int position, final View recycled,
        final ViewGroup parent) {
      final TextView row = TextView.class.cast(super.getView(position,
          recycled, parent));
      row.setText(getItem(position).label);
      return row;
    }

    @Override
    public Filter getFilter() {
      return new Filter() {
        @Override
        protected FilterResults performFiltering(final CharSequence constraint) {
          final FilterResults all = new FilterResults();
          all.values = presets;
          all.count = presets.size();
          return all;
        }

        @Override
        protected void publishResults(final CharSequence constraint,
            final FilterResults results) {
          notifyDataSetChanged();
        }
      };
    }
  }
}
