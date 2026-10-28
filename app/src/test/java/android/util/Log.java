package android.util;

import java.util.ArrayList;
import java.util.List;

/** Stands in for the mockable android.jar, whose Log throws "not mocked". */
public class Log {
  /** Every message logged, oldest first, so a test can read back what reached logcat. */
  public static final List<String> captured = new ArrayList<String>();

  private static int record(final String msg) {
    captured.add(msg);
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
