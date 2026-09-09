package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * The queue discipline HTTrackActivity.setProgressLines follows, with a counter standing in for
 * the looper no unit test can run.
 */
public class ProgressCoalescerTest {
  /** Every drain pattern over the first six frames of a run. */
  private static final int SCHEDULES = 64;
  private static final int FRAMES_PER_SCHEDULE = 20;
  private static final int CONCURRENT_FRAMES = 5000;

  private static String frame(final int index) {
    return "frame " + index;
  }

  /** Posting, and drawing split in two, so a refresh can be timed to land mid-draw. */
  private static final class FakeUiThread {
    final ProgressCoalescer<String> coalescer = new ProgressCoalescer<String>();

    /** What the drawing task has put on screen, oldest first. */
    final List<String> drawn = new ArrayList<String>();

    /** Tasks posted and not yet run, counted atomically because the crawl thread offers too. */
    private final AtomicInteger postedTasks = new AtomicInteger();

    void refresh(final String payload) {
      if (coalescer.offer(payload)) {
        postedTasks.incrementAndGet();
      }
    }

    int postedTasks() {
      return postedTasks.get();
    }

    String startDraw() {
      postedTasks.decrementAndGet();
      return coalescer.take();
    }

    void endDraw(final String payload) {
      if (payload != null) {
        drawn.add(payload);
      }
    }

    void runOneTask() {
      endDraw(startDraw());
    }

    void drain() {
      while (postedTasks() > 0) {
        runOneTask();
      }
    }

    String lastDrawn() {
      return drawn.get(drawn.size() - 1);
    }
  }

  @Test
  public void aBurstOfRefreshesPostsOneTaskCarryingTheLastOne() {
    final FakeUiThread ui = new FakeUiThread();
    for (int i = 0; i < 5; i++) {
      ui.refresh(frame(i));
    }
    assertEquals(1, ui.postedTasks());
    ui.drain();
    assertEquals(Arrays.asList(frame(4)), ui.drawn);
  }

  @Test
  public void aTaskThatHasRunLetsTheNextRefreshPostAgain() {
    final FakeUiThread ui = new FakeUiThread();
    final List<String> expected = new ArrayList<String>();
    for (int i = 0; i < 100; i++) {
      ui.refresh(frame(i));
      assertEquals(1, ui.postedTasks());
      ui.drain();
      expected.add(frame(i));
    }
    assertEquals(expected, ui.drawn);
  }

  @Test
  public void aRefreshArrivingMidDrawGetsATaskOfItsOwn() {
    final FakeUiThread ui = new FakeUiThread();
    ui.refresh("first");
    final String drawing = ui.startDraw();
    ui.refresh("last");
    assertEquals(1, ui.postedTasks());
    ui.endDraw(drawing);
    ui.drain();
    assertEquals(Arrays.asList("first", "last"), ui.drawn);
  }

  /** Whatever the engine and the looper do, the frame nothing follows must reach the screen. */
  @Test
  public void theLastFrameIsDrawnWhateverTheInterleaving() {
    for (int schedule = 0; schedule < SCHEDULES; schedule++) {
      final FakeUiThread ui = new FakeUiThread();
      int drainAfter = schedule;
      for (int i = 0; i < FRAMES_PER_SCHEDULE; i++) {
        ui.refresh(frame(i));
        if (drainAfter % 2 == 0) {
          ui.runOneTask();
        }
        drainAfter /= 2;
      }
      ui.drain();
      assertEquals("schedule " + schedule, frame(FRAMES_PER_SCHEDULE - 1), ui.lastDrawn());
    }
  }

  @Test
  public void aTaskWhoseFrameWasAlreadyDrawnDrawsNothing() {
    final ProgressCoalescer<String> coalescer = new ProgressCoalescer<String>();
    assertNull(coalescer.take());
    coalescer.offer("frame");
    assertEquals("frame", coalescer.take());
    assertNull(coalescer.take());
  }

  /** What the failed-post branch of setProgressLines rests on. */
  @Test
  public void disarmingDropsThePendingFrameAndLetsTheNextOnePost() {
    final ProgressCoalescer<String> coalescer = new ProgressCoalescer<String>();
    assertTrue(coalescer.offer("stranded"));
    coalescer.disarm();
    assertNull(coalescer.take());
    assertTrue(coalescer.offer("next"));
    assertEquals("next", coalescer.take());
  }

  /** The drawing task tells an empty coalescer apart by the null, so no frame may be one. */
  @Test(expected = NullPointerException.class)
  public void aNullFrameIsRefused() {
    new ProgressCoalescer<String>().offer(null);
  }

  @Test
  public void theCrawlThreadAndTheUiThreadAgreeOnTheLastFrame() throws InterruptedException {
    final FakeUiThread ui = new FakeUiThread();
    final Thread crawl = new Thread(new Runnable() {
      @Override
      public void run() {
        for (int i = 0; i < CONCURRENT_FRAMES; i++) {
          ui.refresh(frame(i));
        }
      }
    });
    crawl.start();
    while (crawl.isAlive()) {
      ui.drain();
    }
    crawl.join();
    ui.drain();
    assertEquals(frame(CONCURRENT_FRAMES - 1), ui.lastDrawn());
  }
}
