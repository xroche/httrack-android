package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The finish message is concatenated HTML rendered in two places, one of which cannot follow a
 *  link. Html.fromHtml and the spans it yields need an Android runtime, so these read the source:
 *  they pin what a regression would have to look like, not what the panel draws. */
public class FinishedMessageTest {
  /** A path accessor, whatever the variable it hangs off. */
  private static final Pattern PATH_ACCESSOR =
      Pattern.compile("\\w+\\.get(?:Absolute)?Path\\(\\)");

  private static String source() throws IOException {
    return TestSources.javaSource("HTTrackActivity");
  }

  /** Source with every whitespace run flattened, so a match ignores wrapping. */
  private static String flattened(final String source) {
    return source.replaceAll("\\s+", " ");
  }

  /** Each statement writing the finish message, up to its semicolon. Scoped to the crawl runner:
   *  another local named message elsewhere goes to a Toast, which renders no HTML. */
  private static List<String> messageStatements(final String source) {
    final int from = source.indexOf("protected void runInternal()");
    final int to = source.indexOf("displayFinishedPanel(displayMessage, errorsCount, mirrorFolder)");
    assertTrue("runInternal no longer bounded by its displayFinishedPanel call",
        from != -1 && to > from);
    final List<String> statements = new ArrayList<String>();
    final Matcher m = Pattern.compile("(?m)^\\s*message\\s*\\+?=")
        .matcher(source.substring(from, to));
    while (m.find()) {
      statements.add(source.substring(from + m.start(), source.indexOf(';', from + m.start())));
    }
    assertFalse("no finish message assignments found", statements.isEmpty());
    return statements;
  }

  /** The statement building the "Mirror copied in" line. */
  private static String mirrorPathStatement(final String source) {
    final int at = source.indexOf("Mirror copied in");
    assertTrue("no mirror path line left in HTTrackActivity", at != -1);
    return source.substring(at, source.indexOf(';', at));
  }

  /** A base path is user-chosen, so an unescaped '&' or '<' would corrupt the render. */
  @Test
  public void everyPathReachingTheMessageIsEscaped() throws Exception {
    boolean anyPath = false;
    for (final String statement : messageStatements(source())) {
      final Matcher m = PATH_ACCESSOR.matcher(statement);
      while (m.find()) {
        anyPath = true;
        assertTrue(statement,
            statement.substring(0, m.start()).endsWith("TextUtils.htmlEncode("));
      }
    }
    assertTrue("no path reaches the finish message, so nothing was checked", anyPath);
  }

  /** An unclosed anchor makes Html.fromHtml run the link to the end of the message. */
  @Test
  public void theMirrorPathIsWrappedInAClosedHref() throws Exception {
    final String statement = mirrorPathStatement(source());
    assertTrue(statement, statement.contains("<a href="));
    assertTrue(statement, statement.contains("MIRROR_FOLDER_HREF"));
    assertTrue(statement, statement.contains("</a>"));
  }

  /** A styled span nothing can tap is the likeliest regression, so the movement method must
   *  follow the spans actually rendered rather than the folder the panel was handed. */
  @Test
  public void theMovementMethodFollowsTheRenderedSpans() throws Exception {
    final String flat = flattened(source());
    assertTrue(flat, flat.contains("setMovementMethod( text.getSpans(0, text.length(), "
        + "ClickableSpan.class).length != 0 ? LinkMovementMethod.getInstance() : null)"));
  }

  /** The notification cannot follow a tap, so it is the caller that must pass no folder. */
  @Test
  public void onlyThePanelTakesTheFolder() throws Exception {
    final String flat = flattened(source());
    assertTrue(flat, flat.contains("renderFinishedMessage(displayMessage, mirrorFolder)"));
    assertTrue(flat, flat.contains("sendSystemNotification(current, finished + \": \" + name, "
        + "renderFinishedMessage(displayMessage, null))"));
  }

  /** Stripping every link would turn a URL in an engine error message into a second folder tap
   *  target, so only our own href may be rewritten. */
  @Test
  public void onlyOurOwnHrefIsRewritten() throws Exception {
    assertTrue(flattened(source()).contains("if (!MIRROR_FOLDER_HREF.equals(link.getURL()))"));
  }

  /** Rendering the message raw would leave a link in the notification that nothing can tap. */
  @Test
  public void nothingRendersTheMessageOutsideTheHelper() throws Exception {
    assertFalse(flattened(source()).contains("Html.fromHtml(displayMessage"));
  }

  /** Private storage resolves no folder intent, so both entry points need the clipboard fallback. */
  @Test
  public void bothFolderEntryPointsShareTheFallback() throws Exception {
    final String source = source();
    assertTrue(source.contains("openFolderOrCopyPath(getProjectRootFile())"));
    assertTrue(source.contains("openFolderOrCopyPath(folder)"));
  }
}
