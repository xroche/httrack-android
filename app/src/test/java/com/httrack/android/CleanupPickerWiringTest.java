package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** #211: the picker's title must not say "Delete" outside the delete flow, and a row must
 *  select from its name as well as its checkbox. These prove both are wired, not merely
 *  present somewhere in the file. */
public class CleanupPickerWiringTest {
  private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

  private static String activity() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource("CleanupActivity"));
  }

  /** Body of the method whose declaration starts with SIGNATURE, braces balanced and laid out on
   *  one line, so a reformat of the source does not red the pins below. */
  private static String body(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    return flatten(TestSources.balancedBlock(source, at + signature.length()));
  }

  /** TEXT on one line, with the space a line break leaves after "(" or before ")" or "." gone. */
  private static String flatten(final String text) {
    return text.replaceAll("\\s+", " ").replaceAll("\\( ", "(")
        .replaceAll(" \\)", ")").replaceAll(" \\.", ".").trim();
  }

  /** Calls to the activity's own setTitle, which a dialog builder's .setTitle is not. */
  private static int activitySetTitles(final String source) {
    final Matcher matcher = Pattern.compile("(?<![.\\w])setTitle\\(").matcher(source);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  @Test
  public void onCreateAsksThePolicyWithTheSelectFlagAndSetsTheTitle() throws IOException {
    final String source = activity();
    final String onCreate = body(source, "onCreate(final Bundle savedInstanceState)");
    assertEquals("action == ACTION_SELECT",
        TestSources.arguments(onCreate, "CleanupTitlePolicy.titleFor").trim());
    assertTrue("the policy answer must reach setTitle, not just get computed",
        onCreate.contains("setTitle(CleanupTitlePolicy.titleFor(action == ACTION_SELECT))"));
    assertEquals("a branch would leave the delete label up for some openings", 0,
        TestSources.depthOf(onCreate, "setTitle(CleanupTitlePolicy.titleFor"));
    assertEquals("a second call anywhere would override the policy's answer", 1,
        activitySetTitles(source));
  }

  @Test
  public void theNameElementIsWiredToOnClickNameInTheLayout() throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    final DocumentBuilder builder = factory.newDocumentBuilder();
    final Document doc = builder.parse(TestSources.resFile("layout/cleanup_item.xml"));
    final NodeList nodes = doc.getElementsByTagName("*");
    String nameOnClick = null;
    String checkOnClick = null;
    for (int i = 0; i < nodes.getLength(); i++) {
      final Element view = Element.class.cast(nodes.item(i));
      final String id = view.getAttributeNS(ANDROID_NS, "id");
      if ("@+id/name".equals(id)) {
        nameOnClick = view.getAttributeNS(ANDROID_NS, "onClick");
      } else if ("@+id/check".equals(id)) {
        checkOnClick = view.getAttributeNS(ANDROID_NS, "onClick");
      }
    }
    assertEquals("the name must select the row", "OnClickName", nameOnClick);
    assertEquals("the checkbox must keep its own handler", "OnClickCheckbox", checkOnClick);
  }

  @Test
  public void onClickNameReusesTheCheckboxsOwnClickPath() throws IOException {
    final String source = activity();
    assertTrue("android:onClick needs a public void(View) method",
        source.contains("public void OnClickName(final View v)"));
    final String onClickName = body(source, "OnClickName(final View v)");
    assertTrue("must resolve the row's own checkbox", onClickName.contains(
        "final CheckBox cb = row != null ? (CheckBox) row.findViewById(R.id.check) : null;"));
    assertTrue("must replay a real checkbox click on a box the user can still hit",
        onClickName.contains("if (cb != null && cb.isEnabled()) { cb.performClick(); }"));
    assertEquals("a deeper branch would skip the click for some taps", 1,
        TestSources.depthOf(onClickName, "cb.performClick()"));
    // A duplicate of OnClickCheckbox's logic here would drift from it; the row must delegate.
    assertFalse("must not duplicate the delete-selection bookkeeping",
        onClickName.contains("toBeDeleted"));
    assertFalse("must not duplicate the picker result",
        onClickName.contains("ACTION_SELECT"));
  }
}
