package com.httrack.android;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Re-fits the platform title bar and content inside the system bars where edge-to-edge is forced (API 35+). */
final class EdgeToEdge {
  private EdgeToEdge() {
  }

  /** Pad the decor so title bar + content clear the status/nav bars and the keyboard; survives setContentView swaps. */
  static void fitSystemWindows(final Activity activity) {
    // Below API 35 the framework still fits system windows and resizes for the IME; don't disturb it.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      return;
    }
    final Window window = activity.getWindow();
    WindowCompat.setDecorFitsSystemWindows(window, false);
    final View decor = window.getDecorView();
    ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
      // ime() in the union pads the bottom by max(nav bar, keyboard) so a focused field stays visible.
      final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
          | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    // Dark icons on the light day theme, light icons on the night (Holo dark) theme.
    final boolean night = (activity.getResources().getConfiguration().uiMode
        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    final WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
    controller.setAppearanceLightStatusBars(!night);
    controller.setAppearanceLightNavigationBars(!night);
  }
}
