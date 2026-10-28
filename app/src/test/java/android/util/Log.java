package android.util;

import java.util.ArrayList;
import java.util.List;

/** Stands in for the mockable android.jar, whose Log throws "not mocked". */
public class Log {
  /** What was logged since capture(), for a test that asserts what reaches logcat. */
  private static List<String> captured;

  /** Record from here on, dropping whatever an earlier test logged. */
  public static void capture() {
    captured = new ArrayList<String>();
  }

  /** Every message logged since capture() ran, oldest first. */
  public static List<String> captured() {
    if (captured == null) {
      throw new IllegalStateException("capture() runs first");
    }
    return captured;
  }

  private static int record(final String msg) {
    if (captured != null) {
      captured.add(msg);
    }
    return 0;
  }

  public static int v(final String tag, final String msg) {
    return record(msg);
  }

  public static int v(final String tag, final String msg, final Throwable tr) {
    return record(msg);
  }

  public static int d(final String tag, final String msg) {
    return record(msg);
  }

  public static int d(final String tag, final String msg, final Throwable tr) {
    return record(msg);
  }

  public static int i(final String tag, final String msg) {
    return record(msg);
  }

  public static int i(final String tag, final String msg, final Throwable tr) {
    return record(msg);
  }

  public static int w(final String tag, final String msg) {
    return record(msg);
  }

  public static int w(final String tag, final String msg, final Throwable tr) {
    return record(msg);
  }

  public static int e(final String tag, final String msg) {
    return record(msg);
  }

  public static int e(final String tag, final String msg, final Throwable tr) {
    return record(msg);
  }

  public static String getStackTraceString(final Throwable tr) {
    return "";
  }
}
