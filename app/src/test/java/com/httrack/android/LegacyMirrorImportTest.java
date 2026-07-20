package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the copy walk over an in-memory tree: fidelity, idempotency, and error tolerance. */
public class LegacyMirrorImportTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  /** In-memory Source: a directory holds children; a file holds bytes, or throws if poisoned. */
  private static final class FakeNode implements LegacyMirrorImport.Source {
    final String name;
    final boolean dir;
    final byte[] data;
    final boolean poisoned;
    final List<LegacyMirrorImport.Source> kids = new ArrayList<LegacyMirrorImport.Source>();

    static FakeNode dir(final String name) {
      return new FakeNode(name, true, null, false);
    }

    static FakeNode file(final String name, final String body) {
      return new FakeNode(name, false, body.getBytes(StandardCharsets.UTF_8), false);
    }

    static FakeNode poison(final String name) {
      return new FakeNode(name, false, null, true);
    }

    private FakeNode(final String name, final boolean dir, final byte[] data, final boolean poisoned) {
      this.name = name;
      this.dir = dir;
      this.data = data;
      this.poisoned = poisoned;
    }

    FakeNode with(final LegacyMirrorImport.Source... children) {
      for (final LegacyMirrorImport.Source c : children) {
        kids.add(c);
      }
      return this;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean isDirectory() {
      return dir;
    }

    @Override
    public long length() {
      return data != null ? data.length : 0;
    }

    @Override
    public List<LegacyMirrorImport.Source> children() {
      return kids;
    }

    @Override
    public InputStream openStream() throws IOException {
      if (poisoned) {
        throw new IOException("poisoned " + name);
      }
      return new ByteArrayInputStream(data);
    }
  }

  private String read(final File f) throws IOException {
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
  }

  @Test
  public void copiesTheWholeTreeFaithfully() throws Exception {
    final FakeNode root = FakeNode.dir("HTTrack").with(
        FakeNode.file("index.html", "<html>top</html>"),
        FakeNode.dir("site").with(
            FakeNode.file("page.html", "body"),
            FakeNode.dir("img").with(FakeNode.file("a.png", "PNGDATA"))));
    final File dest = tmp.newFolder("dest");

    final LegacyMirrorImport.Result r = LegacyMirrorImport.copyTree(root, dest);

    assertTrue(r.isComplete());
    assertEquals(3, r.copied);
    assertEquals(0, r.skipped);
    assertEquals("<html>top</html>", read(new File(dest, "index.html")));
    assertEquals("body", read(new File(dest, "site/page.html")));
    assertEquals("PNGDATA", read(new File(dest, "site/img/a.png")));
  }

  @Test
  public void reRunSkipsWhatIsAlreadyThere() throws Exception {
    final FakeNode root = FakeNode.dir("HTTrack").with(FakeNode.file("index.html", "first"));
    final File dest = tmp.newFolder("dest");

    LegacyMirrorImport.copyTree(root, dest);
    final LegacyMirrorImport.Result second = LegacyMirrorImport.copyTree(root, dest);

    assertEquals(0, second.copied);
    assertEquals(1, second.skipped);
    assertEquals("first", read(new File(dest, "index.html")));
  }

  @Test
  public void oneUnreadableFileDoesNotAbortTheRest() throws Exception {
    final FakeNode root = FakeNode.dir("HTTrack").with(
        FakeNode.file("good1.html", "ok"),
        FakeNode.poison("bad.html"),
        FakeNode.file("good2.html", "ok"));
    final File dest = tmp.newFolder("dest");

    final LegacyMirrorImport.Result r = LegacyMirrorImport.copyTree(root, dest);

    assertFalse(r.isComplete());
    assertEquals(2, r.copied);
    assertEquals(1, r.failed);
    assertTrue(new File(dest, "good1.html").exists());
    assertTrue(new File(dest, "good2.html").exists());
    // The poisoned file leaves nothing half-written that a re-run would mistake for complete.
    assertFalse(new File(dest, "bad.html").exists());
    assertFalse(new File(dest, "bad.html.part").exists());
  }

  @Test
  public void refusesAnEntryThatWouldEscapeTheDestination() throws Exception {
    final FakeNode root = FakeNode.dir("HTTrack").with(
        FakeNode.file("../escape.html", "evil"),
        FakeNode.file("ok.html", "ok"));
    final File dest = tmp.newFolder("dest");

    final LegacyMirrorImport.Result r = LegacyMirrorImport.copyTree(root, dest);

    assertEquals(1, r.copied);
    assertEquals(1, r.failed);
    assertFalse(new File(dest.getParentFile(), "escape.html").exists());
  }

  /** A file whose stream yields some bytes and then throws, to model a copy killed mid-write. */
  private static final class HalfThenThrow implements LegacyMirrorImport.Source {
    private final String name;

    HalfThenThrow(final String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }

    @Override
    public long length() {
      return 100;
    }

    @Override
    public List<LegacyMirrorImport.Source> children() {
      return new ArrayList<LegacyMirrorImport.Source>();
    }

    @Override
    public InputStream openStream() {
      return new InputStream() {
        private int served;

        @Override
        public int read() throws IOException {
          if (served++ < 50) {
            return 'x';
          }
          throw new IOException("stream died mid-file");
        }
      };
    }
  }

  @Test
  public void aMidWriteFailureLeavesNoFileThatLooksComplete() throws Exception {
    final FakeNode root = FakeNode.dir("HTTrack").with(new HalfThenThrow("truncated.html"));
    final File dest = tmp.newFolder("dest");

    final LegacyMirrorImport.Result r = LegacyMirrorImport.copyTree(root, dest);

    assertEquals(1, r.failed);
    assertEquals(0, r.copied);
    // Neither the final name nor the temp may survive: a re-run must see it as missing, not done.
    assertFalse(new File(dest, "truncated.html").exists());
    assertFalse(new File(dest, "truncated.html.part").exists());
  }

  /**
   * The real point of the temp-then-rename: a file only ever appears under its final name once
   * whole, so a run killed mid-copy cannot leave a truncated file that a re-run trusts. We cannot
   * kill the process here, but we can observe that while bytes are flowing they are NOT at the
   * final name. A direct-to-destination copy fails this.
   */
  @Test
  public void bytesAreNotVisibleUnderTheFinalNameUntilComplete() throws Exception {
    final File dest = tmp.newFolder("dest");
    final boolean[] finalNameSeenMidWrite = { false };
    final LegacyMirrorImport.Source watcher = new LegacyMirrorImport.Source() {
      @Override
      public String getName() {
        return "HTTrack";
      }

      @Override
      public boolean isDirectory() {
        return true;
      }

      @Override
      public long length() {
        return 0;
      }

      @Override
      public List<LegacyMirrorImport.Source> children() {
        final List<LegacyMirrorImport.Source> kids = new ArrayList<LegacyMirrorImport.Source>();
        kids.add(new LegacyMirrorImport.Source() {
          @Override
          public String getName() {
            return "page.html";
          }

          @Override
          public boolean isDirectory() {
            return false;
          }

          @Override
          public long length() {
            return 10;
          }

          @Override
          public List<LegacyMirrorImport.Source> children() {
            return new ArrayList<LegacyMirrorImport.Source>();
          }

          @Override
          public InputStream openStream() {
            return new InputStream() {
              private int served;

              @Override
              public int read() {
                if (new File(dest, "page.html").exists()) {
                  finalNameSeenMidWrite[0] = true;
                }
                return served++ < 10 ? 'x' : -1;
              }
            };
          }
        });
        return kids;
      }

      @Override
      public InputStream openStream() {
        throw new UnsupportedOperationException();
      }
    };

    LegacyMirrorImport.copyTree(watcher, dest);

    assertFalse("bytes appeared under the final name before the copy finished",
        finalNameSeenMidWrite[0]);
    assertEquals("xxxxxxxxxx", read(new File(dest, "page.html")));
  }

  /**
   * DocumentFile.getName() may return null, and "."/".." are meaningless as entries. The null is
   * the load-bearing case: without the guard, new File(dir, null) throws and takes the whole
   * import down mid-run. The good sibling must still arrive, and copyTree must not throw.
   */
  @Test
  public void aNullOrDotEntryIsRefusedWithoutDeraillingTheRest() throws Exception {
    final FakeNode root = FakeNode.dir("HTTrack").with(
        FakeNode.file(null, "no-name"),
        FakeNode.file("..", "parent"),
        FakeNode.file(".", "self"),
        FakeNode.file("", "empty"),
        FakeNode.file("keep.html", "ok"));
    final File dest = tmp.newFolder("dest");

    final LegacyMirrorImport.Result r = LegacyMirrorImport.copyTree(root, dest);

    assertEquals(1, r.copied);
    assertEquals(4, r.failed);
    assertTrue(new File(dest, "keep.html").exists());
    // Nothing escaped to the destination's parent via "..".
    assertFalse(new File(dest.getParentFile(), "parent").exists());
  }

  @Test
  public void doesNotDropAFileWhereADirectoryAlreadyStands() throws Exception {
    final File dest = tmp.newFolder("dest");
    assertTrue(new File(dest, "clash").mkdir());
    final FakeNode root = FakeNode.dir("HTTrack").with(FakeNode.file("clash", "would-be-lost"));

    final LegacyMirrorImport.Result r = LegacyMirrorImport.copyTree(root, dest);

    // The name collision is reported, not silently counted as skipped.
    assertEquals(1, r.failed);
    assertEquals(0, r.skipped);
    assertTrue(new File(dest, "clash").isDirectory());
  }

  @Test
  public void totalSizeSumsEveryFile() {
    final FakeNode root = FakeNode.dir("HTTrack").with(
        FakeNode.file("a", "12345"),
        FakeNode.dir("d").with(FakeNode.file("b", "123")));
    assertEquals(8, LegacyMirrorImport.totalSize(root));
  }

  /** A tree deeper than the cap is refused by both the size probe and the copy, not overflowed. */
  @Test
  public void aTreeDeeperThanTheCapIsRefused() throws Exception {
    FakeNode cursor = FakeNode.dir("leaf");
    for (int i = 0; i <= LegacyMirrorImport.MAX_DEPTH; i++) {
      cursor = FakeNode.dir("d").with(cursor);
    }
    // Long.MAX_VALUE forces the free-space check to reject the import.
    assertEquals(Long.MAX_VALUE, LegacyMirrorImport.totalSize(cursor));

    final File dest = tmp.newFolder("dest");
    final LegacyMirrorImport.Result r = LegacyMirrorImport.copyTree(cursor, dest);
    assertFalse(r.isComplete());
  }

  @Test
  public void aByteForByteCopyMatchesBinaryContent() throws Exception {
    final byte[] raw = new byte[1000];
    for (int i = 0; i < raw.length; i++) {
      raw[i] = (byte) (i * 7);
    }
    final FakeNode root = FakeNode.dir("HTTrack").with(
        new FakeNode("blob.bin", false, raw, false));
    final File dest = tmp.newFolder("dest");

    LegacyMirrorImport.copyTree(root, dest);

    assertArrayEquals(raw, Files.readAllBytes(new File(dest, "blob.bin").toPath()));
  }
}
