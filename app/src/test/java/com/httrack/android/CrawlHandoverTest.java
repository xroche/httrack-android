package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** The wiring that carries a job-owned crawl back to a window. What the session itself does is
 *  exercised in MirrorSessionTest; what is left here is the wiring no unit test can run, because
 *  it is a lifecycle callback. */
public class CrawlHandoverTest {
  private static String activity() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
  }

  /** Body of the method whose declaration starts with SIGNATURE, braces balanced. */
  private static String body(final String source, final String signature) {
    return TestSources.balancedBlock(source,
        TestSources.indexOf(source, signature) + signature.length());
  }

  private static String norm(final String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  /** The window registers itself while visible, and only the crawl it left can be told so. */
  @Test
  public void onStartRegistersTheListenerAndOnStopClearsIt() throws IOException {
    final String source = activity();
    final String start = body(source, "protected void onStart()");
    assertEquals("one registration", 1,
        TestSources.occurrences(start, "MirrorSession.get().listen(sessionListener);"));
    assertEquals("registered inside a branch, a cold launch would never attach", 0,
        TestSources.depthOf(start, "MirrorSession.get().listen(sessionListener);"));
    assertTrue("a window that draws before it listens misses every refresh in between",
        TestSources.indexOf(start, "listen(sessionListener);")
            < TestSources.indexOf(start, "attachToLiveCrawl();"));
    assertTrue("formatProgress reads strings the attach needs cached",
        TestSources.indexOf(start, "cacheProgressStrings();")
            < TestSources.indexOf(start, "attachToLiveCrawl();"));

    final String stop = body(source, "protected void onStop()");
    assertEquals("it must give back the slot it took, and name itself doing so", 1,
        TestSources.occurrences(stop, "MirrorSession.get().unlisten(sessionListener);"));
    assertEquals("cleared inside a branch, a stopped window keeps drawing", 0,
        TestSources.depthOf(stop, "MirrorSession.get().unlisten(sessionListener);"));

    assertEquals("one listener, or two windows would fight over the slot", 1,
        TestSources.occurrences(source, "MirrorSession.get().listen("));
    assertEquals(1, TestSources.occurrences(source, "MirrorSession.get().unlisten("));
  }

  /** Each report has one renderer, and a swap between them is what this pins. */
  @Test
  public void theListenerRendersEachReportWhereItBelongs() throws IOException {
    final String listener = TestSources.balancedBlock(activity(), TestSources.indexOf(activity(),
        "private final MirrorSession.Listener sessionListener = new MirrorSession.Listener()"));
    assertEquals("lines", norm(TestSources.arguments(
        body(listener, "public void onProgressLines(final String[] lines)"),
        "setProgressLines")));
    assertEquals("formatProgress(stats)", norm(TestSources.arguments(
        body(listener, "public void onStats(final HTTrackStats stats)"), "setProgressLines")));
    assertEquals("verdict.message, verdict.errorsCount, verdict.mirrorFolder",
        norm(TestSources.arguments(
            body(listener, "public void onFinished(final MirrorSession.Verdict verdict)"),
            "displayFinishedPanel")));
  }

  /** The pane is frozen at "Starting worker thread" until this runs, so what it reads and the
   *  order it does it in are both load-bearing. */
  @Test
  public void theAttachAsksThePolicyAndObeysIt() throws IOException {
    final String attach = body(activity(), "private void attachToLiveCrawl()");
    assertEquals("session.live() != null, verdict != null, stats != null, "
        + "MirrorJobService.isPending(this)",
        norm(TestSources.arguments(attach, "HandoverPolicy.attaches")));
    assertEquals("one finished pane", 1,
        TestSources.occurrences(attach, "displayFinishedPanel("));
    assertEquals("one verdict read", 1,
        TestSources.occurrences(attach, "session.heldVerdict()"));
    assertEquals("a verdict taken here is one a pane that never drew it has swallowed", 0,
        TestSources.occurrences(attach, "takeVerdict()"));
    assertEquals("formatProgress(stats)",
        norm(TestSources.arguments(attach, "setProgressLines")));
    assertTrue("both answers must select a branch", attach.contains("case FINISHED:")
        && attach.contains("case PROGRESS:"));
    assertEquals("the attach must be onStart's own, not a branch's", 0,
        TestSources.depthOf(body(activity(), "protected void onStart()"), "attachToLiveCrawl();"));
  }

  /** From API 34 on there is no fragment, so a stop that only knows the fragment is inert. */
  @Test
  public void theStopReachesWhicheverOwnerHoldsTheEngine() throws IOException {
    final String source = activity();
    final String stop = body(source, "private boolean stopCrawl(final boolean force)");
    assertEquals("the fragment owns it below 34, and force must reach it unchanged", 1,
        TestSources.occurrences(stop, "return runner.stopMirror(force);"));
    assertEquals("the session owns it from 34 on, and force must reach that one too", 1,
        TestSources.occurrences(stop, "return crawl.stopMirror(force);"));
    assertTrue("the fragment must be preferred, or a below-34 stop skips the fragment's own end",
        TestSources.indexOf(stop, "runner.stopMirror(force)")
            < TestSources.indexOf(stop, "MirrorSession.get().live()"));

    final String click = body(source, "public void onClickStop(final View view)");
    assertTrue("the button must admit a crawl no fragment owns",
        norm(click).contains("if (runner != null || MirrorSession.get().live() != null) {"));
    // Scoped to the two-press branch, since the abandon below is a third stopCrawl call.
    final String presses = TestSources.balancedBlock(click,
        TestSources.indexOf(click, "if (runner != null || MirrorSession.get().live() != null)"));
    assertEquals("one soft stop", 1, TestSources.occurrences(presses, "stopCrawl(false);"));
    assertEquals("one hard stop", 1, TestSources.occurrences(presses, "stopCrawl(true);"));
    assertEquals("the soft stop belongs to the first press only", 1,
        TestSources.depthOf(presses, "stopCrawl(false);"));
    assertEquals("the hard stop belongs to the second press only", 1,
        TestSources.depthOf(presses, "stopCrawl(true);"));
    assertTrue("the first press is the soft one",
        TestSources.indexOf(presses, "stopCrawl(false);")
            < TestSources.indexOf(presses, "stopCrawl(true);"));

    assertEquals("the finished pane must stop whichever owner still holds the engine", 1,
        TestSources.occurrences(TestSources.between(body(source, "protected void onEnterNewPane()"),
            "case R.layout.activity_mirror_finished:", "break;"), "stopCrawl(true);"));
  }

  /** The finished pane is drawn from a posted message, so a verdict taken before that message
   *  runs is one an activity that dies first swallows. */
  @Test
  public void theVerdictIsHeldUntilThePaneHasDrawnIt() throws IOException {
    final String source = activity();
    final String panel = body(source,
        "protected void displayFinishedPanel(final String displayMessage,");
    assertEquals("one clear", 1,
        TestSources.occurrences(panel, "MirrorSession.get().takeVerdict();"));
    assertTrue("cleared before the pane is set, a message that never ran would still lose it",
        TestSources.indexOf(panel, "setPane(LAYOUT_FINISHED);")
            < TestSources.indexOf(panel, "MirrorSession.get().takeVerdict();"));
    assertEquals("the clear belongs inside the posted runnable, not beside the post", 2,
        TestSources.depthOf(panel, "MirrorSession.get().takeVerdict();"));
    assertEquals("one place clears it, or a second would empty the slot before a pane drew it", 1,
        TestSources.occurrences(source, "takeVerdict()"));
  }

  /** setRequiredNetworkType(NETWORK_TYPE_ANY) still requires a network, so offline the job is
   *  scheduled and sits there. No owner holds an engine, and nothing but the cancel ends it. */
  @Test
  public void aCrawlThatNeverStartedCanStillBeAbandoned() throws IOException {
    final String source = activity();
    final String stop = body(source, "private boolean stopCrawl(final boolean force)");
    assertEquals("one cancel, and only where no owner answered", 1,
        TestSources.occurrences(stop, "MirrorJobService.cancel(this);"));
    assertTrue("a cancel ahead of the owners would hard-stop a crawl that is running",
        TestSources.indexOf(stop, "crawl.stopMirror(force)")
            < TestSources.indexOf(stop, "MirrorJobService.cancel(this);"));
    assertEquals("one way to the cancel, or a caller could skip the no-owner check", 1,
        TestSources.occurrences(source, "MirrorJobService.cancel("));
    assertEquals("MirrorSession.get().live() != null, MirrorJobService.isPending(this)",
        norm(TestSources.arguments(body(source, "private boolean waitsForNetwork()"),
            "HandoverPolicy.waitsForNetwork")));

    final String click = body(source, "public void onClickStop(final View view)");
    assertTrue("the button must admit a crawl that has not started",
        norm(click).contains("if (runner == null && waitsForNetwork()) {"));
    assertTrue("the two-press branch reads no owner and would do nothing, so it cannot come first",
        TestSources.indexOf(click, "waitsForNetwork()")
            < TestSources.indexOf(click, "MirrorSession.get().live() != null"));
    assertEquals("the abandon is a hard one: there is nothing to let finish", 2,
        TestSources.occurrences(click, "stopCrawl(true);"));
    assertEquals("the pane the user came from is where an abandoned crawl leaves them", 1,
        TestSources.occurrences(click, "setPane(LAYOUT_PROJECT_SETUP);"));
    assertEquals("the abandon must be the method's own, not nested in the two-press branch", 1,
        TestSources.depthOf(click, "setPane(LAYOUT_PROJECT_SETUP);"));
  }

  /** "Starting worker thread" is a lie while the scheduler holds the job for want of a network,
   *  and the pane is the only thing the user can see. */
  @Test
  public void theWaitForANetworkIsWhatThePaneSays() throws IOException {
    final String source = activity();
    final String branch = TestSources.between(body(source, "protected void onEnterNewPane()"),
        "case R.layout.activity_mirror_progress:", "case R.layout.activity_mirror_finished:");
    assertTrue("the line cannot know which wait it is until the owner is chosen",
        TestSources.indexOf(branch, "scheduleMirrorJob()")
            < TestSources.indexOf(branch, "R.string.waiting_for_network"));
    assertEquals("two lines, one decision", 1, TestSources.occurrences(norm(branch),
        "getString(waitsForNetwork() ? R.string.waiting_for_network "
        + ": R.string.starting_worker_thread)"));
    final String attach = body(source, "private void attachToLiveCrawl()");
    assertTrue("a window attaching to a job still waiting must say the same thing",
        attach.contains("case WAITING:")
            && attach.contains("getString(R.string.waiting_for_network)"));
    assertTrue("the string the pane names has to exist",
        TestSources.read(TestSources.resFile("values/strings.xml"))
            .contains("<string name=\"waiting_for_network\">"));
  }

  /** Liveness must cover a crawl no fragment owns, and still cover the window between a
   *  fragment being added and its crawl reaching the slot. */
  @Test
  public void livenessReadsBothOwners() throws IOException {
    final String live = body(activity(), "protected boolean hasLiveRunner()");
    assertEquals("the session is what a job-owned crawl answers through", 1,
        TestSources.occurrences(live, "MirrorSession.get().live() != null"));
    assertEquals("dropping the fragment check loses the crawl that has not started yet", 1,
        TestSources.occurrences(live,
            "f instanceof RunnerFragment && ((RunnerFragment) f).hasLiveRunner()"));
    assertEquals("the session check must be the method's own, not a branch's", 0,
        TestSources.depthOf(live, "MirrorSession.get().live() != null"));

    final String source = activity();
    assertEquals("the keep-screen-on flag belongs to the crawl, not to the fragment", 1,
        TestSources.occurrences(body(source, "private void refreshKeepScreenOn()"),
            "hasLiveRunner());"));
    assertEquals("the destination must not be recomputed under either owner", 1,
        TestSources.occurrences(body(source, "protected void onResume()"),
            "if (runner == null && !hasLiveRunner()) {"));
  }

  /** API 24 to 33 keeps the fragment as the only owner, and its onDestroy stop is what ends a
   *  crawl there. Nothing on that path publishes to the session, so the attach above is inert. */
  @Test
  public void theFragmentPathIsUntouchedBelowThirtyFour() throws IOException {
    final String source = activity();
    final String destroy = body(source, "public void onDestroy()");
    assertEquals("the fragment must still hard-stop the crawl it owns", 1,
        TestSources.occurrences(destroy, "runner.stopMirror(true)"));
    assertEquals("and still tell the user it did", 1,
        TestSources.occurrences(destroy, "runner.sendAbortNotification();"));

    final String runner = TestSources.balancedBlock(source, TestSources.indexOf(source,
        "protected static class Runner extends AsyncTask<Void, Integer, Void>"));
    assertEquals("a Runner publishing to the session would render every report twice", 0,
        TestSources.occurrences(runner, "MirrorSession.get().publish"));
    assertEquals("the queued finished pane is the below-34 handover, and it stays", 1,
        TestSources.occurrences(runner, "pendingParentActions.add("));
  }

  /** Only a window can show the finished pane, so a crawl that ends with none attached must say
   *  so some other way, and must not say it twice. */
  @Test
  public void aDetachedEndNotifiesAndAnAttachedOneDoesNot() throws IOException {
    final String job = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("MirrorJobService"));
    final String finished = body(job, "public void onFinished(final String message, "
        + "final long errorsCount, final File mirrorFolder)");
    assertEquals("new MirrorSession.Verdict(message, errorsCount, mirrorFolder)",
        norm(TestSources.arguments(finished, "publishVerdict")));
    assertEquals("one notification", 1,
        TestSources.occurrences(finished, "HTTrackActivity.sendFinishedNotification("));
    assertEquals("getApplicationContext(), projectName, message",
        norm(TestSources.arguments(finished, "HTTrackActivity.sendFinishedNotification")));
    assertTrue("a notification outside the HELD branch would double an attached window's pane",
        TestSources.depthOf(finished, "HTTrackActivity.sendFinishedNotification(") > 0);
    assertTrue("HELD is the only answer that owes the user a notification",
        norm(finished).contains("== HandoverPolicy.Delivery.HELD) {"));

    assertEquals("every refresh must reach the window as well as the notification", 1,
        TestSources.occurrences(body(job, "public void onStats(final HTTrackStats stats)"),
            "MirrorSession.get().publishStats(stats);"));
    assertEquals("and so must the lines shown before the first refresh", 1,
        TestSources.occurrences(body(job, "public void onProgress(final String[] lines)"),
            "MirrorSession.get().publishProgress(lines);"));
  }
}
