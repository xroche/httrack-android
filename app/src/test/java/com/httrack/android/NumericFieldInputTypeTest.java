package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The engine's cmdl_opt() reads a '.' inside a '%'-less token such as "-r1.5" as
 * a URL, so integer option fields must not offer a decimal point.
 */
public class NumericFieldInputTypeTest {
  private static final String ANDROID_NS =
      "http://schemas.android.com/apk/res/android";

  /** -%c is the only mapped option the engine scans with "%f". */
  private static final String DECIMAL_FIELD = "editMaxConnectionsSecond";

  /* Unit tests run with the app project as working directory. */
  private static File layoutDir() {
    for (final String path : new String[] { "src/main/res/layout",
        "app/src/main/res/layout" }) {
      final File dir = new File(path);
      if (dir.isDirectory()) {
        return dir;
      }
    }
    throw new IllegalStateException("no layout directory below "
        + new File(".").getAbsolutePath());
  }

  @Test
  public void onlyTheConnectionRateFieldAcceptsADecimalPoint()
      throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    boolean sawDecimalField = false;
    for (final File layout : layoutDir().listFiles()) {
      if (!layout.getName().endsWith(".xml")) {
        continue;
      }
      final Document doc = factory.newDocumentBuilder().parse(layout);
      final NodeList fields = doc.getElementsByTagName("EditText");
      for (int i = 0; i < fields.getLength(); i++) {
        final Element field = Element.class.cast(fields.item(i));
        final String type = field.getAttributeNS(ANDROID_NS, "inputType");
        if (!type.startsWith("number")) {
          continue;
        }
        final String id =
            field.getAttributeNS(ANDROID_NS, "id").replace("@+id/", "");
        final boolean decimal = DECIMAL_FIELD.equals(id);
        sawDecimalField |= decimal;
        assertEquals(layout.getName() + ": " + id,
            decimal ? "numberDecimal" : "number", type);
      }
    }
    assertTrue("no numeric field parsed", sawDecimalField);
  }
}
