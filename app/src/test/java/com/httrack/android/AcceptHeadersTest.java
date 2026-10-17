package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Pair;
import com.httrack.android.AcceptPresets.Preset;
import com.httrack.android.OptionsMapper.AcceptOption;
import com.httrack.android.OptionsMapper.OptionMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The Accept headers box: twelve rows, each shipping --accept for one kind of link. An empty row
 * means automatic, so a row that emitted anything for it would send a header nobody asked for.
 */
public class AcceptHeadersTest {
  /** The kinds the engine takes, in its display order; the default row carries none. */
  private static final String KINDS[] = { "", "document", "iframe", "frame",
      "image", "style", "script", "font", "audio", "video", "object", "embed" };

  /** A value with a comma, a slash and a q factor, since a real one has all three. */
  private static final String VALUE = "image/avif,image/webp,*/*;q=0.8";

  /** The mapper the shipped table wires KEY to. */
  private static OptionMapper wiredMapper(final String key) {
    for (final Pair<String, OptionMapper> field : new OptionsMapper().fieldsMapper) {
      if (field.first.equals(key)) {
        return field.second;
      }
    }
    throw new IllegalStateException("no mapper for " + key);
  }

  private static List<String> emit(final String key, final String value) {
    final List<String> commandline = new ArrayList<String>();
    wiredMapper(key).emit(commandline, value);
    return commandline;
  }

  @Test
  public void theRowTableNamesTheKindsTheEngineTakes() {
    final List<String> kinds = new ArrayList<String>();
    for (final String row[] : AcceptPresets.ROWS) {
      kinds.add(row[AcceptPresets.KIND]);
    }
    assertEquals(Arrays.asList(KINDS), kinds);
  }

  /* The row table drives the dropdown and the mapper builds the command line, so each holds the
     kind. A drift between them sends one kind's value under another's name. */
  @Test
  public void theMapperCarriesTheKindItsRowNames() {
    for (final String row[] : AcceptPresets.ROWS) {
      final OptionMapper mapper = wiredMapper(row[AcceptPresets.KEY]);
      assertTrue(row[AcceptPresets.KEY] + " is wired to " + mapper.getClass(),
          mapper instanceof AcceptOption);
      assertEquals(row[AcceptPresets.KEY], row[AcceptPresets.KIND],
          AcceptOption.class.cast(mapper).kind);
    }
  }

  @Test
  public void everyRowShipsItsOwnKind() {
    final Map<List<String>, String> seen = new LinkedHashMap<List<String>, String>();
    for (final String row[] : AcceptPresets.ROWS) {
      final String key = row[AcceptPresets.KEY];
      final String kind = row[AcceptPresets.KIND];
      final List<String> argv = emit(key, VALUE);
      assertEquals(key, Arrays.asList("--accept", kind.length() != 0 ? kind
          + "=" + VALUE : VALUE), argv);
      final String twin = seen.put(argv, key);
      assertEquals(key + " and " + twin + " both ship " + argv, null, twin);
    }
    assertEquals("one command line per row", AcceptPresets.ROWS.length,
        seen.size());
  }

  /* The field is free text and its hint reads "Automatic", so whitespace is what a user leaves
     behind rather than a header they chose. */
  @Test
  public void anEmptyRowShipsNothing() {
    for (final String row[] : AcceptPresets.ROWS) {
      for (final String value : new String[] { null, "", " ", "\t\n" }) {
        assertEquals(row[AcceptPresets.KEY] + " for \"" + value + "\"",
            new ArrayList<String>(), emit(row[AcceptPresets.KEY], value));
      }
    }
  }

  /* Surrounding blanks would otherwise reach the header verbatim. */
  @Test
  public void aRowTrimsWhatItShips() {
    assertEquals(Arrays.asList("--accept", "image=" + VALUE),
        emit("AcceptImage", "  " + VALUE + "\n"));
    assertEquals(Arrays.asList("--accept", VALUE),
        emit("AcceptDefault", " " + VALUE + " "));
  }

  /* The box's twelve keys and AcceptLanguage are the only ones starting that way, so a thirteenth
     row added to one table and not the other is caught here. */
  @Test
  public void theSavedKeysAreTheRowsAndNothingElse() {
    final TreeSet<String> ours = new TreeSet<String>();
    for (final String row[] : AcceptPresets.ROWS) {
      ours.add(row[AcceptPresets.KEY]);
    }
    final TreeSet<String> saved = new TreeSet<String>();
    for (final String key : TestSources.serializerKeys()) {
      if (key.startsWith("Accept") && !"AcceptLanguage".equals(key)) {
        saved.add(key);
      }
    }
    assertEquals(ours, saved);
  }

  /** The widget behind each id, read off the Browser ID layout. */
  private static Map<String, Element> fields() throws IOException {
    final Map<String, Element> fields = new LinkedHashMap<String, Element>();
    for (final File layout : TestSources.layouts("activity_options_browserid")) {
      final Matcher m = Pattern.compile(
          "<(\\w+)\\b([^>]*android:id=\"@\\+?id/(\\w+)\"[^>]*)>").matcher(
          TestSources.read(layout));
      while (m.find()) {
        fields.put(m.group(3), new Element(m.group(1), m.group(2)));
      }
    }
    return fields;
  }

  private static final class Element {
    final String tag;
    final String attributes;

    Element(final String tag, final String attributes) {
      this.tag = tag;
      this.attributes = attributes;
    }
  }

  /* A plain EditText offers no dropdown, and a hint of anything but "Automatic" leaves the user
     no way to read an empty row. */
  @Test
  public void everyRowIsAnEditableComboHintedAutomatic() throws IOException {
    final Map<String, Element> fields = fields();
    for (final String row[] : AcceptPresets.ROWS) {
      final String id = "editAccept"
          + row[AcceptPresets.KEY].substring("Accept".length());
      final Element field = fields.get(id);
      assertTrue("no " + id + " in the Browser ID layout", field != null);
      assertEquals(id, "AutoCompleteTextView", field.tag);
      assertTrue(id + " is hinted " + field.attributes,
          field.attributes.contains("android:hint=\"@string/accept_automatic\""));
    }
  }

  /* Nothing at runtime says a row has no dropdown, so the call that gives it one is pinned. */
  @Test
  public void theTabOffersThePresetsWhenItIsShown() throws IOException {
    final String source = TestSources.javaSource("OptionsActivity");
    final String tab = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "class BrowserId extends Tab"));
    assertTrue("BrowserId.onShow() must call offerPresets",
        tab.contains("AcceptPresets.offerPresets(view)"));
  }

  /* The engine answers for the Default row from the legacy table alone, and a browser offered
     with no value would clear the field, which reads as automatic. */
  @Test
  public void aBrowserWithoutAValueIsLeftOut() {
    final List<Preset> presets = AcceptPresets.presets(new String[] { "Chrome",
        "Firefox", "Old HTTrack value" }, new String[] { null, "",
        "text/html,*/*;q=0.1" });
    assertEquals(2, presets.size());
    assertEquals("Old HTTrack value", presets.get(0).label);
    assertEquals("text/html,*/*;q=0.1", presets.get(0).value);
    assertEquals("*/*", presets.get(1).value);
    assertEquals("*/*", presets.get(1).label);
  }

  /* With no engine there is still something to pick, and it is never an empty entry. */
  @Test
  public void theCatchAllIsOfferedWithNoBrowserValueAtAll() {
    final List<Preset> presets = AcceptPresets.presets(new String[] { "Chrome" },
        new String[] { null });
    assertEquals(1, presets.size());
    assertEquals("*/*", presets.get(0).value);
  }

  /* The dropdown shows a browser name and leaves the value behind, which holds only because the
     entry's own text is the value. A label reaching the field would ship --accept 'Chrome'. */
  @Test
  public void pickingASuggestionLeavesTheValueBehind() {
    assertEquals(VALUE, new Preset("Chrome", VALUE).toString());
  }
}
