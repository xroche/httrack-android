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

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewParent;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.Toast;

import com.httrack.android.jni.HTTrackLib;

/**
 * Cleanup view.<br />
 * Thanks to David Silvera for the excellent related tutorial.
 */
public class CleanupActivity extends ListActivity {
  private ListView list;
  private File projectRootFile;
  private File resourceFile;
  private String[] projects;
  private int action;
  private final HashSet<Integer> toBeDeleted = new HashSet<Integer>();
  private volatile boolean deleteInProgress;

  public static final int ACTION_CLEANUP = 1;
  public static final int ACTION_SELECT = 2;

  // A directory nested deeper than this is treated as a failure rather than overflowing the stack.
  static final int MAX_DEPTH = 100;

  /** Called when the activity is first created. */
  @Override
  public void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_cleanup);
    list = getListView();

    // Fetch args from parent
    final Bundle extras = getIntent().getExtras();
    projectRootFile = File.class.cast(extras
        .get("com.httrack.android.rootFile"));
    resourceFile = File.class.cast(extras
        .get("com.httrack.android.resourceFile"));
    projects = (String[]) extras.get("com.httrack.android.projectNames");
    action = extras.getInt("com.httrack.android.action");

    if (projectRootFile == null || resourceFile == null || projects == null
        || action == 0) {
      throw new RuntimeException("internal error");
    }
    
    // Visibility of "delete" and its hline.
    final int state = action == ACTION_CLEANUP ? View.VISIBLE : View.GONE;
    findViewById(R.id.buttonClear).setVisibility(state);
    findViewById(R.id.horizontalLine).setVisibility(state);

    final ArrayList<HashMap<String, String>> listItem = new ArrayList<HashMap<String, String>>();

    for (final String name : projects) {
      final HashMap<String, String> map = new HashMap<String, String>();
      map.put("name", name);

      // Map used to lookup projects
      final SparseArray<String> projectMap = new SparseArray<String>();
      final File target = new File(projectRootFile, name);
      try {
        OptionsMapper.unserialize(HTTrackActivity.getProfileFile(target),
            projectMap);
        final String description = projectMap.get(R.id.fieldWebsiteURLs);
        map.put("description", description);
      } catch (final IOException e) {
        map.put("description", name);
      }

      // Add item
      listItem.add(map);
    }

    final SimpleAdapter adapter = new SimpleAdapter(
        this.getBaseContext(), listItem, R.layout.cleanup_item, new String[] {
            "name", "description" }, new int[] { R.id.name, R.id.description });
    list.setAdapter(adapter);
  }

  public void OnClickCheckbox(final View v) {
    final CheckBox cb = (CheckBox) v;
    // Adapter position of the clicked row, valid even when scrolled; getChildAt would misread it
    // as a visible-child index and toggle/select the wrong project.
    final int position = list.getPositionForView(v);
    if (position == ListView.INVALID_POSITION) {
      return;
    }

    if (action == ACTION_CLEANUP) {
      final View o = blocCheckFor(v);
      if (cb.isChecked()) {
        if (o != null) {
          o.setBackgroundResource(R.color.transparent_red);
        }
        toBeDeleted.add(position);
      } else {
        if (o != null) {
          o.setBackgroundResource(R.color.transparent);
        }
        toBeDeleted.remove(position);
      }
    } else if (action == ACTION_SELECT) {
      // Push result
      final Intent intent = new Intent();
      intent.putExtra("com.httrack.android.projectName", projects[position]);
      setResult(Activity.RESULT_OK, intent);

      // Finish activity
      finish();
    }
  }

  /** The clicked row's coloured container, walked up from the clicked view rather than by index. */
  private static View blocCheckFor(final View clicked) {
    View view = clicked;
    while (view != null && view.getId() != R.id.blocCheck) {
      final ViewParent parent = view.getParent();
      view = parent instanceof View ? (View) parent : null;
    }
    return view;
  }

  /* Delete recursively a directory, or delete a file. */
  public static boolean deleteRecursively(final File file) {
    return deleteRecursively(file, MAX_DEPTH);
  }

  /* Depth-bounded worker: refuses a tree deeper than depthLeft instead of overflowing the stack. */
  static boolean deleteRecursively(final File file, final int depthLeft) {
    // TODO: check if this is necessary (symbolic link handling to avoid
    // infinite loops)
    if (file.delete()) {
      return true;
    }
    if (depthLeft <= 0) {
      return false;
    }
    if (file.isDirectory()) {
      final File[] children = file.listFiles(); // null on I/O error even for a real directory
      if (children != null) {
        for (final File child : children) {
          deleteRecursively(child, depthLeft - 1);
        }
      }
    }
    return file.delete();
  }

  /**
   * Is this path empty ? Note: ignores .nomedia files.
   * 
   * @return true if the root path is empty.
   */
  public static boolean pathIsEmpty(final File projectRootFile) {
    if (projectRootFile != null) {
      final File[] files = projectRootFile.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(final File dir, final String filename) {
          return !filename.equals(HTTrackActivity.NOMEDIA_FILE);
        }
      });
      return files != null && files.length == 0;
    } else {
      return false;
    }
  }

  /**
   * Delete the selected projects off the UI thread: one delete() per file over a whole mirror
   * plus a native index rebuild blows past the ANR budget. The selection is snapshotted here and
   * the result posted back to finish the activity; a second run while one is going is ignored.
   */
  protected void deleteProjects() {
    if (deleteInProgress) {
      return;
    }
    final HashSet<Integer> positions = new HashSet<Integer>(toBeDeleted);
    toBeDeleted.clear();
    deleteInProgress = true;
    Toast.makeText(this, "Deleting projects…", Toast.LENGTH_SHORT).show();
    new Thread(new Runnable() {
      @Override
      public void run() {
        final HashSet<Integer> deleted = new HashSet<Integer>();
        for (final int position : positions) {
          if (deleteRecursively(new File(projectRootFile, projects[position]))) {
            deleted.add(position);
          }
        }

        // Root path can be deleted ?
        final boolean deleteRootPath = pathIsEmpty(projectRootFile);
        if (deleteRootPath) {
          if (deleteRecursively(projectRootFile)) {
            Log.d(getClass().getSimpleName(), "successfully deleted root path: "
                + projectRootFile);
          } else {
            Log.w(getClass().getSimpleName(), "could not delet root path: "
                + projectRootFile);
          }

          // Delete top-level parent if a "HTTrack" folder was found. This ensure
          // that we fully cleanup user-data.
          final File parentRoot = projectRootFile.getParentFile();
          if (parentRoot != null && parentRoot.getName().equals("HTTrack")
              && pathIsEmpty(parentRoot)) {
            if (deleteRecursively(parentRoot)) {
              Log.d(getClass().getSimpleName(), "successfully deleted root path: "
                  + parentRoot);
            } else {
              Log.w(getClass().getSimpleName(), "could not delet root path: "
                  + parentRoot);
            }
          }
        } else {
          // Rebuild top index
          HTTrackLib.buildTopIndex(projectRootFile, resourceFile);
        }

        deliverDeleteOutcome(deleted, deleteRootPath);
      }
    }, "cleanup-delete").start();
  }

  /** Finish with the result on the main thread; touch the list only if we still own it. */
  private void deliverDeleteOutcome(final HashSet<Integer> deleted,
      final boolean rootPathWasDeleted) {
    new Handler(Looper.getMainLooper()).post(new Runnable() {
      @Override
      public void run() {
        deleteInProgress = false;
        if (!isFinishing() && !isDestroyed()) {
          final int firstVisible = list.getFirstVisiblePosition();
          for (final int position : deleted) {
            // deleted holds adapter positions; map to the visible child, skipping rows scrolled off.
            final View item = list.getChildAt(position - firstVisible);
            if (item == null) {
              continue;
            }
            item.findViewById(R.id.blocCheck).setBackgroundResource(R.color.gray);
            final CheckBox cb = (CheckBox) item.findViewById(R.id.check);
            cb.setClickable(false);
            cb.setEnabled(false);
          }
        }

        final Intent intent = new Intent();
        intent.putExtra("com.httrack.android.rootPathWasDeleted", rootPathWasDeleted);
        setResult(Activity.RESULT_OK, intent);
        finish();
      }
    });
  }

  public void onClickDelete(final View v) {
    if (action == ACTION_CLEANUP) {
      String projectList = "";
      for (final int position : toBeDeleted) {
        final String name = projects[position];
        projectList += "\n";
        projectList += name;
      }
      if (projectList.length() != 0) {
        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle("Delete Projects")
            .setMessage(
                "Are you sure you want to delete selected projects ?"
                    + projectList)
            .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(final DialogInterface dialog, final int which) {
                deleteProjects();
              }
            }).setNegativeButton("No", null).show();
      }
    }
  }
}
