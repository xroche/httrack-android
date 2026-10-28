package com.httrack.android;

import static org.junit.Assert.assertEquals;

import android.util.Log;
import android.util.Pair;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The line logged when the map goes dirty reaches logcat, so a value in it would publish a
 *  credential, and a newline in that value would forge an entry. */
public class DirtyLogTest {
  private static final String PREFIX = "map set dirty: ";

  /** A password inside a URL, then a line that would pass for a log entry of its own. */
  private static final String SECRET = "http://user:pa55word@example.com/\nmap set dirty: none";

  /** The dirty line logged when a fresh map takes VALUE for ID, or null if none was. */
  private static String dirtyLine(final int id, final String value) {
    Log.capture();
    new OptionsMapper().setMap(id, value);
    for (final String line : Log.captured()) {
      if (line.startsWith(PREFIX)) {
        return line;
      }
    }
    return null;
  }

  @Test
  public void theLineNamesTheField() {
    assertEquals("the serialization key, and nothing of the value",
        PREFIX + "CurrentUrl", dirtyLine(R.id.fieldWebsiteURLs, SECRET));
    assertEquals("a key the serializer does not carry names its id",
        PREFIX + "12345", dirtyLine(12345, SECRET));
  }

  /** Every serialized field takes free text, so the sweep is the whole table. */
  @Test
  public void noFieldLogsItsValue() {
    final List<String> wrong = new ArrayList<String>();
    for (final Pair<Integer, String> field : OptionsMapper.fieldsSerializer) {
      final String line = dirtyLine(field.first, SECRET);
      if (!(PREFIX + field.second).equals(line)) {
        wrong.add(field.second + " logged " + line);
      }
    }
    assertEquals("fields whose line is not the name alone", "[]", wrong.toString());
  }
}
