package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.MultipleChoicesOption;
import com.httrack.android.OptionsMapper.OptionMapper;
import com.httrack.android.OptionsMapper.ProfileFormat;
import com.httrack.android.OptionsMapper.SimpleOptionFlag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Checks winprofile.ini storage and option emission against WinHTTrack's conventions. */
public class WinProfileParityTest {
  private static List<String> emit(final OptionMapper mapper, final String value) {
    final List<String> cmd = new ArrayList<String>();
    mapper.emit(cmd, value);
    return cmd;
  }

  /* A profile file, in the order its lines appear. */
  private static Map<String, String> file(final String... pairs) {
    final Map<String, String> raw = new LinkedHashMap<String, String>();
    for (int i = 0; i < pairs.length; i += 2) {
      raw.put(pairs[i], pairs[i + 1]);
    }
    return raw;
  }

  private static String[] boxes(final Map<String, String> values) {
    return new String[] { values.get("Dos"), values.get("Iso9660") };
  }

  /* -x turns external links into error pages, and the engine defaults it off. */
  @Test
  public void noExternalPagesEmitsWhenChecked() {
    assertEquals(Arrays.asList("-x"), emit(new SimpleOptionFlag("x"), "1"));
    assertTrue(emit(new SimpleOptionFlag("x"), "0").isEmpty());
  }

  /* -%q includes query strings and -%q0 drops them; the box hides them. */
  @Test
  public void hideQueryStringsEmitsTheDisablingForm() {
    assertEquals(Arrays.asList("-%q0"), emit(new SimpleOptionFlag("%q0"), "1"));
    assertTrue(emit(new SimpleOptionFlag("%q0"), "0").isEmpty());
  }

  /* Bare -j re-asserts the engine default, so unticking the box needs -j0. */
  @Test
  public void parseJavaTurnsOffWithTheZeroForm() {
    assertEquals(Arrays.asList("-j0"),
        emit(new SimpleOptionFlag("j0", true), "0"));
    assertTrue(emit(new SimpleOptionFlag("j0", true), "1").isEmpty());
  }

  /* Checked must revalidate, which is -C2; the engine's own default is -C1. */
  @Test
  public void cacheChecksForUpdatesWhenChecked() {
    final OptionMapper cache = new MultipleChoicesOption(new String[] { "C0",
        "C2" });
    assertEquals(Arrays.asList("-C2"), emit(cache, "1"));
    assertEquals(Arrays.asList("-C0"), emit(cache, "0"));
  }

  @Test
  public void winHttrackProfileOpensWithBothBoxes() {
    assertArrayEquals(new String[] { "0", "0" },
        boxes(ProfileFormat.resolve(file("Dos", "0"))));
    assertArrayEquals(new String[] { "1", "0" },
        boxes(ProfileFormat.resolve(file("Dos", "1"))));
    assertArrayEquals(new String[] { "0", "1" },
        boxes(ProfileFormat.resolve(file("Dos", "2"))));
    assertArrayEquals(new String[] { "1", "1" },
        boxes(ProfileFormat.resolve(file("Dos", "3"))));
  }

  /* Our own older profiles wrote Dos as a plain boolean beside an Iso9660. */
  @Test
  public void olderProfileKeepsEveryRenamedSetting() {
    final Map<String, String> values = ProfileFormat.resolve(file("Dos", "1",
        "Iso9660", "1", "ProxyProtocol", "1", "KeepWwwPrefix", "1",
        "KeepDoubleSlashes", "1"));
    assertArrayEquals(new String[] { "1", "1" }, boxes(values));
    assertEquals("1", values.get("ProxyType"));
    assertEquals("1", values.get("KeepWww"));
    assertEquals("1", values.get("KeepSlashes"));
    assertFalse(values.containsKey("ProxyProtocol"));
  }

  /* The split must read the file, not the map it is filling: a profile with no
     Dos line would otherwise clear a saved Iso9660. */
  @Test
  public void profileWithoutDosLeavesBothBoxesAlone() {
    final Map<String, String> values = ProfileFormat.resolve(file("ProxyType",
        "1"));
    assertFalse(values.containsKey("Dos"));
    assertFalse(values.containsKey("Iso9660"));
  }

  @Test
  public void aDosWithNoValueAtAllLeavesTheBoxesAlone() {
    final Map<String, String> values = ProfileFormat.resolve(file("Dos", null));
    assertNull(values.get("Dos"));
    assertFalse(values.containsKey("Iso9660"));
  }

  @Test
  public void unreadableDosLeavesTheBoxesAlone() {
    assertEquals("yes", ProfileFormat.resolve(file("Dos", "yes")).get("Dos"));
    assertNull(ProfileFormat.resolve(file("Dos", "yes")).get("Iso9660"));
    assertEquals("", ProfileFormat.resolve(file("Dos", "")).get("Dos"));
  }

  @Test
  public void dosPacksBothBoxes() {
    assertEquals("0", ProfileFormat.pack("0", "0"));
    assertEquals("1", ProfileFormat.pack("1", "0"));
    assertEquals("2", ProfileFormat.pack("0", "1"));
    assertEquals("3", ProfileFormat.pack("1", "1"));
    assertEquals("0", ProfileFormat.pack(null, null));
  }

  /* A load that rebuilds SEEDED, from a profile that carried HELD. */
  private static ProfileFormat.Baseline after(final Map<String, String> seeded,
      final String... held) {
    return new ProfileFormat.Baseline(ProfileFormat.packed(seeded),
        new HashSet<String>(Arrays.asList(held)));
  }

  /* A mapper that has loaded nothing, so it can leave nothing out. */
  private static ProfileFormat.Baseline nothingLoaded() {
    return after(file());
  }

  /* WinHTTrack has no Iso9660 key, so ours must not survive into the file. */
  @Test
  public void writtenProfileFoldsIso9660IntoDos() {
    final Map<String, String> written = ProfileFormat.toFile(file("Dos", "0",
        "Iso9660", "1", "ProxyType", "1"), nothingLoaded());
    assertEquals("2", written.get("Dos"));
    assertFalse(written.containsKey("Iso9660"));
    assertEquals("1", written.get("ProxyType"));
  }

  /* What the next load holds: what it rebuilds, then the file over the top. */
  private static Map<String, String> reload(final Map<String, String> seeded,
      final Map<String, String> written) {
    final Map<String, String> values = new LinkedHashMap<String, String>(
        seeded);
    values.putAll(ProfileFormat.resolve(written));
    return values;
  }

  @Test
  public void everyBoxCombinationSurvivesAWriteAndRead() {
    final Map<String, String> seeded = file("Dos", "0", "Iso9660", "0");
    for (final String dos : new String[] { "0", "1" }) {
      for (final String iso9660 : new String[] { "0", "1" }) {
        final Map<String, String> values = reload(seeded, ProfileFormat.toFile(
            file("Dos", dos, "Iso9660", iso9660), after(seeded)));
        assertArrayEquals(dos + "/" + iso9660,
            new String[] { dos, iso9660 }, boxes(values));
      }
    }
  }

  /* A key the file never had must not come back set, or the front end that
     wrote the file stops applying its own default for it. */
  @Test
  public void aSettingNobodyChoseStaysOutOfTheFile() {
    final Map<String, String> written = ProfileFormat.toFile(
        file("Footer", "ours", "UserID", "", "Sockets", null, "TimeOut", "",
            "Dos", "0", "Iso9660", "0"),
        after(file("Footer", "ours", "UserID", "", "Dos", "0")));
    assertFalse("seeded value still held", written.containsKey("Footer"));
    assertFalse("seeded value is the empty string",
        written.containsKey("UserID"));
    assertFalse("no value at all", written.containsKey("Sockets"));
    assertFalse("nothing seeded and nothing typed",
        written.containsKey("TimeOut"));
    assertFalse("both boxes off", written.containsKey("Dos"));
  }

  @Test
  public void aSettingThatDiffersIsWritten() {
    final Map<String, String> written = ProfileFormat.toFile(
        file("Footer", "theirs", "Sockets", "8", "Dos", "0", "Iso9660", "1"),
        after(file("Footer", "ours", "Dos", "0")));
    assertEquals("theirs", written.get("Footer"));
    assertEquals("8", written.get("Sockets"));
    assertEquals("2", written.get("Dos"));
  }

  /* Clearing a field is a choice, and the empty line is how it is recorded. */
  @Test
  public void aFieldTheUserClearedKeepsItsEmptyLine() {
    final Map<String, String> written = ProfileFormat.toFile(file("Footer", ""),
        after(file("Footer", "ours")));
    assertTrue(written.containsKey("Footer"));
    assertEquals("", written.get("Footer"));
  }

  /* The baseline is what a load rebuilds, NOT the built-in default table. A
     saved default option sits between the two, so a value matching the table
     while the load rebuilds something else must still reach the file. */
  @Test
  public void aValueTheLoadWouldNotRebuildIsWritten() {
    final Map<String, String> written = ProfileFormat.toFile(
        file("MaxRate", "25000"), after(file("MaxRate", "5000")));
    assertEquals("25000", written.get("MaxRate"));
  }

  /* Saving must not strip a line another front end put there, whatever it
     holds, or its reader falls back on a default we just took away. */
  @Test
  public void aKeyTheProfileCarriedSurvivesASave() {
    final Map<String, String> written = ProfileFormat.toFile(
        file("Footer", "ours", "UserID", "", "Dos", "0", "Iso9660", "0"),
        after(file("Footer", "ours", "UserID", "", "Dos", "0"), "Footer",
            "UserID", "Dos"));
    assertEquals("ours", written.get("Footer"));
    assertEquals("", written.get("UserID"));
    assertEquals("0", written.get("Dos"));
  }

  /* Leaving a key out must not change what the project means, since the load
     rebuilds the same baseline. */
  @Test
  public void droppingSeededValuesLeavesTheProjectUnchanged() {
    final Map<String, String> seeded = file("Footer", "ours", "UserID", "",
        "MaxRate", "5000", "Dos", "0", "Iso9660", "0");
    final Map<String, String> values = file("Footer", "theirs", "UserID", "",
        "MaxRate", "25000", "Sockets", "8", "Dos", "1", "Iso9660", "0");
    final Map<String, String> reloaded = reload(seeded,
        ProfileFormat.toFile(values, after(seeded)));
    for (final Map.Entry<String, String> setting : values.entrySet()) {
      final String was = setting.getValue();
      final String now = reloaded.get(setting.getKey());
      assertEquals(setting.getKey(), was == null ? "" : was, now == null ? ""
          : now);
    }
  }

  /* A default read off the device cannot be left out of the file, since the
     device can change before the next load. seededDefaults overrides the table
     for exactly those keys, so the two lists must name the same ones. */
  @Test
  public void everyDeviceDerivedDefaultIsAlwaysWritten() throws IOException {
    final String source = TestSources.javaSource("OptionsMapper");
    final Matcher declared = Pattern.compile(
        "environmentKeys\\[\\] = \\{([^}]*)\\}").matcher(source);
    assertTrue("environmentKeys parsed", declared.find());
    final Set<String> always = new HashSet<String>();
    final Matcher name = Pattern.compile("\"([^\"]+)\"").matcher(
        declared.group(1));
    while (name.find()) {
      always.add(name.group(1));
    }
    final Set<String> derived = new HashSet<String>();
    final Matcher put = Pattern.compile("defaults\\.put\\(\"([^\"]+)\"")
        .matcher(source);
    while (put.find()) {
      derived.add(put.group(1));
    }
    assertEquals("device-derived defaults parsed", 1, derived.size());
    assertEquals(derived, always);
  }

  @Test
  public void renamedKeysMapBothWays() {
    assertEquals("ProxyType", ProfileFormat.canonicalName("ProxyProtocol"));
    assertEquals("ProxyProtocol", ProfileFormat.legacyName("ProxyType"));
    assertEquals("KeepWww", ProfileFormat.canonicalName("KeepWwwPrefix"));
    assertEquals("KeepSlashes",
        ProfileFormat.canonicalName("KeepDoubleSlashes"));
    assertEquals("Near", ProfileFormat.canonicalName("Near"));
    assertNull(ProfileFormat.legacyName("Near"));
  }

  /* The table pulls in R.id, so the declarations are read out of the source. */
  private static String mapperTable() throws IOException {
    return TestSources.javaSource("OptionsMapper");
  }

  private static List<String> serializerKeys() throws IOException {
    final Matcher m = Pattern.compile(
        "new Pair<Integer, String>\\(R\\.id\\.\\w+,\\s*\"([^\"]+)\"\\)")
        .matcher(mapperTable());
    final List<String> keys = new ArrayList<String>();
    while (m.find()) {
      keys.add(m.group(1));
    }
    assertEquals("serializer keys parsed", 94, keys.size());
    return keys;
  }

  @Test
  public void keysUseTheWinHttrackSpelling() throws IOException {
    final List<String> keys = serializerKeys();
    assertTrue(keys.containsAll(Arrays.asList("ProxyType", "KeepWww",
        "KeepSlashes")));
    for (final String legacy : new String[] { "ProxyProtocol",
        "KeepWwwPrefix", "KeepDoubleSlashes" }) {
      assertFalse(legacy + " still written", keys.contains(legacy));
    }
  }

  /* A rename that leaves canonicalName pointing at no field drops the setting
     in silence, so the targets have to stay real keys. */
  @Test
  public void everyRenameTargetIsAStoredKey() throws IOException {
    final List<String> keys = serializerKeys();
    for (final String key : keys) {
      final String legacy = ProfileFormat.legacyName(key);
      if (legacy != null) {
        assertEquals(key, ProfileFormat.canonicalName(legacy));
      }
    }
    for (final String legacy : new String[] { "ProxyProtocol",
        "KeepWwwPrefix", "KeepDoubleSlashes" }) {
      assertTrue(legacy + " resolves to no stored key",
          keys.contains(ProfileFormat.canonicalName(legacy)));
    }
  }

  /* A hand-edited profile reaches the radio mappers with anything at all. */
  @Test
  public void aChoiceOutsideTheTableEmitsNothing() {
    final OptionMapper cache = new MultipleChoicesOption(new String[] { "C0",
        "C2" });
    for (final String value : new String[] { "2", "99999999999", "", "abc",
        null }) {
      assertTrue("value " + value, emit(cache, value).isEmpty());
    }
  }

  /* No flag may re-assert an engine default to mean "off", whichever primitive
     spells it: the -%q bug wore SimpleOption0 rather than a reverted flag. */
  @Test
  public void noOffSwitchEmitsABareEnablingForm() throws IOException {
    final Matcher m = Pattern.compile(
        "new (SimpleOptionFlag|SimpleOption0)\\(\\s*\"([^\"]+)\""
            + "(?:\\s*,\\s*(?:/\\*[^*]*\\*/\\s*)?(true|false))?\\s*\\)")
        .matcher(mapperTable());
    int reverted = 0;
    int tristate = 0;
    while (m.find()) {
      final boolean isFlag = "SimpleOptionFlag".equals(m.group(1));
      if (isFlag && "true".equals(m.group(3))) {
        reverted++;
        assertTrue("-" + m.group(2) + " must carry its 0 form", m.group(2)
            .endsWith("0"));
      } else if (!isFlag) {
        tristate++;
        assertFalse("-" + m.group(2) + " emits both forms, so it may not be "
            + "spelled as its own off switch", m.group(2).endsWith("0"));
      }
    }
    assertEquals("reverted flags parsed", 3, reverted);
    assertEquals("tri-state options parsed", 4, tristate);
  }
}
