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
      /* The path has to come from a StoragePaths helper and nowhere else. A blocklist of
         external-storage calls was defeated twice, once by a mutant returning an external path
         behind a getFilesDir() test and once by new File(getFilesDir(), "../../../sdcard"),
         so this asks what builds the path instead of what it must not mention. Where those
         helpers put a file is StoragePathsTest's question, over real files. */
      assertTrue(call, body.contains("StoragePaths."));
      assertFalse(call + " builds its own path", body.contains("new File("));
    }
  }

  /**
   * Both crawl paths go through buildCommandline, so the argv decision has to be made there.
   * The job gets the array it builds, and a login left out of that array would make a
   * backgrounded crawl run logged out. What the decision IS lives in BrowserLoginPolicyTest.
   */
  @Test
  public void theBundleFolderReachesTheArgv() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
    final String body = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "public synchronized List<String> buildCommandline()"));
    assertTrue(body, body.contains("BrowserLoginPolicy.withLogin(mapper.buildCommandline()"));
    assertTrue(body, body.contains("StoragePaths.loginBundleDir(getFilesDir()"));
    final String job = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("MirrorJobService"));
    assertTrue(job, job.contains("extras.getStringArray(EXTRA_OPTIONS)"));
  }

  /**
   * The engine writes the bundle. It names the file, puts an international host into ASCII,
   * refuses an address carrying a password, applies its own caps and reads the file back through
   * its own loader, and a name it would refuse stops the whole mirror. None of that may be
   * rebuilt here.
   */
  @Test
  public void theEngineWritesTheBundle() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("BrowserLoginActivity"));
    final String done = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "public void onClickLoginDone("));
    assertTrue(done, done.contains("HTTrackLib.writeLoginBundle(result.text,"));
    // The lookup needs the libraries, and this window opens before any crawl has loaded them.
    assertTrue(done, done.contains("HTTrackLib.loadLibraries()"));
    // Nothing is claimed when the engine refused, because the crawl would then have no login.
    assertTrue(done, done.contains("bundle == null"));
    // The count the user reads is the engine's, not ours.
    assertTrue(done, done.contains("EXTRA_COOKIE_COUNT, bundle.cookies"));
    // No second writer: the app owns no bundle text, no name and no scope.
    for (final String owned : new String[] { "HTTrack bundle", "httrack-bundle=",
        "getRegistrableDomain", "getBundleNameRefusal", "writeOwnerOnly" }) {
      assertFalse(owned, TestSources.javaSource("BrowserLoginActivity").contains(owned));
    }
  }

  /** The hosts the crawl starts from have to reach the cookie sweep. */
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

  /**
   * The engine is given the address the window was opened for, not the one the login ended on.
   * That address is the crawl's own root, so naming it adds no second root, and a login ending
   * on an identity provider cannot put that provider in the list.
   */
  @Test
  public void theBundleIsNamedForTheAddressTheCrawlStartsFrom() throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("BrowserLoginActivity"));
    final String done = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "public void onClickLoginDone("));
    assertTrue(done, done.contains("getUserAgentString(), crawlUrl)"));
    assertFalse("the address the login ended on is not what the crawl starts from",
        done.contains("web.getUrl()"));
    // And that address is the one the window was opened with, kept for the write.
    final String create = TestSources.balancedBlock(source,
        TestSources.indexOf(source, "protected void onCreate("));
    assertTrue(create, create.contains("crawlUrl = url"));
    final String caller = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
    final String apply = TestSources.balancedBlock(caller,
        TestSources.indexOf(caller, "private void applyBrowserLogin("));
    assertTrue(apply, apply.contains("BrowserLoginPolicy.withBundleUrl("));
    assertTrue(apply, apply.contains("BrowserLoginPolicy.withoutBundleNames("));
    /* The widget as well as the map. setMap alone left the old text on screen and the next
       savePaneFields copied it back, so the whole feature was invisible. */
    final String setter = TestSources.balancedBlock(caller,
        TestSources.indexOf(caller, "private void setAddressField("));
    assertTrue(setter, setter.contains("setMap(R.id.fieldWebsiteURLs, addresses)"));
    assertTrue(setter, setter.contains("setFieldText(R.id.fieldWebsiteURLs, addresses)"));
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
