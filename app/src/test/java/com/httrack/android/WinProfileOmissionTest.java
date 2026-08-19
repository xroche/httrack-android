package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.httrack.android.OptionsMapper.ProfileFormat;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * A save must not state a setting nobody chose, under the policy the engine's
 * shared winprofile-keys.tsv pins.
 */
public class WinProfileOmissionTest {
  /* Our default is the shared one for every agreed key but this: our action
     radio seeds nothing, so the key is written whatever it holds. */
  private static final Set<String> KNOWN_DIVERGENCES = new TreeSet<String>(
      Arrays.asList("CurrentAction"));

  /* Rows the defaults sweep cannot compare: a derived row counts as a skip the
     same way an unresolved one does. */
  private static final Set<String> NO_SHARED_DEFAULT = new TreeSet<String>(
      Arrays.asList("AcceptLanguage", "Debugging", "Iso9660", "MaxRate",
          "ProjectName", "Sockets", "UserID", "WarcFile"));

  private static final int KEY = 0;
  private static final int OWNERS = 1;
  private static final int DEFAULT_STATE = 7;
  private static final int DEFAULT_VALUE = 8;
  private static final int EMPTY_MEANS = 9;

  /* A table we cannot read is a failure and never a skip. */
  private static Map<String, String[]> sharedTable() throws IOException {
    final File table = TestSources.engineFile("winprofile-keys.tsv");
    final Map<String, String[]> rows = new LinkedHashMap<String, String[]>();
    for (final String raw : TestSources.read(table).split("\n")) {
      final String line = raw.endsWith("\r") ? raw.substring(0,
          raw.length() - 1) : raw;
      if (line.length() == 0 || line.charAt(0) == '#') {
        continue;
      }
      final String columns[] = line.split("\t", -1);
      assertEquals(line, 11, columns.length);
      rows.put(columns[KEY], columns);
    }
    assertTrue("table holds " + rows.size() + " rows", rows.size() > 90);
    return rows;
  }

  /* fieldsDefaults pulls in Pair, whose stub constructor drops both fields. */
  private static Map<String, String> ourDefaults() throws IOException {
    final String source = TestSources.javaSource("OptionsMapper");
    final int from = source.indexOf("fieldsDefaults[] = new Pair[] {");
    final int to = source.indexOf("\n  };", from);
    assertTrue("fieldsDefaults not found", from != -1 && to > from);
    // One entry is commented out, and would otherwise be read as declared.
    final String declarations = source.substring(from, to).replaceAll(
        "(?m)^\\s*//.*$", "");
    final Matcher m = Pattern.compile(
        "new Pair<String, String>\\(\\s*\"([^\"]+)\",\\s*\"([^\"]*)\"\\)")
        .matcher(declarations);
    final Map<String, String> defaults = new LinkedHashMap<String, String>();
    while (m.find()) {
      defaults.put(m.group(1), m.group(2));
    }
    assertEquals("fieldsDefaults entries parsed",
        TestSources.occurrences(declarations, "new Pair<String, String>("),
        defaults.size());
    return defaults;
  }

  private static boolean tableSaysAbsentMeansSame(final String row[],
      final String baseline) {
    if (baseline.length() == 0 && "literal".equals(row[EMPTY_MEANS])) {
      return false;
    }
    if ("derived".equals(row[DEFAULT_STATE])) {
      return true;
    }
    if ("none".equals(row[DEFAULT_STATE])) {
      return baseline.length() == 0;
    }
    return row[DEFAULT_VALUE].equals(baseline);
  }

  @Test
  public void everyWrittenKeyIsInTheSharedTable() throws IOException {
    final Set<String> unknown = new TreeSet<String>(
        TestSources.serializerKeys());
    unknown.removeAll(sharedTable().keySet());
    assertEquals("keys we write that the shared table does not state",
        new TreeSet<String>(), unknown);
  }

  /* The other direction: a key dropped from fieldsSerializer shortens every
     list built off it, so nothing else here would notice. */
  @Test
  public void everyKeyTheTableGivesUsIsWritten() throws IOException {
    final Set<String> ours = new TreeSet<String>(TestSources.serializerKeys());
    final Set<String> missing = new TreeSet<String>();
    for (final String row[] : sharedTable().values()) {
      if (Arrays.asList(row[OWNERS].split(",")).contains("droid")
          && !ours.contains(row[KEY])) {
        missing.add(row[KEY]);
      }
    }
    assertEquals("keys the table says we write but we do not",
        new TreeSet<String>(), missing);
  }

  @Test
  public void everyKeyTheFilterTablesNameIsWritten() throws IOException {
    final Set<String> named = new TreeSet<String>();
    named.addAll(Arrays.asList(ProfileFormat.DERIVED));
    named.addAll(Arrays.asList(ProfileFormat.NO_DEFAULT));
    named.addAll(Arrays.asList(ProfileFormat.EMPTY_IS_LITERAL));
    for (final String shared[] : ProfileFormat.SHARED_DEFAULTS) {
      named.add(shared[0]);
    }
    named.removeAll(TestSources.serializerKeys());
    assertEquals("table entries naming a key we no longer write",
        new TreeSet<String>(), named);
  }

  @Test
  public void omissionAgreesWithTheSharedTable() throws IOException {
    final Map<String, String[]> shared = sharedTable();
    final Map<String, String> defaults = ourDefaults();
    final Set<String> checked = new TreeSet<String>();
    for (final String key : TestSources.serializerKeys()) {
      final String row[] = shared.get(key);
      assertTrue(key + " has no row", row != null);
      for (final String baseline : new String[] { "", row[DEFAULT_VALUE],
          "not-a-default" }) {
        assertEquals(key + " seeded \"" + baseline + "\"",
            tableSaysAbsentMeansSame(row, baseline),
            ProfileFormat.absentMeansSame(key, baseline, defaults.get(key)));
      }
      checked.add(key);
    }
    assertEquals("keys checked", new TreeSet<String>(
        TestSources.serializerKeys()), checked);
  }

  @Test
  public void ourDefaultsAreTheSharedOnes() throws IOException {
    final Map<String, String[]> shared = sharedTable();
    final Map<String, String> defaults = ourDefaults();
    final Set<String> diverging = new TreeSet<String>();
    final Set<String> skipped = new TreeSet<String>();
    for (final String key : TestSources.serializerKeys()) {
      final String row[] = shared.get(key);
      if (!"agreed".equals(row[DEFAULT_STATE])) {
        skipped.add(key);
        continue;
      }
      final String ours = defaults.containsKey(key) ? defaults.get(key) : "";
      if (!row[DEFAULT_VALUE].equals(ours)) {
        diverging.add(key);
      }
    }
    assertEquals(KNOWN_DIVERGENCES, diverging);
    assertEquals("keys the sweep never compared", NO_SHARED_DEFAULT, skipped);
  }

  private static Map<String, String> values(final String... pairs) {
    final Map<String, String> values = new LinkedHashMap<String, String>();
    for (int i = 0; i < pairs.length; i += 2) {
      values.put(pairs[i], pairs[i + 1]);
    }
    return values;
  }

  private static Set<String> present(final String... keys) {
    return new HashSet<String>(Arrays.asList(keys));
  }

  /* What a reader substitutes for an absent key. Never the map a case passes
     as the seed, so swapping the two arguments reds. */
  private static final Map<String, String> OUR_DEFAULTS = values("Dos", "0",
      "Footer", "<!-- ours -->", "WildCardFilters", "+*.png", "MaxRate",
      "25000", "AcceptLanguage", "en,*");

  private static Map<String, String> written(final Map<String, String> values,
      final Map<String, String> seeded, final Set<String> present) {
    return ProfileFormat.toFile(values, seeded, OUR_DEFAULTS, present);
  }

  /* The bug: a saved WinHTTrack profile came back with our footer. */
  @Test
  public void aSettingNobodyChoseStaysOutOfTheFile() {
    final Map<String, String> seeded = values("Depth", null, "Footer",
        "<!-- ours -->", "WildCardFilters", "+*.png", "Sockets", null);
    final Map<String, String> saved = new LinkedHashMap<String, String>(seeded);
    saved.put("Depth", "3");
    final Map<String, String> file = written(saved, seeded, present("Depth"));
    assertEquals("3", file.get("Depth"));
    assertFalse("Footer restated", file.containsKey("Footer"));
    assertFalse("filters restated", file.containsKey("WildCardFilters"));
    assertFalse("Sockets restated", file.containsKey("Sockets"));
  }

  /* resetMap layers the user's saved defaults over the table, so what the seed
     holds and what a reader substitutes are two different maps. */
  @Test
  public void aSavedDefaultMovesTheSeedOffOurTable() {
    final Map<String, String> seeded = values("AcceptLanguage", "fr,*",
        "MaxRate", "");
    final Map<String, String> file = written(seeded, seeded, present());
    assertFalse("AcceptLanguage stamped", file.containsKey("AcceptLanguage"));
    assertFalse("MaxRate restated", file.containsKey("MaxRate"));
  }

  /* #127 in mirror image: an empty UserID is the user asking the engine to
     pick, and WinHTTrack reads an absent one as its own fixed literal. */
  @Test
  public void aFieldTheUserClearedIsStillStated() {
    final Map<String, String> seeded = values("UserID", "", "AcceptLanguage",
        "en,*", "OtherHeaders", "");
    final Map<String, String> file = written(seeded, seeded, present());
    assertEquals("", file.get("UserID"));
    assertTrue("UserID dropped", file.containsKey("UserID"));
    assertFalse("AcceptLanguage stamped", file.containsKey("AcceptLanguage"));
    assertFalse("OtherHeaders restated", file.containsKey("OtherHeaders"));
  }

  /* Dropping a key the file states leaves the old line as the answer, since
     nothing truncates on the crawl path. */
  @Test
  public void whatTheFileAlreadyStatesIsRestated() {
    final Map<String, String> seeded = values("Depth", null, "Footer",
        "<!-- ours -->");
    final Map<String, String> file = written(seeded, seeded, present("Depth",
        "Footer"));
    assertTrue("Depth dropped", file.containsKey("Depth"));
    assertTrue("Footer dropped", file.containsKey("Footer"));
  }

  /* A key no reader substitutes for has to be seeded empty to be omissible;
     ours seeds 25000, so it is written until that seed goes. */
  @Test
  public void aSeededValueNoReaderRestoresIsAlwaysWritten() {
    final Map<String, String> seeded = values("MaxRate", "25000", "Sockets",
        null, "Debugging", null);
    final Map<String, String> file = written(seeded, seeded, present());
    assertEquals("25000", file.get("MaxRate"));
    assertFalse("Sockets restated", file.containsKey("Sockets"));
    assertFalse("Debugging restated", file.containsKey("Debugging"));
  }

  @Test
  public void aChangedSettingIsAlwaysWritten() {
    final Map<String, String> seeded = values("Footer", "<!-- ours -->",
        "ParseAll", "1");
    final Map<String, String> saved = values("Footer", "<!-- mine -->",
        "ParseAll", "0");
    final Map<String, String> file = written(saved, seeded, present());
    assertEquals("<!-- mine -->", file.get("Footer"));
    assertEquals("0", file.get("ParseAll"));
  }

  /* Both name-mangling boxes reach the file as one Dos line, so the filter has
     to compare the packed value rather than either box. */
  @Test
  public void bothNameManglingBoxesAreComparedPacked() {
    final Map<String, String> seeded = values("Dos", "0", "Iso9660", "0");
    assertFalse("Dos restated",
        written(seeded, seeded, present()).containsKey("Dos"));
    final Map<String, String> saved = values("Dos", "0", "Iso9660", "1");
    final Map<String, String> file = written(saved, seeded, present());
    assertEquals("2", file.get("Dos"));
    assertFalse("Iso9660 written", file.containsKey("Iso9660"));
  }

  private static File profile(final String content) throws IOException {
    final File file = File.createTempFile("winprofile", ".ini");
    file.deleteOnExit();
    final Writer writer = new FileWriter(file);
    try {
      writer.write(content);
    } finally {
      writer.close();
    }
    return file;
  }

  @Test
  public void statedKeysReadsTheCurrentSpellings() throws IOException {
    final Set<String> keys = ProfileFormat.statedKeys(profile("Pause=2:5\r\n"
        + "; a comment\r\n\r\nIso9660=1\r\nFooter=\r\nno separator here\r\n"));
    assertEquals(new TreeSet<String>(Arrays.asList("Dos", "Footer", "Iso9660",
        "PauseFiles")), new TreeSet<String>(keys));
  }

  @Test
  public void aProfileThatDoesNotExistStatesNothing() throws IOException {
    final File missing = new File(profile("").getPath() + ".gone");
    assertTrue(ProfileFormat.statedKeys(missing).isEmpty());
  }

  @Test
  public void commitReplacesTheProfile() throws IOException {
    final File target = profile("old\n");
    final File pending = profile("new\n");
    ProfileFormat.commit(pending, target);
    assertEquals("new\n", TestSources.read(target));
    assertFalse("pending left behind", pending.exists());
  }

  @Test
  public void commitThatCannotRenameLeavesTheProfileAlone() throws IOException {
    final File target = profile("old\n");
    assertTrue(target.delete() && target.mkdir());
    final File pending = profile("new\n");
    try {
      ProfileFormat.commit(pending, target);
      fail("renamed a file over a directory");
    } catch (final IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(pending.getPath()));
      assertTrue(e.getMessage(), e.getMessage().contains(target.getPath()));
    }
    assertFalse("pending left behind", pending.exists());
    assertTrue("profile clobbered", target.isDirectory());
    assertTrue(target.delete());
  }

  @Test
  public void aBrandNewProjectStatesOnlyWhatCannotBeInferred()
      throws IOException {
    final Map<String, String> defaults = ourDefaults();
    final Map<String, String> seeded = new LinkedHashMap<String, String>();
    for (final String key : TestSources.serializerKeys()) {
      seeded.put(key, defaults.get(key));
    }
    seeded.put("ProjectName", "myproject");
    seeded.put("CurrentUrl", "http://example.com/");
    final Set<String> file = new TreeSet<String>(ProfileFormat.toFile(seeded,
        seeded, defaults, present()).keySet());
    assertEquals(new TreeSet<String>(Arrays.asList("CurrentAction",
        "CurrentUrl", "MaxRate", "ProjectName", "UserID")), file);
  }
}
