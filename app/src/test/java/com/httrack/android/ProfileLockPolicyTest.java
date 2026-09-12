package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Truth table for the profile-conflict decision. The source-text tests next door prove the
 *  run asks here and passes all three refusals; this one proves the answers. */
public class ProfileLockPolicyTest {
  /** Each answer is written out rather than computed, so the table cannot agree with a change
   *  to the expression it checks. Arguments are claimHeldByAnother, lockRefused and
   *  lockOverlapped. A symmetric OR tables the same whichever way the first one is passed. */
  @Test
  public void anyOneRefusalMeansAMirrorIsAlreadyRunning() {
    assertEquals("none", false, ProfileLockPolicy.alreadyInProgress(false, false, false));
    assertEquals("overlapped", true, ProfileLockPolicy.alreadyInProgress(false, false, true));
    assertEquals("refused", true, ProfileLockPolicy.alreadyInProgress(false, true, false));
    assertEquals("refused+overlapped", true,
        ProfileLockPolicy.alreadyInProgress(false, true, true));
    assertEquals("marked", true, ProfileLockPolicy.alreadyInProgress(true, false, false));
    assertEquals("marked+overlapped", true,
        ProfileLockPolicy.alreadyInProgress(true, false, true));
    assertEquals("marked+refused", true, ProfileLockPolicy.alreadyInProgress(true, true, false));
    assertEquals("all three", true, ProfileLockPolicy.alreadyInProgress(true, true, true));
  }
}
