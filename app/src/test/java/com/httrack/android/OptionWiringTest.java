package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Pair;
import com.httrack.android.OptionsMapper.MultipleChoicesOption;
import com.httrack.android.OptionsMapper.OptionMapper;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Three tables carry an option from its widget to the engine: fieldsSerializer names the widget,
 * fieldsMapper turns the value into arguments, and fieldsDefaults seeds it. A row that is missing,
 * or that points at a widget of the wrong kind, costs nothing at runtime. So these tests drive the
 * shipped OptionsMapper and read the command line it builds.
 *
 * <p>They reach a widget through the same table they audit, so they cannot tell two widgets of one
 * kind apart. Swapping two checkbox ids leaves every test here green. Naming which option a key
 * carries is WinProfileParityTest's job.
 */
public class OptionWiringTest {
  private static final String ANDROID_NS =
      "http://schemas.android.com/apk/res/android";

  /** Two values a user could type, because one command line on its own proves nothing. */
  private static final String ONE = "1234";
  private static final String OTHER = "5678";


  /* Two strings no option emits. The digits matter: a value option drops text but keeps a
     number, so text alone cannot tell a checkbox from a field wired to -r or -T. */
  private static final String[] TYPED = { "zzprobe", "97" };

  /** Kept in the profile only, because both name the project rather than the crawl. */
  private static final Set<String> NOT_ENGINE_OPTIONS = new TreeSet<String>(
      Arrays.asList("Category", "ProjectName"));

  private static final String[][] GATED = {
      { "Sitemap", "SitemapUrl", "-%m", "--sitemap-url" },
      { "Warc", "WarcFile", "-%r", "--warc-file" },
      { "SingleFile", "SingleFileMaxSize", "-%Z", "--single-file-max-size" } };

  /** Maps a field to the companion field and value that must be set before it emits. */
  private static final Map<String, String[]> COMPANIONS = companions();

  private static Map<String, String[]> companions() {
    final Map<String, String[]> map = new LinkedHashMap<String, String[]>();
    map.put("BuildString", new String[] { "Build", "14" });
    map.put("Port", new String[] { "Proxy", "proxy.example" });
    map.put("ProxyType", new String[] { "Proxy", "proxy.example" });
    for (final String[] gate : GATED) {
      map.put(gate[1], new String[] { gate[0], "1" });
    }
    // Neither half of a mime rule emits without the other.
    for (int i = 1; i <= 8; i++) {
      map.put("MIMEDefsExt" + i, new String[] { "MIMEDefsMime" + i,
          "application/x-probe" });
      map.put("MIMEDefsMime" + i, new String[] { "MIMEDefsExt" + i, ".probe" });
    }
    return map;
  }

  /** Every R.id by value, so a table entry can name the widget it points at. */
  private static Map<Integer, String> idNames() throws Exception {
    final Map<Integer, String> names = new HashMap<Integer, String>();
    for (final Field field : R.id.class.getFields()) {
      names.put(Integer.valueOf(field.getInt(null)), field.getName());
    }
    return names;
  }

  private static List<Element> views(final File layout) throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    final DocumentBuilder builder = factory.newDocumentBuilder();
    final Document doc = builder.parse(layout);
    final NodeList nodes = doc.getElementsByTagName("*");
    final List<Element> views = new ArrayList<Element>();
    for (int i = 0; i < nodes.getLength(); i++) {
      views.add(Element.class.cast(nodes.item(i)));
    }
    return views;
  }

  private static String viewId(final Element view) {
    return view.getAttributeNS(ANDROID_NS, "id").replaceFirst("^@\\+?id/", "");
  }

  /** The widget class each id names, read off the tag suffix so AppCompat names count too. */
  private static Map<String, String> widgets() throws Exception {
    final Map<String, String> tags = new TreeMap<String, String>();
    for (final File layout : TestSources.layouts()) {
      for (final Element view : views(layout)) {
        final String id = viewId(view);
        if (id.length() == 0) {
          continue;
        }
        final String tag = view.getTagName();
        tags.put(id, tag.substring(tag.lastIndexOf('.') + 1));
      }
    }
    return tags;
  }

  /** How many buttons each radio group offers, which is how many choices it has to reach. */
  private static Map<String, Integer> radioButtons() throws Exception {
    final Map<String, Integer> counts = new TreeMap<String, Integer>();
    for (final File layout : TestSources.layouts()) {
      for (final Element view : views(layout)) {
        if (!view.getTagName().endsWith("RadioGroup")) {
          continue;
        }
        int buttons = 0;
        final NodeList kids = view.getElementsByTagName("*");
        for (int i = 0; i < kids.getLength(); i++) {
          if (Element.class.cast(kids.item(i)).getTagName()
              .endsWith("RadioButton")) {
            buttons++;
          }
        }
        counts.put(viewId(view), Integer.valueOf(buttons));
      }
    }
    return counts;
  }

  private static int id(final String key) {
    final Integer id = OptionsMapper.fieldsNameToId.get(key);
    assertTrue("fieldsSerializer names no widget for " + key, id != null);
    return id.intValue();
  }

  /** What the shipped mapper hands the engine for one field and its companion, if it has one. */
  private static List<String> commandline(final String key, final String value) {
    final OptionsMapper mapper = new OptionsMapper();
    final String[] companion = COMPANIONS.get(key);
    if (companion != null) {
      mapper.setMap(id(companion[0]), companion[1]);
    }
    mapper.setMap(id(key), value);
    return mapper.buildCommandline();
  }

  /** The keys of each serialized field, paired with the widget class behind it. */
  private static Map<String, String> keyWidgets() throws Exception {
    final Map<Integer, String> names = idNames();
    final Map<String, String> widgets = widgets();
    final Map<String, String> kinds = new LinkedHashMap<String, String>();
    for (final Pair<Integer, String> field : OptionsMapper.fieldsSerializer) {
      final String name = names.get(field.first);
      final String tag = widgets.get(name);
      assertTrue(field.second + " points at " + name
          + ", which no layout declares", tag != null);
      kinds.put(field.second, tag);
    }
    return kinds;
  }

  /** The mapper each key is wired to, as the shipped table declares it. */
  private static Map<String, OptionMapper> mappers() {
    final Map<String, OptionMapper> mappers =
        new LinkedHashMap<String, OptionMapper>();
    for (final Pair<String, OptionMapper> field : new OptionsMapper()
        .fieldsMapper) {
      mappers.put(field.first, field.second);
    }
    return mappers;
  }

  private static Set<String> mapperKeys() {
    return new TreeSet<String>(mappers().keySet());
  }

  private static Set<String> defaultKeys() {
    final Set<String> keys = new TreeSet<String>();
    for (final Pair<String, String> field : OptionsMapper.fieldsDefaults) {
      keys.add(field.first);
    }
    return keys;
  }

  @Test
  public void everyKeyWeSaveReachesAMapper() {
    final Set<String> unmapped = new TreeSet<String>(
        TestSources.serializerKeys());
    unmapped.removeAll(mapperKeys());
    assertEquals("keys buildCommandline() logs and drops", new TreeSet<String>(),
        unmapped);
  }

  /* A mapper under a key no widget saves is never reached, whatever it emits. */
  @Test
  public void everyMapperAnswersToASavedKey() {
    final Set<String> orphans = new TreeSet<String>(mapperKeys());
    orphans.removeAll(TestSources.serializerKeys());
    assertEquals("mappers no field can reach", new TreeSet<String>(), orphans);
  }

  /* initializeMap() throws on a default whose key no widget holds, which is a crash at startup. */
  @Test
  public void everyDefaultNamesASavedKey() {
    final Set<String> unknown = new TreeSet<String>(defaultKeys());
    unknown.removeAll(TestSources.serializerKeys());
    assertEquals("defaults for keys we do not save", new TreeSet<String>(),
        unknown);
  }

  @Test
  public void everySavedFieldHasAWidget() throws Exception {
    final Map<String, String> kinds = keyWidgets();
    assertEquals("one entry per saved key", TestSources.serializerKeys().size(),
        kinds.size());
    assertTrue("read " + kinds.size() + " fields", kinds.size() > 90);
  }

  /* A field nobody can move is a setting the user changes for nothing. Radio groups have their
     own test below. */
  @Test
  public void everyFieldChangesTheCommandLine() throws Exception {
    final Set<String> inert = new TreeSet<String>();
    for (final Map.Entry<String, String> field : keyWidgets().entrySet()) {
      final String key = field.getKey();
      if (NOT_ENGINE_OPTIONS.contains(key)
          || "RadioGroup".equals(field.getValue())) {
        continue;
      }
      final boolean flag = "CheckBox".equals(field.getValue());
      final List<String> one = commandline(key, flag ? "1" : ONE);
      final List<String> other = commandline(key, flag ? "0" : OTHER);
      if (one.equals(other)) {
        inert.add(key + " (" + field.getValue() + ")");
      }
    }
    assertEquals("fields the engine never hears about", new TreeSet<String>(),
        inert);
  }

  /* A checkbox only ever holds 0 or 1. One wired to a value option would pass a profile's stored
     text straight to the engine. */
  @Test
  public void aCheckboxNeverPassesTextToTheEngine() throws Exception {
    final Set<String> leaking = new TreeSet<String>();
    int checked = 0;
    for (final Map.Entry<String, String> field : keyWidgets().entrySet()) {
      if (!"CheckBox".equals(field.getValue())) {
        continue;
      }
      checked++;
      for (final String typed : TYPED) {
        for (final String argument : commandline(field.getKey(), typed)) {
          if (argument.contains(typed)) {
            leaking.add(field.getKey() + " emits " + argument);
          }
        }
      }
    }
    assertEquals("checkboxes emitting their own text", new TreeSet<String>(),
        leaking);
    assertTrue("read " + checked + " checkboxes", checked > 30);
  }

  /* Two buttons that build the same command line mean the engine never hears the second one. */
  @Test
  public void everyRadioButtonSelectsItsOwnOption() throws Exception {
    final Map<String, Integer> buttons = radioButtons();
    final Map<Integer, String> names = idNames();
    int groups = 0;
    for (final Pair<Integer, String> field : OptionsMapper.fieldsSerializer) {
      final Integer count = buttons.get(names.get(field.first));
      if (count == null) {
        continue;
      }
      groups++;
      final Map<List<String>, Integer> seen =
          new LinkedHashMap<List<String>, Integer>();
      for (int i = 0; i < count.intValue(); i++) {
        final List<String> argv = commandline(field.second, String.valueOf(i));
        final Integer twin = seen.put(argv, Integer.valueOf(i));
        assertNull(field.second + ": buttons " + twin + " and " + i
            + " both mean " + argv, twin);
      }
    }
    assertTrue("read " + groups + " radio groups", groups > 8);
  }

  /* The choices and the widget in front of them are declared apart, so their counts can drift. */
  @Test
  public void everyChoiceHasAWidgetStateToSelectIt() throws Exception {
    final Map<String, Integer> buttons = radioButtons();
    final Map<Integer, String> names = idNames();
    final Map<String, OptionMapper> mappers = mappers();
    int checked = 0;
    for (final Pair<Integer, String> field : OptionsMapper.fieldsSerializer) {
      final OptionMapper mapper = mappers.get(field.second);
      if (!(mapper instanceof MultipleChoicesOption)) {
        continue;
      }
      final Integer group = buttons.get(names.get(field.first));
      // Cache once held a choices table behind a checkbox, which offers two states, not a count.
      assertNotNull(field.second + " holds a choices table but no radio group", group);
      assertEquals(field.second + " offers " + group + " states", group.intValue(),
          MultipleChoicesOption.class.cast(mapper).choices.length);
      checked++;
    }
    assertTrue("read " + checked + " choice tables", checked > 3);
  }

  /* A gated value reaches the engine only because fieldsSerializer emits its checkbox first. */
  @Test
  public void aGatedValueNeedsItsCheckbox() {
    for (final String[] gate : GATED) {
      final OptionsMapper on = new OptionsMapper();
      on.setMap(id(gate[0]), "1");
      on.setMap(id(gate[1]), ONE);
      final List<String> ticked = on.buildCommandline();
      assertTrue(gate[0] + " emits no " + gate[2], ticked.contains(gate[2]));
      assertTrue(gate[1] + " emits no " + gate[3], ticked.contains(gate[3]));
      assertEquals(gate[1] + " does not follow " + gate[3], ONE,
          ticked.get(ticked.indexOf(gate[3]) + 1));

      final OptionsMapper off = new OptionsMapper();
      off.setMap(id(gate[0]), "0");
      off.setMap(id(gate[1]), ONE);
      final List<String> unticked = off.buildCommandline();
      assertFalse(gate[0] + " emits " + gate[2] + " while unticked",
          unticked.contains(gate[2]));
      assertFalse(gate[3] + " reaches the engine while " + gate[0]
          + " is unticked", unticked.contains(gate[3]));
    }
  }

  /** A fresh project sends no rate cap, so the engine applies its own default. */
  @Test
  public void aFreshProjectAsksForNoRateCap() {
    final List<String> argv = new OptionsMapper().buildCommandline();
    assertFalse("nothing emitted, so the sweep below proves nothing", argv.isEmpty());
    for (final String token : argv) {
      assertFalse("rate cap " + token, token.startsWith("-A"));
    }
  }

  /** buildCommandline() runs finish() in iteration order, so the map must keep the table's. */
  @Test
  public void theMapperMapKeepsTheTableOrder() {
    final OptionsMapper mapper = new OptionsMapper();
    final List<String> declared = new ArrayList<String>();
    for (final Pair<String, OptionMapper> field : mapper.fieldsMapper) {
      declared.add(field.first);
    }
    assertEquals(declared,
        new ArrayList<String>(mapper.fieldsNameToMapper.keySet()));
  }

  /** Two rows naming one extension: the engine keeps the first -%A it reads, so row 1 must win. */
  @Test
  public void theTopmostMimeRowWins() {
    final OptionsMapper mapper = new OptionsMapper();
    mapper.setMap(id("MIMEDefsExt1"), "php3");
    mapper.setMap(id("MIMEDefsMime1"), "text/html");
    mapper.setMap(id("MIMEDefsExt2"), "php3");
    mapper.setMap(id("MIMEDefsMime2"), "text/plain");
    final List<String> argv = mapper.buildCommandline();
    final int row1 = argv.indexOf("php3=text/html");
    final int row2 = argv.indexOf("php3=text/plain");
    assertTrue("row 1 emitted nothing", row1 >= 0);
    assertTrue("row 2 emitted nothing", row2 >= 0);
    assertTrue("row 2 reaches the engine first", row1 < row2);
  }
}
