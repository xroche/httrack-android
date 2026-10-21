package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Guards on the browser login's wiring. These read the sources and resources, so they are
 * lint-shaped: they see a name that went missing, never a wrong decision. The decisions live in
 * BrowserLoginPolicy and BrowserLoginCookies, which are tested by running them.
 */
public class BrowserLoginWiringTest {
  /** An undeclared activity is an ActivityNotFoundException the moment the button is tapped. */
  @Test
  public void theManifestDeclaresTheLoginActivity() throws IOException {
    final String manifest = TestSources.read(TestSources.mainFile("AndroidManifest.xml"));
    assertTrue(manifest.contains("com.httrack.android.BrowserLoginActivity"));
    assertTrue(manifest.contains("@string/title_activity_browser_login"));
  }

  /** An android:onClick with no matching public method crashes when the view is tapped. */
  @Test
  public void everyOnClickOfTheLoginScreensResolves() throws IOException {
    assertHandled("activity_browser_login", "BrowserLoginActivity");
    assertHandled("activity_proj_setup", "HTTrackActivity");
  }

  private static void assertHandled(final String layout, final String owner) throws IOException {
    final String source = TestSources.javaSource(owner);
    int seen = 0;
    // Every qualified variant too: each one is a layout the activity may inflate.
    for (final java.io.File file : TestSources.layouts(layout)) {
      final Matcher click = Pattern.compile("android:onClick=\"([^\"]+)\"")
          .matcher(TestSources.read(file));
      while (click.find()) {
        final String method = click.group(1);
        assertTrue(owner + " has no " + method,
            source.contains("public void " + method + "(final View "));
        seen++;
      }
    }
    assertTrue(layout + " declares no onClick", seen != 0);
  }

  /** lint does not abort the build here, so a missing string is only found by tapping. */
  @Test
  public void everyStringTheLoginUsesIsDeclared() throws IOException {
    final String strings = TestSources.read(TestSources.resFile("values/strings.xml"));
    final List<String> names = new ArrayList<String>();
    for (final String file : new String[] { "layout/activity_browser_login.xml",
        "menu/browser_login.xml" }) {
      collect(TestSources.read(TestSources.resFile(file)), "@string/([A-Za-z0-9_]+)", names);
    }
    for (final String owner : new String[] { "BrowserLoginActivity", "HTTrackActivity" }) {
      collect(TestSources.javaSource(owner), "R\\.string\\.(browser_login[A-Za-z0-9_]*)", names);
    }
    // The two resources name ten today, so a collection that silently broke reads as a failure.
    assertTrue("only " + names.size() + " strings found", names.size() >= 10);
    for (final String name : names) {
      assertTrue("no <string name=\"" + name + "\">",
          strings.contains("<string name=\"" + name + "\">"));
    }
  }

  private static void collect(final String text, final String regex, final List<String> into) {
    final Matcher match = Pattern.compile(regex).matcher(text);
    while (match.find()) {
      into.add(match.group(1));
    }
  }

  /**
   * The login window's hardening, each a decision a later edit could undo in silence.
   */
  @Test
  public void theLoginWindowStaysLockedDown() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("BrowserLoginActivity"));
    // A bridge object would hand the logged-in page our own code.
    assertFalse(source.contains("addJavascriptInterface"));
    // Overriding this is how a WebView ends up accepting any certificate for a password form.
    assertFalse(source.contains("onReceivedSslError"));
    for (final String locked : new String[] { "setAllowFileAccess(false)",
        "setAllowContentAccess(false)" }) {
      assertTrue(locked, source.contains(locked));
    }
  }

  /** The file holds a live session, so it must not land where other apps read the mirrors. */
  @Test
  public void theCookieFileGoesToPrivateStorage() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
    final String body = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "private File getLoginCookiesFile()"));
    assertTrue(body.contains("getFilesDir()"));
    assertFalse(body.contains("getProjectRootFile()"));
    assertFalse(body.contains("getTargetFile()"));
  }

  /** The delete flow must wait for a crawl, or the engine recreates the login it just removed. */
  @Test
  public void cleanupWaitsForALiveCrawl() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
    final String body = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "public void onCleanup("));
    /* The whole call, because argument order is what a grep otherwise cannot see, and these
       two booleans transpose silently. Phrased as a refusal, so there is no "!" to invert. */
    assertTrue(body, body.contains(
        "if (CleanupTitlePolicy.refusesDelete(isCrawlLive(), names != null"));
    // The button's enabled state has to ask the same question, or it goes stale on its own.
    assertTrue(source, source.contains(
        "!CleanupTitlePolicy.refusesDelete(isCrawlLive(), hasProjectNames())"));
    // The button's enabled state alone is not a guard, because performClick ignores it.
    final String live = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "private boolean isCrawlLive()"));
    assertTrue(live, live.contains("hasLiveRunner()"));
    // A connectivity stop empties the session and re-arms the job, so pending counts as live.
    assertTrue(live, live.contains("MirrorJobService.isPending(this)"));
  }

  /** Deleting a project has to take its saved login with it, or a live session is orphaned. */
  @Test
  public void deletingAProjectForgetsItsLogin() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("CleanupActivity"));
    final String body = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "protected void deleteProjects()"));
    assertTrue(body, body.contains("forgetLogin(projects[position])"));
    // Gated on the mirror being gone, so a folder deleted outside the app still drops it.
    assertTrue(body, body.contains("!project.exists()"));
    // The path comes from the one helper both callers use, never a second copy.
    final String forget = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "private void forgetLogin("));
    assertTrue(forget, forget.contains("StoragePaths.savedLoginFiles("));
  }

  /** The staged name must come from the shared helper, so the delete pattern can match it. */
  @Test
  public void theStagedWriteBuildsItsNameFromTheSharedHelper() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("BrowserLoginActivity"));
    final String body = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "static boolean writeOwnerOnly("));
    assertTrue(body, body.contains("StoragePaths.stagedLoginFile(file)"));
    // On the staged file, because calling the helper and then writing elsewhere saves nothing.
    assertTrue(body, body.contains("FileOutputStream(staged)"));
    assertTrue(body, body.contains("staged.renameTo(file)"));
  }

  /** Without the landed address the user has to retype where the login took them. */
  @Test
  public void theLandedAddressIsReturnedAndShown() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("BrowserLoginActivity"));
    final String done = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "public void onClickLoginDone("));
    // The WebView's own address, not the one the activity was started with.
    assertTrue(done, done.contains("EXTRA_LANDED_URL, web.getUrl()"));
    final String caller = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
    final String apply = TestSources.balancedBlock(caller,
        TestSources.indexOf(caller, "private void applyBrowserLogin("));
    assertTrue(apply, apply.contains("BrowserLoginPolicy.withLandedUrl("));
    assertTrue(apply, apply.contains("setMap(R.id.fieldWebsiteURLs, addresses)"));
    /* The widget as well as the map. setMap alone left the old text on screen and the next
       savePaneFields copied it back, so the whole feature was invisible. */
    assertTrue(apply, apply.contains("setFieldText(R.id.fieldWebsiteURLs, addresses)"));
  }

  /** -%K is read only when the engine accepts cookies, so the hand-off has to tick the box. */
  @Test
  public void theHandOverTurnsCookiesOn() throws IOException {
    // The raw source, because the value is the point here and blanking literals would hide it.
    final String source = TestSources.javaSource("HTTrackActivity");
    final String blanked = TestSources.withoutCommentsAndStrings(source);
    final int at = TestSources.indexOf(blanked, "private void applyBrowserLogin(");
    final String body = source.substring(at,
        at + TestSources.balancedBlock(blanked, at).length());
    assertTrue(body, body.contains("setMap(R.id.checkAcceptCookies, \"1\")"));
    assertTrue(body, body.contains("setMap(R.id.editCookiesFile,"));
    assertTrue(body, body.contains("setMap(R.id.editBrowserIdentity,"));
  }
}
