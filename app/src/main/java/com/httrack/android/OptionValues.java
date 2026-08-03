/*
HTTrack Android Java Interface.

HTTrack Website Copier, Offline Browser for Windows and Unix
Copyright (C) Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.httrack.android;

import java.util.regex.Pattern;

/**
 * Numeric helpers for the option mappers; pure, Android-free, so the mappers
 * that use them stay reachable from unit tests.
 */
public final class OptionValues {
  private OptionValues() {
  }

  private static final Pattern patternDigits = Pattern.compile("^[0-9]+$");

  private static final Pattern patternDecimal = Pattern
      .compile("^[0-9]+(\\.[0-9]+)?$");

  /**
   * True if the value is a non-empty run of ASCII digits.
   *
   * @param value
   *          The value, possibly null
   * @return true if the value is pure digits
   */
  public static boolean isDigits(final String value) {
    return value != null && patternDigits.matcher(value).matches();
  }

  /**
   * True if the value is a non-negative integer or decimal.
   *
   * @param value
   *          The value, possibly null
   * @return true if the value is a non-negative decimal
   */
  public static boolean isDecimal(final String value) {
    return value != null && patternDecimal.matcher(value).matches();
  }

  /**
   * Parse an integer.
   *
   * @param value
   *          The integer value
   * @param defaultValue
   *          Default value on error or out-of-range
   * @return The parsed value, or defaultValue on error
   */
  public static int parseInt(final String value, final int defaultValue) {
    try {
      return Integer.parseInt(value);
    } catch (final NumberFormatException nfe) {
      return defaultValue;
    }
  }

  /**
   * Parse an integer.
   *
   * @param value
   *          The integer value
   * @return The parsed value, or 999999999 on error
   */
  public static int parseInt(final String value) {
    return parseInt(value, 999999999);
  }
}
