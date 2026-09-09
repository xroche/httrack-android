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
  /** Posting, and drawing split in two, so a refresh can be timed to land mid-draw. */
  private static final class FakeUiThread {
    final ProgressCoalescer<String> coalescer = new ProgressCoalescer<String>();
    final List<String> drawn = new ArrayList<String>();
    int queued;

    void refresh(final String payload) {
      if (coalescer.offer(payload)) {
        queued++;
      }
    }

    String startDraw() {
      assertTrue("no task was posted", queued > 0);
      queued--;
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
      while (queued > 0) {
        runOneTask();
      }
    }
  }

  @Test
  public void aBurstOfRefreshesPostsOneTaskCarryingTheLastOne() {
    final FakeUiThread ui = new FakeUiThread();
    for (final String frame : new String[] { "1", "2", "3", "4", "5" }) {
      ui.refresh(frame);
    }
    assertEquals(1, ui.queued);
    ui.drain();
    assertEquals(Arrays.asList("5"), ui.drawn);
  }

  @Test
  public void aTaskThatHasRunLetsTheNextRefreshPostAgain() {
    final FakeUiThread ui = new FakeUiThread();
    final List<String> expected = new ArrayList<String>();
    for (int i = 0; i < 100; i++) {
      final String frame = "frame " + i;
      ui.refresh(frame);
      assertEquals(1, ui.queued);
      ui.drain();
      expected.add(frame);
    }
    assertEquals(expected, ui.drawn);
  }

  @Test
  public void aRefreshArrivingMidDrawGetsATaskOfItsOwn() {
    final FakeUiThread ui = new FakeUiThread();
    ui.refresh("first");
    final String drawing = ui.startDraw();
    ui.refresh("last");
    ui.endDraw(drawing);
    ui.drain();
    assertEquals(Arrays.asList("first", "last"), ui.drawn);
  }

  /** Whatever the engine and the looper do, the frame nothing follows must reach the screen. */
  @Test
  public void theLastFrameIsDrawnWhateverTheInterleaving() {
    for (int seed = 0; seed < 64; seed++) {
      final FakeUiThread ui = new FakeUiThread();
      int schedule = seed;
      for (int frame = 0; frame < 20; frame++) {
        ui.refresh("frame " + frame);
        if (schedule % 2 == 0 && ui.queued > 0) {
          ui.runOneTask();
        }
        schedule /= 2;
      }
      ui.drain();
      assertEquals("seed " + seed, "frame 19", ui.drawn.get(ui.drawn.size() - 1));
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

  @Test
  public void theCrawlThreadAndTheUiThreadAgreeOnTheLastFrame() throws InterruptedException {
    final int frames = 5000;
    final ProgressCoalescer<Integer> coalescer = new ProgressCoalescer<Integer>();
    final AtomicInteger queued = new AtomicInteger();
    final Thread crawl = new Thread(new Runnable() {
      @Override
      public void run() {
        for (int frame = 1; frame <= frames; frame++) {
          if (coalescer.offer(Integer.valueOf(frame))) {
            queued.incrementAndGet();
          }
        }
      }
    });
    crawl.start();

    Integer last = null;
    while (crawl.isAlive() || queued.get() > 0) {
      while (queued.get() > 0) {
        queued.decrementAndGet();
        final Integer payload = coalescer.take();
        if (payload != null) {
          last = payload;
        }
      }
    }
    crawl.join();
    assertEquals(Integer.valueOf(frames), last);
  }
}
