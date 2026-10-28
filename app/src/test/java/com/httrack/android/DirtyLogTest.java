package com.httrack.android;

import static org.junit.Assert.assertEquals;

import android.util.Log;
import android.util.Pair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** What OptionsMapper.setMap logs when an option changes. */
public class DirtyLogTest {
  private static final String PREFIX = "map set dirty: ";

  /** A password inside a URL, then a line that would pass for a log entry of its own. */
  private static final String SECRET = "http://user:pa55word@example.com/\n" + PREFIX + "none";

  /** Everything logged while a fresh map takes VALUE for ID. */
  private static List<String> logOf(final int id, final String value) {
    Log.captured.clear();
    new OptionsMapper().setMap(id, value);
    return new ArrayList<String>(Log.captured);
  }

  /** The sweep below expects what the table says, so one key is pinned by hand here. */
  @Test
  public void theMessageNamesTheField() {
    assertEquals("the serialization key, and nothing of the value",
        Arrays.asList(PREFIX + "CurrentUrl"), logOf(R.id.fieldWebsiteURLs, SECRET));
    assertEquals("a key the serializer does not carry names its id",
        Arrays.asList(PREFIX + "12345"), logOf(12345, SECRET));
  }

  /** Every serialized field takes free text, so the sweep is the whole table. */
  @Test
  public void noFieldLogsItsValue() {
    final List<String> wrong = new ArrayList<String>();
    for (final Pair<Integer, String> field : OptionsMapper.fieldsSerializer) {
      final List<String> logged = logOf(field.first, SECRET);
      if (!Arrays.asList(PREFIX + field.second).equals(logged)) {
        wrong.add(field.second + " logged " + logged);
      }
    }
    assertEquals("fields whose log is not the name alone", "[]", wrong.toString());
  }
}
