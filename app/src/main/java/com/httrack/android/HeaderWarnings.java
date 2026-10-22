package com.httrack.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

/**
 * Which of the user's extra header lines the crawl will throw away. The engine refuses a few
 * header shapes and says why on stderr, which this app discards, so a header the user set is
 * silently never sent.
 *
 * The lists below are the engine's, and {@code HeaderWarningsTest} reads them back out of the
 * pinned engine source, so a bump that changes them turns red here rather than going quiet.
 */
final class HeaderWarnings {
  private HeaderWarnings() {
  }

  /** Refused outright, because they frame the message or act per hop. */
  static final List<String> FRAMING = Arrays.asList("Content-Length", "Transfer-Encoding",
      "Connection", "Keep-Alive", "TE", "Upgrade", "Expect");

  /** Carried once, so a later line of the same name replaces an earlier one. */
  static final List<String> SINGLE = Arrays.asList("Host", "Cookie", "Authorization");

  /** The characters RFC 7230 allows in a field name, besides letters and digits. */
  private static final String TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~";

  /**
   * Why the crawl will drop each line of FIELD, one message per dropped header.
   *
   * @param field
   *          the Other-headers field's text
   * @return the messages, empty when every line would be sent
   */
  static List<String> refused(final String field) {
    final List<String> out = new ArrayList<String>();
    final Set<String> seen = new LinkedHashSet<String>();
    int number = 0;
    for (final String line : CommandlineTokens.lineTokens(field)) {
      number++;
      final String name = fieldName(line);
      final String why = reason(name, seen);
      if (why != null) {
        out.add("Line " + number + ": " + why);
      }
      /* Recorded even when refused, because the engine hands a refused line to the request
         too, so it is what a later line of the same name replaces. */
      if (name != null) {
        seen.add(name.toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  /** Why a line naming NAME is dropped, given the names already seen, or null when it is sent. */
  private static String reason(final String name, final Set<String> seen) {
    if (name == null) {
      return "the line is not a header";
    }
    if (contains(FRAMING, name)) {
      return "HTTrack sets this header itself";
    }
    if (contains(SINGLE, name) && seen.contains(name.toLowerCase(Locale.ROOT))) {
      return "an earlier line already set this header";
    }
    return null;
  }

  /** LINE's field name, or null unless a valid name is followed by a colon. */
  private static String fieldName(final String line) {
    final int colon = line.indexOf(':');
    if (colon < 1) {
      return null;
    }
    for (int i = 0; i < colon; i++) {
      final char c = line.charAt(i);
      if (!Character.isLetterOrDigit(c) && TOKEN_PUNCTUATION.indexOf(c) == -1) {
        return null;
      }
      // Letters outside ASCII are not token characters, whatever isLetterOrDigit says.
      if (c > 0x7f) {
        return null;
      }
    }
    return line.substring(0, colon);
  }

  /** Is NAME one of NAMES, compared the way a header name compares? */
  private static boolean contains(final List<String> names, final String name) {
    for (final String known : names) {
      if (known.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tell the user which extra headers the crawl will not send, and say nothing when the field
   * is empty or every line would go.
   *
   * @param view
   *          the tab holding the field
   */
  static void warnAboutDropped(final View view) {
    final TextView field = TextView.class.cast(view.findViewById(R.id.editOtherHeaders));
    if (field == null) {
      return;
    }
    final List<String> dropped = refused(field.getText().toString());
    if (dropped.isEmpty()) {
      return;
    }
    final Context context = field.getContext();
    final StringBuilder message = new StringBuilder(
        context.getString(R.string.other_headers_dropped));
    for (final String line : dropped) {
      message.append("\n\n").append(line);
    }
    new AlertDialog.Builder(context).setMessage(message.toString())
        .setPositiveButton(android.R.string.ok, null).show();
  }
}
