package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** What a stopped job does next, over every reason the system can give. */
public class JobStopPolicyTest {
  /** Every reason, answered in one string, so a wrong row cannot hide behind an earlier one. */
  private static String table(final boolean[] answers) {
    final StringBuilder out = new StringBuilder();
    for (int reason = 0; reason <= 16; reason++) {
      out.append(reason).append('=').append(answers[reason]).append(' ');
    }
    return out.toString();
  }

  private static boolean[] reschedules() {
    final boolean[] answers = new boolean[17];
    for (int reason = 0; reason <= 16; reason++) {
      answers[reason] = JobStopPolicy.reschedules(reason);
    }
    return answers;
  }

  private static boolean[] tellsTheUser() {
    final boolean[] answers = new boolean[17];
    for (int reason = 0; reason <= 16; reason++) {
      answers[reason] = JobStopPolicy.tellsTheUser(reason);
    }
    return answers;
  }

  /** Three reasons whose retry cannot help: ours, the Task Manager's, and a restriction that
   *  takes the network bypass away. Everything else retries. */
  @Test
  public void onlyTheThreeHopelessReasonsAbandonTheMirror() {
    assertEquals("0=true 1=false 2=true 3=true 4=true 5=true 6=true 7=true 8=true 9=true "
        + "10=true 11=false 12=true 13=false 14=true 15=true 16=true ",
        table(reschedules()));
  }

  @Test
  public void onlyAnUnaskedAbandonNeedsExplaining() {
    assertEquals("0=false 1=false 2=false 3=false 4=false 5=false 6=false 7=false 8=false "
        + "9=false 10=false 11=true 12=false 13=true 14=false 15=false 16=false ",
        table(tellsTheUser()));
  }

  /** A stop we asked for is the one case where the user already knows. */
  @Test
  public void ourOwnCancelIsSilentAndFinal() {
    assertEquals(false, JobStopPolicy.reschedules(JobStopPolicy.STOP_REASON_CANCELLED_BY_APP));
    assertEquals(false, JobStopPolicy.tellsTheUser(JobStopPolicy.STOP_REASON_CANCELLED_BY_APP));
  }

  /** The constants have to match android.app.job.JobParameters, which the unit suite cannot
   *  load, so the values the plan read off the framework are pinned here. */
  @Test
  public void theReasonsCarryTheFrameworkValues() {
    assertEquals(0, JobStopPolicy.STOP_REASON_UNDEFINED);
    assertEquals(1, JobStopPolicy.STOP_REASON_CANCELLED_BY_APP);
    assertEquals(3, JobStopPolicy.STOP_REASON_TIMEOUT);
    assertEquals(4, JobStopPolicy.STOP_REASON_DEVICE_STATE);
    assertEquals(7, JobStopPolicy.STOP_REASON_CONSTRAINT_CONNECTIVITY);
    assertEquals(11, JobStopPolicy.STOP_REASON_BACKGROUND_RESTRICTION);
    assertEquals(13, JobStopPolicy.STOP_REASON_USER);
    assertEquals(16, JobStopPolicy.STOP_REASON_TIMEOUT_ABANDONED);
  }
}
