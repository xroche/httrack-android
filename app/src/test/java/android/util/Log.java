package android.util;

/** Stands in for the mockable android.jar, whose Log throws "not mocked". */
public class Log {
  public static int v(final String tag, final String msg) {
    return 0;
  }

  public static int v(final String tag, final String msg, final Throwable tr) {
    return 0;
  }

  public static int d(final String tag, final String msg) {
    return 0;
  }

  public static int d(final String tag, final String msg, final Throwable tr) {
    return 0;
  }

  public static int i(final String tag, final String msg) {
    return 0;
  }

  public static int i(final String tag, final String msg, final Throwable tr) {
    return 0;
  }

  public static int w(final String tag, final String msg) {
    return 0;
  }

  public static int w(final String tag, final String msg, final Throwable tr) {
    return 0;
  }

  public static int e(final String tag, final String msg) {
    return 0;
  }

  public static int e(final String tag, final String msg, final Throwable tr) {
    return 0;
  }

  public static String getStackTraceString(final Throwable tr) {
    return "";
  }
}
