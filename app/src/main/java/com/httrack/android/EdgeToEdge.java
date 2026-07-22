package com.httrack.android;

import android.app.ActionBar;
import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Fits content inside the system bars and below the platform action bar where edge-to-edge is forced (API 35+). */
final class EdgeToEdge {
  private EdgeToEdge() {
  }

  /** Pad the decor for the system bars, and the content for the action bar it overlaps; survives setContentView swaps. */
  static void fitSystemWindows(final Activity activity) {
    // Below API 35 the framework still fits system windows and resizes for the IME; don't disturb it.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      return;
    }
    final Window window = activity.getWindow();
    WindowCompat.setDecorFitsSystemWindows(window, false);
    final View decor = window.getDecorView();
    ViewCompat.setOnApplyWindowInsetsListener(decor, (v, insets) -> {
      // ime() pads the bottom by max(nav bar, keyboard) so a focused field stays visible.
      final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
          | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    final View content = activity.findViewById(android.R.id.content);
    if (content != null) {
      // Edge-to-edge overlays the Holo action bar on the content; clear it by the bar's real laid-out
      // height (the themed actionBarSize under-measures it on some densities), refreshed on every layout.
      content.setPadding(0, themedActionBarSize(activity), 0, 0);
      decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
        final ActionBar bar = activity.getActionBar();
        final int height = bar != null ? bar.getHeight() : 0;
        if (height > 0 && content.getPaddingTop() != height) {
          content.setPadding(0, height, 0, 0);
        }
      });
    }
    // Dark icons on the light day theme, light icons on the night (Holo dark) theme.
    final boolean night = (activity.getResources().getConfiguration().uiMode
        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    final WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
    controller.setAppearanceLightStatusBars(!night);
    controller.setAppearanceLightNavigationBars(!night);
  }

  /** The theme's action bar height in px, or 0 if the theme has no action bar. */
  private static int themedActionBarSize(final Activity activity) {
    final TypedValue tv = new TypedValue();
    if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
      return TypedValue.complexToDimensionPixelSize(tv.data, activity.getResources().getDisplayMetrics());
    }
    return 0;
  }
}
