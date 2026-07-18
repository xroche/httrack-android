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

  @Test
  public void totalSizeSumsEveryFile() {
    final FakeNode root = FakeNode.dir("HTTrack").with(
        FakeNode.file("a", "12345"),
        FakeNode.dir("d").with(FakeNode.file("b", "123")));
    assertEquals(8, LegacyMirrorImport.totalSize(root));
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
