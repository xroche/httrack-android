package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Starting a crawl over a live one refuses three ways, and tryLock() reports a lock this JVM
 *  already holds by throwing an IllegalStateException. Caught broadly, that reaches the user as
 *  an engine crash. ProfileLockPolicyTest holds the truth table; these read the wiring that
 *  feeds it, pinning whole argument lists so a dropped or swapped refusal reds. */
public class SecondCrawlTest {
  private static final String POLICY = "ProfileLockPolicy.alreadyInProgress";
  private static final String RUN = "protected void runInternal()";

  /** HTTrackActivity with comments and string literals blanked, so a commented-out call cannot
   *  pass for one and a brace in a literal is not counted. */
  private static String activity() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
  }

  /** Body of the method whose declaration starts with SIGNATURE, braces balanced. */
  private static String body(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    return TestSources.balancedBlock(source, at + signature.length());
  }

  /** Body of the runner's own METHOD; RunnerFragment declares some of the same names, and it is
   *  the trunk rather than the thing that acts. */
  private static String runnerBody(final String signature) throws IOException {
    final String source = activity();
    final int runner = source.indexOf("protected static class Runner extends AsyncTask");
    assertTrue("no Runner class", runner != -1);
    return body(TestSources.balancedBlock(source, runner), signature);
  }

  /** Block following the first TEXT inside BODY. An absent TEXT would otherwise read as offset
   *  -1, and balancedBlock would return the top of the method instead. */
  private static String blockAfter(final String body, final String text) {
    final int at = body.indexOf(text);
    assertTrue("no " + text, at != -1);
    return TestSources.balancedBlock(body, at + text.length());
  }

  /** The run's finally block, the only place a release runs whether the run was refused or not.
   *  Sliced rather than searched, since a release the try holds still reads as present. */
  private static String releases(final String run) {
    assertEquals("one finally, or the slice below takes the wrong block", 1,
        TestSources.occurrences(run, "finally"));
    return blockAfter(run, "finally");
  }

  /** SOURCE with every run of whitespace collapsed to one space. */
  private static String flat(final String source) {
    return source.replaceAll("\\s+", " ").trim();
  }

  /** BODY holds FIRST then SECOND. An absent one reads as -1, which compares as ordered. */
  private static void assertInOrder(final String message, final String body, final String first,
      final String second) {
    assertTrue("no " + first, body.contains(first));
    assertTrue("no " + second, body.contains(second));
    assertTrue(message, body.indexOf(first) < body.indexOf(second));
  }

  /** Brace depth of the first TEXT inside BODY; a statement of the try block reads as one. */
  private static int depthOf(final String body, final String text) {
    final int at = body.indexOf(text);
    assertTrue("no " + text, at != -1);
    int depth = 0;
    for (int i = 0; i < at; i++) {
      if (body.charAt(i) == '{') {
        depth++;
      } else if (body.charAt(i) == '}') {
        depth--;
      }
    }
    return depth;
  }

  /** ARGUMENTS split at top-level commas, whitespace collapsed. */
  private static List<String> split(final String arguments) {
    final List<String> parts = new ArrayList<String>();
    int depth = 0;
    int from = 0;
    for (int i = 0; i < arguments.length(); i++) {
      final char c = arguments.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(flat(arguments.substring(from, i)));
        from = i + 1;
      }
    }
    parts.add(flat(arguments.substring(from)));
    return parts;
  }

  @Test
  public void theSameJvmOverlapIsCaughtByItsOwnType() throws IOException {
    final String run = runnerBody(RUN);
    // OverlappingFileLockException is an IllegalStateException, so only its own name keeps it
    // out of the catch(Throwable) that reports a crash.
    assertTrue("tryLock must run inside a try of its own",
        flat(run).contains("try { lock = outLock.getChannel().tryLock(); }"));
    assertTrue("no catch of OverlappingFileLockException",
        run.contains("catch (final OverlappingFileLockException"));
    assertTrue("a swallowed overlap would let the run start over a locked profile",
        body(run, "catch (final OverlappingFileLockException overlap)")
            .contains("lockOverlapped = true"));
  }

  @Test
  public void allThreeRefusalsReachThePolicy() throws IOException {
    // Naming the call is not enough: any of the three folded to a constant would pass that.
    assertEquals(Arrays.asList("!profileMarked", "lock == null", "lockOverlapped"),
        split(TestSources.arguments(runnerBody(RUN), POLICY)));
  }

  @Test
  public void theRefusalStopsTheRunBeforeTheEngine() throws IOException {
    final String run = runnerBody(RUN);
    assertEquals("the check must be a statement of the run, not a branch of something else", 1,
        depthOf(run, POLICY));
    assertInOrder("the overlap has to be known before the verdict", run,
        "catch (final OverlappingFileLockException", POLICY);
    assertInOrder("a refusal after the engine starts is two crawls, not a message", run, POLICY,
        "engine.main(cargs)");
    assertTrue("the refusal must throw, or the run carries on unlocked",
        flat(blockAfter(run, POLICY)).startsWith("throw new IOException("));
  }

  @Test
  public void theUserIsToldInTheirOwnLanguage() throws IOException {
    assertTrue("the message must be the cached resource", runnerBody(RUN)
        .contains("throw new IOException(string_already_in_progress)"));
    assertEquals("the string is read once, from the parent, like every other message",
        Arrays.asList("R.string.mirror_already_in_progress"),
        split(TestSources.arguments(runnerBody("public synchronized void setParent("),
            "string_already_in_progress = getParentString")));
    // The raw source, since withoutCommentsAndStrings blanks the literal this looks for.
    assertFalse("an English literal cannot be translated",
        TestSources.javaSource("HTTrackActivity").contains("already in progress\""));
    assertTrue("no mirror_already_in_progress string",
        TestSources.read(TestSources.resFile("values/strings.xml"))
            .contains("<string name=\"mirror_already_in_progress\">"));
  }

  @Test
  public void onlyTheRunThatClaimedTheProfileReleasesIt() throws IOException {
    // A refused second run releasing the claim is what lets a third attempt reach tryLock.
    assertEquals("the claim is a yes or no, so nothing else may end the run here",
        "return runningInstances.add(profile.getAbsolutePath());",
        flat(body(activity(),
            "protected static synchronized boolean markRunningInstance(final File profile)")));
    final String run = runnerBody(RUN);
    assertTrue("the claim has to be recorded",
        run.contains("profileMarked = markRunningInstance(profile)"));
    assertEquals("an ungated second clear releases the live run's claim just the same", 1,
        TestSources.occurrences(run, "clearRunningInstance("));
    assertEquals("clearing on a refused run releases the live run's claim",
        "clearRunningInstance(profile);",
        flat(blockAfter(releases(run), "if (profileMarked)")));
  }

  @Test
  public void aRefusedRunLeavesNoOpenHandle() throws IOException {
    // A refusal now reaches tryLock, so the close can no longer hang off holding the lock.
    final String run = runnerBody(RUN);
    assertEquals("the handle must be closed whatever the lock did",
        "try { outLock.close(); } catch (IOException io) { }",
        flat(blockAfter(releases(run), "if (outLock != null)")));
    assertFalse("a close inside the lock branch skips every refused run",
        flat(blockAfter(run, "if (lock != null)")).contains("outLock.close()"));
  }
}
