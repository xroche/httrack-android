package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Truth tables for the decisions a recovered fault forces. The source-text tests next door
 *  prove the activity delegates here; these prove the answers. */
public class NativeFaultPolicyTest {
  private static final int FINISHED = 4;
  private static final int SETUP = 1;
  private static final int PROGRESS = 3;

  @Test
  public void nothingClosesWhileTheEngineIsHealthy() {
    for (final int from : new int[] { SETUP, PROGRESS, FINISHED }) {
      for (final int to : new int[] { SETUP, PROGRESS, FINISHED }) {
        assertFalse(from + "->" + to,
            NativeFaultPolicy.closeOnPaneChange(false, from, to, FINISHED));
      }
    }
  }

  @Test
  public void onlyLeavingTheReportingPanelCloses() {
    assertTrue(NativeFaultPolicy.closeOnPaneChange(true, FINISHED, SETUP, FINISHED));
    assertTrue(NativeFaultPolicy.closeOnPaneChange(true, FINISHED, PROGRESS, FINISHED));
    // Raising the panel, and re-entering it after a rotation, must both survive.
    assertFalse(NativeFaultPolicy.closeOnPaneChange(true, FINISHED, FINISHED, FINISHED));
    assertFalse(NativeFaultPolicy.closeOnPaneChange(true, PROGRESS, FINISHED, FINISHED));
    // A recreated activity restores its pane from -1, before it has one.
    assertFalse(NativeFaultPolicy.closeOnPaneChange(true, -1, FINISHED, FINISHED));
    assertFalse(NativeFaultPolicy.closeOnPaneChange(true, -1, SETUP, FINISHED));
  }

  @Test
  public void aConfigurationChangeKeepsTheProcess() {
    // isFinishing() is false for a rotation, whatever else is true.
    assertFalse(NativeFaultPolicy.exitOnDestroy(false, true, true));
    assertFalse(NativeFaultPolicy.exitOnDestroy(false, false, true));
    assertFalse(NativeFaultPolicy.exitOnDestroy(false, true, false));
  }

  @Test
  public void aFinishAfterAFaultOrOnRequestEndsTheProcess() {
    assertTrue(NativeFaultPolicy.exitOnDestroy(true, false, true));
    assertTrue(NativeFaultPolicy.exitOnDestroy(true, true, false));
    assertTrue(NativeFaultPolicy.exitOnDestroy(true, true, true));
    // An ordinary exit from a healthy app is still an ordinary exit.
    assertFalse(NativeFaultPolicy.exitOnDestroy(true, false, false));
  }

  @Test
  public void aFaultLatchedAwayFromTheReportingPanelIsRaised() {
    // A cleanup or a browse-all rebuild latches with nothing on screen to say so.
    assertTrue(NativeFaultPolicy.reportOnResume(true, SETUP, FINISHED));
    assertTrue(NativeFaultPolicy.reportOnResume(true, PROGRESS, FINISHED));
    // Already reported: raising it again would loop on every resume.
    assertFalse(NativeFaultPolicy.reportOnResume(true, FINISHED, FINISHED));
    assertFalse(NativeFaultPolicy.reportOnResume(false, SETUP, FINISHED));
  }

  @Test
  public void closingAndExitingAreNotTheSameQuestion() {
    // Leaving the panel closes the activity; only the destruction ends the process.
    assertTrue(NativeFaultPolicy.closeOnPaneChange(true, FINISHED, SETUP, FINISHED));
    assertEquals(false, NativeFaultPolicy.exitOnDestroy(false, true, true));
  }
}
