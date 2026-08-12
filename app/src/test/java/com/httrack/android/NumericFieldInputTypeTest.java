package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A '.' reaching the engine is either read as a URL by cmdl_opt or left over
 * for the option walker to abort on, so a numeric option field may offer a
 * decimal point only for the one option the engine scans with %f.
 */
public class NumericFieldInputTypeTest {
  private static final String ANDROID_NS =
      "http://schemas.android.com/apk/res/android";

  /** The only option the engine scans with %f (htscoremain.c, case 'c'). */
  private static final String FLOAT_OPTION = "%c";

  /** Numeric option fields, each with the engine option OptionsMapper emits. */
  private static final String[][] NUMERIC_FIELDS = {
      { "editMaxDepth", "r" }, { "editMaxExtDepth", "%e" },
      { "editMaxSizeHtml", "m" }, { "editMaxSizeOther", "m" },
      { "editSiteSizeLimit", "M" }, { "editMaxTimeOverall", "E" },
      { "editMaxTransferRate", "A" }, { "editMaxConnectionsSecond", "%c" },
      { "editMaxNumberLinks", "#L" }, { "editNumberOfConnections", "c" },
      { "editTimeout", "T" }, { "editRetries", "R" },
      { "editMinTransferRate", "J" }, { "editProxyPort", "P" },
      { "editSingleFileMaxSize", "--single-file-max-size" } };

  private static String option(final String id) {
    for (final String[] field : NUMERIC_FIELDS) {
      if (field[0].equals(id)) {
        return field[1];
      }
    }
    return null;
  }

  @Test
  public void numericFieldsOfferADecimalPointOnlyWhereTheEngineTakesOne()
      throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    final DocumentBuilder builder = factory.newDocumentBuilder();
    final Set<String> seen = new HashSet<String>();
    for (final File layout : TestSources.layouts()) {
      final Document doc = builder.parse(layout);
      final NodeList nodes = doc.getElementsByTagName("*");
      for (int i = 0; i < nodes.getLength(); i++) {
        final Element view = Element.class.cast(nodes.item(i));
        // AppCompat inflation and custom widgets both lengthen the tag name.
        final String tag = view.getTagName();
        if (!tag.endsWith("EditText") && !tag.endsWith("TextView")) {
          continue;
        }
        final String id = view.getAttributeNS(ANDROID_NS, "id")
            .replaceFirst("^@\\+?id/", "");
        final String option = option(id);
        if (option == null) {
          continue;
        }
        seen.add(id);
        final String where = layout.getName() + ": " + id;
        final String inputType = view.getAttributeNS(ANDROID_NS, "inputType");
        assertFalse(where + " declares no inputType", inputType.isEmpty());
        for (final String flag : inputType.split("\\|")) {
          assertTrue(where + " is not numeric: " + flag,
              flag.startsWith("number"));
          // A leading '-' makes the engine reject the whole commandline.
          assertFalse(where + " accepts a sign", "numberSigned".equals(flag));
          assertTrue(
              where + " accepts a decimal point but -" + option + " does not",
              !"numberDecimal".equals(flag) || FLOAT_OPTION.equals(option));
        }
      }
    }
    for (final String[] field : NUMERIC_FIELDS) {
      assertTrue("no layout declares " + field[0], seen.contains(field[0]));
    }
    assertEquals(NUMERIC_FIELDS.length, seen.size());
  }
}
