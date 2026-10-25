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

  /** The bundle holds a live session, so it must not land where other apps read the mirrors. */
  @Test
  public void theLoginFilesGoToPrivateStorage() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
    for (final String call : new String[] { "private File getLoginCookiesFile()",
        "public void onClickBrowserLogin(", "public synchronized List<String> buildCommandline()" }) {
      final String body = TestSources.balancedBlock(source, TestSources.indexOf(source, call));
      assertTrue(call, body.contains("getFilesDir()"));
      assertFalse(call, body.contains("getProjectRootFile()"));
      assertFalse(call, body.contains("getTargetFile()"));
    }
  }

  /**
   * The engine looks for a bundle beside the mirror unless it is told otherwise, and a mirror
   * folder is readable by every app, so the crawl has to name the private folder itself.
   */
  @Test
  public void theBundleFolderReachesTheArgv() throws IOException {
    // The raw source, because the option's own spelling is the point of this one.
    final String source = TestSources.javaSource("HTTrackActivity");
    final String blanked = TestSources.withoutCommentsAndStrings(source);
    final int at = source.indexOf('{',
        TestSources.indexOf(blanked, "public synchronized List<String> buildCommandline()"));
    final String body = source.substring(at, at + TestSources.balancedBlock(blanked, at).length());
    assertTrue(body, body.contains("StoragePaths.loginBundleDir("));
    /* The option and its value as separate tokens, which is the only form the engine's parser
       reads: it compares argv[na] and then takes the next one. */
    final int option = body.indexOf("options.add(\"--bundle-dir\")");
    assertTrue(body, option != -1);
    assertTrue(body, body.indexOf("options.add(bundles.getAbsolutePath())", option) != -1);
    /* An address naming a bundle that is gone would make the engine refuse to run at all, so
       every token is mapped before the option goes out. */
    assertTrue(body, body.contains("BrowserLoginBundle.withoutMissingBundle(options.get(i)"));
    /* Both crawl paths go through here: the job gets the array this builds, so a login left out
       of it would make a backgrounded crawl run logged out. */
    final String job = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("MirrorJobService"));
    assertTrue(job, job.contains("extras.getStringArray(EXTRA_OPTIONS)"));
  }

  /** A name the engine refuses ABORTS the mirror, so only its own verdict may choose one. */
  @Test
  public void theBundleNameIsTheEnginesVerdict() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("BrowserLoginActivity"));
    final String body = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "private static String bundleName("));
    assertTrue(body, body.contains("HTTrackLib.getRegistrableDomain(host)"));
    assertTrue(body, body.contains("HTTrackLib.getBundleNameRefusal(name) == null"));
    // Nothing is written when the engine refuses every name, because a crawl would then stop.
    assertTrue(body, body.contains("return null"));
    // The lookups need the libraries, and this window opens before any crawl has loaded them.
    assertTrue(body, body.contains("HTTrackLib.loadLibraries()"));
  }

  /**
   * getCookie answers for any host, so a crawl host sharing a parent domain with a visited one
   * still yields that parent's cookies. Asking only the visited hosts leaves those behind.
   */
  @Test
  public void theCrawlsOwnHostsAreAskedForCookies() throws IOException {
    final String caller = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
    final String open = TestSources.balancedBlock(caller,
        TestSources.indexOf(caller, "public void onClickBrowserLogin("));
    assertTrue(open, open.contains("BrowserLoginPolicy.fieldHosts(field)"));
    assertTrue(open, open.contains("BrowserLoginActivity.EXTRA_CRAWL_HOSTS"));
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("BrowserLoginActivity"));
    final String done = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "public void onClickLoginDone("));
    assertTrue(done, done.contains("asked.addAll(crawlHosts)"));
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
    assertTrue(done, done.contains("landed = web.getUrl()"));
    assertTrue(done, done.contains("EXTRA_LANDED_URL, landed"));
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

  /**
   * A bundle's cookies reach the jar only when the engine accepts cookies, and with the box
   * unticked there is no jar to put them in. The crawl then runs logged out, and the only sign
   * is a warning Android hides, so the hand-off ticks the box itself.
   *
   * The -%K file is named for a second reason: the engine saves its jar at the end of the
   * crawl, and with no file named it saves it beside the mirror, in shared storage.
   */
  @Test
  public void theHandOverTurnsCookiesOnAndKeepsTheJarPrivate() throws IOException {
    // The raw source, because the value is the point here and blanking literals would hide it.
    final String source = TestSources.javaSource("HTTrackActivity");
    final String blanked = TestSources.withoutCommentsAndStrings(source);
    /* From the brace, because balancedBlock leaves the outer pair out: slicing the raw source
       from the declaration instead would cut the same number of bytes off the END, and the
       refusal below would then pass on a body it never read. */
    final int at = source.indexOf('{',
        TestSources.indexOf(blanked, "private void applyBrowserLogin("));
    final String body = source.substring(at, at + TestSources.balancedBlock(blanked, at).length());
    assertTrue(body, body.contains("setMap(R.id.checkAcceptCookies, \"1\")"));
    assertTrue(body, body.contains("setMap(R.id.editCookiesFile,"));
    // The browser's identity rides in the bundle, which sends it to that bundle's sites alone.
    assertFalse(body, body.contains("setMap(R.id.editBrowserIdentity,"));
  }
}
