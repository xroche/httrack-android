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

  /* The engine's own words, not a second copy of our belief about them. An unknown kind word is
     not refused: the engine takes the whole token as the default Accept, so a rename there would
     quietly turn one row's value into every row's. */
  @Test
  public void theKindsAreTheEnginesOwn() throws IOException {
    final String table = TestSources.between(
        TestSources.read(TestSources.engineFile("src/htsfetch.c")),
        "static const char *const accept_kinds[]", "};");
    final List<String> engine = new ArrayList<String>();
    final Matcher word = Pattern.compile("\"([^\"]+)\"").matcher(table);
    while (word.find()) {
      engine.add(word.group(1));
    }
    final List<String> ours = new ArrayList<String>();
    for (final String row[] : AcceptPresets.ROWS) {
      if (!AcceptPresets.NO_KIND.equals(row[AcceptPresets.KIND])) {
        ours.add(row[AcceptPresets.KIND]);
      }
    }
    assertEquals(engine, ours);
    assertEquals("the default row comes first and carries no kind",
        AcceptPresets.NO_KIND, AcceptPresets.ROWS[0][AcceptPresets.KIND]);
    assertEquals("one row per kind, plus the default", engine.size() + 1,
        AcceptPresets.ROWS.length);
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

  /* The engine's own bound, so our copy of it cannot drift at a pin bump. HTS_ACCEPT_MAXSIZE is
     exclusive, as HTS_CDLMAXSIZE is, and it bounds the value without the kind prefix. */
  @Test
  public void ourLimitIsTheEnginesOwn() throws IOException {
    final String header = TestSources.withoutCommentsAndStrings(TestSources
        .read(TestSources.engineFile("src/httrack-library.h")));
    final Matcher m = Pattern.compile(
        "#\\s*define\\s+HTS_ACCEPT_MAXSIZE\\s+\\(?\\s*([0-9]+)").matcher(header);
    assertTrue("the pinned engine declares no HTS_ACCEPT_MAXSIZE", m.find());
    assertEquals("HTS_ACCEPT_MAXSIZE is exclusive",
        OptionsMapper.MAX_ACCEPT_BYTES + 1, Integer.parseInt(m.group(1)));
  }

  /* Measured on the engine at 80d63de3: 255 bytes pass, 256 refuse the whole command line with
     "Accept list string too long". The kind prefix does not count towards it. */
  @Test
  public void aValueTooLongForTheEngineIsDropped() {
    final String fits = "a/" + repeat('b', 253);
    final String over = "a/" + repeat('b', 254);
    assertEquals(255, fits.length());
    assertEquals(Arrays.asList("--accept", "image=" + fits),
        emit("AcceptImage", fits));
    assertEquals(Arrays.asList("--accept", fits), emit("AcceptDefault", fits));
    assertEquals(new ArrayList<String>(), emit("AcceptImage", over));
    assertEquals(new ArrayList<String>(), emit("AcceptDefault", over));
  }

  /* The limit is the engine's buffer, so it counts bytes: 255 two-byte characters are 510. */
  @Test
  public void aNonAsciiValueIsMeasuredInBytes() {
    final String wide = repeat('\u00e9', 128);
    assertEquals(128, wide.length());
    assertEquals(new ArrayList<String>(), emit("AcceptDefault", wide));
    assertEquals(Arrays.asList("--accept", repeat('\u00e9', 127)),
        emit("AcceptDefault", repeat('\u00e9', 127)));
  }

  /* Measured on the same engine: --accept followed by a word starting with '-' is a syntax error
     and no mirror runs. Only the default row can emit one, since a kind row prefixes the value. */
  @Test
  public void aValueTheEngineWouldReadAsAnOptionIsDropped() {
    assertEquals(new ArrayList<String>(), emit("AcceptDefault", "-foo"));
    assertEquals(Arrays.asList("--accept", "image=-foo"),
        emit("AcceptImage", "-foo"));
  }

  private static String repeat(final char c, final int times) {
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < times; i++) {
      out.append(c);
    }
    return out.toString();
  }

  /* Measured on the engine at 2523fc41: a default-row "image=text/probe" set the image row and
     left every other request on the built-in default, and a bare "image=" sent images no Accept
     header at all. The command line cannot tell either from the image row. */
  @Test
  public void aDefaultThatNamesAKindIsDropped() {
    for (final String value : new String[] { "image=text/probe", "image=",
        "document=text/html", "embed=*/*" }) {
      assertEquals(value, new ArrayList<String>(), emit("AcceptDefault", value));
    }
  }

  /* Only a kind word, and only before the first equals sign: a q factor and a media-type
     parameter both carry one, and no kind word is a media type. */
  @Test
  public void aDefaultHoldingAnEqualsButNoKindIsKept() {
    for (final String value : new String[] { "*/*;q=0.8", "text/html;level=1",
        "images=text/html", "=text/html", "image/png=x" }) {
      assertEquals(value, Arrays.asList("--accept", value),
          emit("AcceptDefault", value));
    }
  }

  /* A kind row prefixes its own value, so the engine reads that prefix and never the typed one. */
  @Test
  public void aKindRowKeepsAValueThatNamesAKind() {
    assertEquals(Arrays.asList("--accept", "image=document=text/html"),
        emit("AcceptImage", "document=text/html"));
  }

  /* The engine turns CR, LF and TAB in any argument into spaces (htscoremain.c), so a value
     holding one would reach the server altered. Measured there: an interior newline comes out as
     a space rather than as a second entry, so this is a mangled header and not an injected one. */
  @Test
  public void aValueHoldingAControlCharacterIsDropped() {
    for (final String value : new String[] { "image/avif\nstyle=text/css",
        "image/avif\rx", "image/avif\tx", "image/avif\u007fx" }) {
      assertEquals(value, new ArrayList<String>(), emit("AcceptImage", value));
      assertEquals(value, new ArrayList<String>(), emit("AcceptDefault", value));
    }
  }

  /* A space is not a control character, and a media type list can hold one after a comma. */
  @Test
  public void aValueHoldingASpaceIsKept() {
    assertEquals(Arrays.asList("--accept", "text/html, image/png"),
        emit("AcceptDefault", "text/html, image/png"));
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

  /** The widget behind each id, read off the Accept headers layout. */
  private static Map<String, Element> fields() throws IOException {
    final Map<String, Element> fields = new LinkedHashMap<String, Element>();
    for (final File layout : TestSources.layouts("activity_options_accept")) {
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
      assertTrue("no " + id + " in the Accept headers layout", field != null);
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
        TestSources.indexOf(source, "class AcceptHeaders extends Tab"));
    assertTrue("AcceptHeaders.onShow() must call offerPresets",
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
    assertEquals("the catch-all names itself", "", presets.get(1).label);
  }

  /* With no engine there is still something to pick, and it is never an empty entry. */
  @Test
  public void theCatchAllIsOfferedWithNoBrowserValueAtAll() {
    final List<Preset> presets = AcceptPresets.presets(new String[] { "Chrome" },
        new String[] { null });
    assertEquals(1, presets.size());
    assertEquals("*/*", presets.get(0).value);
  }

  /* The dropdown shows the value with the browser name under it, and picking a row leaves the
     value behind. That holds only because the entry's own text is the value: a label reaching
     the field would ship --accept 'Chrome default'. */
  @Test
  public void pickingASuggestionLeavesTheValueBehind() {
    assertEquals(VALUE, new Preset("Chrome default", VALUE).toString());
  }
}
