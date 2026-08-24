package com.httrack.android;

/**
 * What a recovered native fault forces on the UI. These are the decisions the activity would
 * otherwise bury in lifecycle methods, where no test can reach them: no Android type appears
 * here, so each one can be checked against its truth table.
 */
final class NativeFaultPolicy {
  private NativeFaultPolicy() {
  }

  /**
   * Close the app when the user leaves the panel that reported the fault.
   *
   * @param faulted      whether a native fault has been recovered
   * @param currentPane  the pane being left
   * @param nextPane     the pane asked for
   * @param finishedPane the pane the fault is reported on
   * @return true when the activity must finish instead of switching pane
   */
  static boolean closeOnPaneChange(final boolean faulted, final int currentPane,
      final int nextPane, final int finishedPane) {
    return faulted && currentPane == finishedPane && nextPane != finishedPane;
  }

  /**
   * End the process, once the activity is gone for good and has saved what it holds.
   *
   * @param finishing     whether the activity is finishing, false for a configuration change
   * @param exitRequested whether something already asked for the process to go
   * @param faulted       whether a native fault has been recovered
   * @return true when the process must not survive this destruction
   */
  static boolean exitOnDestroy(final boolean finishing, final boolean exitRequested,
      final boolean faulted) {
    return finishing && (exitRequested || faulted);
  }

  /**
   * Report a fault that was latched away from the finished panel, where nothing else shows it.
   *
   * @param faulted      whether a native fault has been recovered
   * @param currentPane  the pane on screen
   * @param finishedPane the pane the fault is reported on
   * @return true when the finished panel must be raised
   */
  static boolean reportOnResume(final boolean faulted, final int currentPane,
      final int finishedPane) {
    return faulted && currentPane != finishedPane;
  }
}
