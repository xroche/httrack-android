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
import android.text.TextUtils;
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

  /** The kind word of the row every other kind falls back to. */
  static final String NO_KIND = "";

  /**
   * The rows, in the engine's display order. A kind is one of the Sec-Fetch-Dest words
   * hts_accept_preset() takes, and the keys are the ones winprofile-keys.tsv states.
   */
  static final String ROWS[][] = { { "AcceptDefault", NO_KIND },
      { "AcceptDocument", "document" }, { "AcceptIframe", "iframe" },
      { "AcceptFrame", "frame" }, { "AcceptImage", "image" },
      { "AcceptStyle", "style" }, { "AcceptScript", "script" },
      { "AcceptFont", "font" }, { "AcceptAudio", "audio" },
      { "AcceptVideo", "video" }, { "AcceptObject", "object" },
      { "AcceptEmbed", "embed" } };

  /**
   * Would the default row's VALUE be read as one kind's own entry? A typed "image="
   * followed by a media type is the image row on the command line, and the engine cannot tell
   * the two apart, so the default row refuses it rather than filling a row left empty.
   *
   * @param value the typed value, already trimmed
   * @return true when the text before the first equals sign is a kind word
   */
  static boolean namesAKind(final String value) {
    final int equals = value.indexOf('=');
    if (equals == -1) {
      return false;
    }
    final String prefix = value.substring(0, equals);
    for (final String row[] : ROWS) {
      if (row[KIND].length() != 0 && row[KIND].equals(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** Browsers the engine holds a table for, with the label each one's value is offered under. */
  private static final String BROWSERS[] = { "chrome", "firefox", "legacy" };

  private static final int LABELS[] = { R.string.accept_preset_chrome,
      R.string.accept_preset_firefox, R.string.accept_preset_legacy };

  /** The catch-all. Every row offers it, under a browser's name where that browser sends it. */
  private static final String ANY = "*/*";

  /**
   * What the dropdown of one row offers. No browser value at all when the native library did not
   * load, since the engine is the only place they are kept.
   *
   * @param context the context holding the labels
   * @param kind    the row's kind word, NO_KIND for the default row
   * @return one suggestion per distinct value, the catch-all among them
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
   * Builds the suggestion list from values already read. A browser the engine has no value for
   * is left out and never offered empty, since picking that would clear the field and mean
   * automatic.
   *
   * @param labels the label of each browser
   * @param values its value, null or empty where it has none
   * @return the suggestions, one per distinct value
   */
  static List<Preset> presets(final String labels[], final String values[]) {
    final List<Preset> presets = new ArrayList<Preset>();
    for (int i = 0; i < values.length; i++) {
      if (values[i] != null && values[i].length() != 0) {
        offer(presets, labels[i], values[i]);
      }
    }
    offer(presets, "", ANY);
    return presets;
  }

  /**
   * Add VALUE under LABEL, or name LABEL on the row that already holds it. Chrome and Firefox
   * send the same thing for several kinds, and for scripts both send the catch-all, so a row
   * per browser would offer one value three times over.
   *
   * @param presets the rows so far
   * @param label   the browser, empty for the catch-all
   * @param value   its value
   */
  private static void offer(final List<Preset> presets, final String label,
      final String value) {
    for (int i = 0; i < presets.size(); i++) {
      final Preset seen = presets.get(i);
      /* Whole-value equality. A prefix test would name Chrome for anything merely starting
         like Chrome's value, which is the bug the WinHTTrack box had to fix. */
      if (seen.value.equals(value)) {
        if (label.length() != 0) {
          presets.set(i, new Preset(seen.label.length() != 0 ? seen.label
              + ", " + label : label, value));
        }
        return;
      }
    }
    presets.add(new Preset(label, value));
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
      /* A tap on a row that has no focus only gives it focus, so the click alone would ask for
         two taps to see the choices. */
      field.setOnClickListener(clicked -> field.showDropDown());
      field.setOnFocusChangeListener((focused, hasFocus) -> {
        if (hasFocus) {
          field.showDropDown();
        }
      });
    }
  }

  /**
   * One suggestion. Its text is the value and not the label, because the text of the entry
   * picked is what the field is left holding.
   */
  static final class Preset {
    /** Browser the value belongs to, empty where the value names itself. */
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
      super(context, android.R.layout.simple_list_item_2, android.R.id.text1,
          presets);
      this.presets = presets;
    }

    /* The value is what the row ships, so the value is what it shows; the browser name goes
       under it. Showing the name alone would hide the bytes the user is choosing. */
    @Override
    public View getView(final int position, final View recycled,
        final ViewGroup parent) {
      final View row = super.getView(position, recycled, parent);
      final Preset preset = getItem(position);
      final TextView value = TextView.class.cast(row
          .findViewById(android.R.id.text1));
      final TextView label = TextView.class.cast(row
          .findViewById(android.R.id.text2));
      value.setText(preset.value);
      /* Wrapped whole, one Chrome value fills six lines and the four choices stop fitting on
         screen together, so the row shows the head of the value and the field takes all of it. */
      value.setSingleLine(false);
      value.setMaxLines(3);
      value.setEllipsize(TextUtils.TruncateAt.END);
      label.setText(preset.label);
      label.setVisibility(preset.label.length() != 0 ? View.VISIBLE : View.GONE);
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
