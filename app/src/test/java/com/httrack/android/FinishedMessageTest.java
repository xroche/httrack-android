package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** The finish message is concatenated HTML rendered in two places, one of which cannot follow a
 *  link. */
public class FinishedMessageTest {
  private static String source() throws IOException {
    return TestSources.javaSource("HTTrackActivity");
  }

  /** The statement building the "Mirror copied in" line. */
  private static String mirrorPathStatement(final String source) {
    final int at = source.indexOf("Mirror copied in");
    assertTrue("no mirror path line left in HTTrackActivity", at != -1);
    return source.substring(at, source.indexOf(';', at));
  }

  /** A base path is user-chosen, so an unescaped '&' or '<' would corrupt the render. */
  @Test
  public void theMirrorPathIsEscapedBeforeItReachesTheHtml() throws Exception {
    final String line = mirrorPathStatement(source());
    assertTrue(line, line.contains("TextUtils.htmlEncode(target.getAbsolutePath())"));
    assertFalse(line, line.contains("+ target.getAbsolutePath()"));
  }

  @Test
  public void theMirrorPathIsWrappedInTheFolderHref() throws Exception {
    final String line = mirrorPathStatement(source());
    assertTrue(line, line.contains("<a href="));
    assertTrue(line, line.contains("MIRROR_FOLDER_HREF"));
  }

  /** Rendering the message raw would leave a link in the notification that nothing can tap. */
  @Test
  public void nothingRendersTheMessageOutsideTheHelper() throws Exception {
    assertFalse(source().contains("Html.fromHtml(displayMessage)"));
  }

  /** Private storage resolves no folder intent, so both entry points need the clipboard fallback. */
  @Test
  public void bothFolderEntryPointsShareTheFallback() throws Exception {
    final String source = source();
    assertTrue(source.contains("openFolderOrCopyPath(getProjectRootFile())"));
    assertTrue(source.contains("openFolderOrCopyPath(folder)"));
  }
}
