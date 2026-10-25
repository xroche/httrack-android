package com.httrack.android;

import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import com.httrack.android.jni.HTTrackLib;

/**
 * Which of the user's extra header lines the crawl will not send as typed. The engine says why
 * on stderr, which this app discards, so the header would otherwise go missing with no message.
 * The engine answers the question itself, so nothing here knows what a header name may hold or
 * which names it refuses.
 */
final class HeaderWarnings {
  private HeaderWarnings() {
  }

  /** The line is not a header. */
  static final int REASON_MALFORMED = 1;
  /** HTTrack writes this header itself. */
  static final int REASON_FRAMING = 2;
  /** A later line sets the same header, and only the last one is sent. */
  static final int REASON_REPLACED = 3;
  /** An update or a resume can write this header instead. */
  static final int REASON_CONDITIONAL = 4;

  /** Where the engine's verdict on a header line comes from. */
  interface Verdicts {
    /**
     * @param headers the earlier lines joined with CRLF, or null for the first line
     * @param line    the line to judge
     * @return one of the HTTrackLib.HEADER_ constants
     */
    int of(String headers, String line);
  }

  /** One line the dialog reports, and why. */
  static final class Dropped {
    /** Which line of the box the user should look at, counting from 1. */
    final int line;
    /** One of the REASON_ constants. */
    final int reason;

    Dropped(final int line, final int reason) {
      this.line = line;
      this.reason = reason;
    }
  }

  /** The engine's own verdict, asked through the native library. */
  private static final Verdicts ENGINE = new Verdicts() {
    @Override
    public int of(final String headers, final String line) {
      return HTTrackLib.getCustomHeaderVerdict(headers, line);
    }
  };

  /**
   * Which lines of FIELD the crawl will not send as typed, in the order the user reads them.
   *
   * @param field    the Other-headers field's text
   * @param verdicts where to ask what the engine does with a line
   * @return one entry per line worth reporting, empty when every line goes out as typed
   */
  static List<Dropped> dropped(final String field, final Verdicts verdicts) {
    final List<Dropped> out = new ArrayList<Dropped>();
    final List<String> lines = new ArrayList<String>();
    final List<Integer> numbers = new ArrayList<Integer>();
    if (field == null) {
      return out;
    }
    /* The engine never sees a blank line, but the user counts it. */
    final String physical[] = field.split("\n");
    for (int i = 0; i < physical.length; i++) {
      final String line = CommandlineTokens.normalizeLine(physical[i]);
      if (line.length() != 0) {
        lines.add(line);
        numbers.add(i + 1);
      }
    }
    final int reasons[] = new int[lines.size()];
    final StringBuilder earlier = new StringBuilder();
    for (int j = 0; j < lines.size(); j++) {
      final String before = j == 0 ? null : earlier.toString();
      switch (verdicts.of(before, lines.get(j))) {
      case HTTrackLib.HEADER_DROPPED_ORPHAN_FOLD:
      case HTTrackLib.HEADER_DROPPED_MALFORMED:
        reasons[j] = REASON_MALFORMED;
        break;
      case HTTrackLib.HEADER_DROPPED_FRAMING:
        reasons[j] = REASON_FRAMING;
        break;
      case HTTrackLib.HEADER_SENT_UNLESS_ENGINE_OVERRIDES:
        reasons[j] = REASON_CONDITIONAL;
        break;
      case HTTrackLib.HEADER_SENT_REPLACES_EARLIER:
        /* This line wins, so ask which earlier lines it beats: one line alone as
           the earlier text answers for that line only. */
        for (int i = 0; i < j; i++) {
          if (verdicts.of(lines.get(i), lines.get(j))
              == HTTrackLib.HEADER_SENT_REPLACES_EARLIER) {
            reasons[i] = REASON_REPLACED;
          }
        }
        break;
      default:
        break;
      }
      if (earlier.length() != 0) {
        earlier.append("\r\n");
      }
      earlier.append(lines.get(j));
    }
    for (int j = 0; j < reasons.length; j++) {
      if (reasons[j] != 0) {
        out.add(new Dropped(numbers.get(j), reasons[j]));
      }
    }
    return out;
  }

  /** The string telling the user about REASON. */
  private static int messageFor(final int reason) {
    switch (reason) {
    case REASON_FRAMING:
      return R.string.other_headers_framing;
    case REASON_REPLACED:
      return R.string.other_headers_replaced;
    case REASON_CONDITIONAL:
      return R.string.other_headers_conditional;
    default:
      return R.string.other_headers_malformed;
    }
  }

  /**
   * Tell the user which extra headers the crawl will not send as typed, then run AFTER. Says
   * nothing when every line goes out, or when the native library did not load.
   *
   * @param view  the tab holding the field
   * @param after what to do once the user has read the message, or null for nothing
   */
  static void warnAboutDropped(final View view, final Runnable after) {
    final TextView field = TextView.class.cast(view.findViewById(R.id.editOtherHeaders));
    final List<Dropped> dropped = field != null && HTTrackLib.loadLibraries()
        ? dropped(field.getText().toString(), ENGINE)
        : new ArrayList<Dropped>();
    if (dropped.isEmpty()) {
      if (after != null) {
        after.run();
      }
      return;
    }
    final Context context = field.getContext();
    final StringBuilder message = new StringBuilder(
        context.getString(R.string.other_headers_dropped));
    for (final Dropped line : dropped) {
      message.append("\n\n").append(
          context.getString(R.string.other_headers_line, line.line,
                            context.getString(messageFor(line.reason))));
    }
    final AlertDialog.Builder builder = new AlertDialog.Builder(context)
        .setMessage(message.toString())
        .setPositiveButton(android.R.string.ok, null);
    if (after != null) {
      builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
        @Override
        public void onDismiss(final DialogInterface dialog) {
          after.run();
        }
      });
    }
    builder.show();
  }
}
