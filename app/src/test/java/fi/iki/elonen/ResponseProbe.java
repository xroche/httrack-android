package fi.iki.elonen;

import java.io.OutputStream;

/** Test-only bridge: Response.send() is protected, so the wire bytes need a same-package caller. */
public final class ResponseProbe {
  private ResponseProbe() {
  }

  public static void send(final NanoHTTPD.Response response, final OutputStream out) {
    response.send(out);
  }
}
