package com.cleantv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Remote-first file browser for TVs that do not ship Android's DocumentsUI picker. */
public final class FileBrowserActivity extends Activity {
    private static final int ORANGE = 0xFFFF7B1C;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private TextView path, help, count;
    private ListView list;
    private File current;
    private boolean folderMode;
    private Typeface font;

    private static final class Entry { final String label; final File file; Entry(String label, File file) { this.label = label; this.file = file; } }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); folderMode = "folder".equals(getIntent().getStringExtra("mode"));
        try { font = Typeface.createFromAsset(getAssets(), "fonts/Onest.ttf"); } catch (Exception ignored) { font = Typeface.DEFAULT; }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(48), dp(28), dp(48), dp(28)); root.setBackgroundColor(0xFF05090C);
        TextView brand = text("PYLERIUM / MEDIA BROWSER", 19, ORANGE); brand.setTypeface(font, Typeface.BOLD); brand.setLetterSpacing(.1f); root.addView(brand, new LinearLayout.LayoutParams(-1, dp(42)));
        path = text("DRIVES", 27, Color.WHITE); path.setTypeface(font, Typeface.BOLD); root.addView(path, new LinearLayout.LayoutParams(-1, dp(50)));
        help = text(folderMode ? "OK opens a folder  •  MENU assigns the current folder" : "OK opens folders or selects videos  •  MENU adds selected files", 13, 0xFF91A4AE);
        root.addView(help, new LinearLayout.LayoutParams(-1, dp(38)));
        list = new ListView(this); list.setDividerHeight(dp(6)); list.setDivider(null); list.setSelector(R.drawable.browser_selector); list.setBackgroundColor(0xFF131C21); list.setVerticalFadingEdgeEnabled(true);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        count = text("", 13, 0xFF26D8EE); count.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); root.addView(count, new LinearLayout.LayoutParams(-1, dp(34)));
        list.setOnItemClickListener((parent, view, position, id) -> activate(entries.get(position)));
        setContentView(root); showRoots(); list.requestFocus();
    }
    private TextView text(String value, float size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(font); view.setGravity(Gravity.CENTER_VERTICAL); return view; }

    private void showRoots() {
        current = null; path.setText("CHOOSE A DRIVE"); entries.clear();
        StorageManager manager = getSystemService(StorageManager.class); List<StorageVolume> volumes = manager == null ? new ArrayList<>() : manager.getStorageVolumes();
        if (Build.VERSION.SDK_INT >= 30) {
            volumes.sort((a, b) -> Boolean.compare(b.isRemovable(), a.isRemovable()));
            for (StorageVolume volume : volumes) {
                File directory = volume.getDirectory();
                if (directory != null && directory.canRead()) entries.add(new Entry((volume.isRemovable() ? "USB  /  " : "STORAGE  /  ") + volume.getDescription(this), directory));
            }
        }
        if (entries.isEmpty()) {
            File storage = new File("/storage"); File[] children = storage.listFiles();
            if (children != null) for (File child : children) if (child.canRead()) entries.add(new Entry("DRIVE  /  " + child.getName(), child));
        }
        refresh();
    }
    private void showDirectory(File directory) {
        current = directory; path.setText(directory.getName().isEmpty() ? directory.getPath() : directory.getName().toUpperCase()); entries.clear();
        File[] children = directory.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::isFile).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File child : children) {
                if (child.isDirectory() && child.canRead()) entries.add(new Entry("▸  " + child.getName(), child));
                else if (!folderMode && isVideo(child)) entries.add(new Entry((selected.contains(child.getPath()) ? "✓  " : "   ") + child.getName(), child));
            }
        }
        refresh();
    }
    private void refresh() { list.setAdapter(new BrowserAdapter()); updateCount(); if (!entries.isEmpty()) list.setSelection(0); }
    private void activate(Entry entry) {
        if (entry.file.isDirectory()) showDirectory(entry.file);
        else { String path = entry.file.getPath(); if (!selected.add(path)) selected.remove(path); refresh(); }
    }
    private void finishSelection() {
        ArrayList<String> paths = new ArrayList<>();
        if (folderMode) { if (current == null) { Toast.makeText(this, "Open a folder first", Toast.LENGTH_SHORT).show(); return; } paths.add(current.getPath()); }
        else { if (selected.isEmpty()) { Toast.makeText(this, "Select at least one video", Toast.LENGTH_SHORT).show(); return; } paths.addAll(selected); }
        setResult(RESULT_OK, new Intent().putStringArrayListExtra("paths", paths)); finish();
    }
    private void updateCount() { count.setText(folderMode ? (current == null ? "SELECT A DRIVE" : "MENU  /  USE THIS FOLDER") : selected.size() + " SELECTED  •  MENU TO ADD"); }
    private boolean isVideo(File file) { String name = file.getName().toLowerCase(); return name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov") || name.endsWith(".m4v") || name.endsWith(".webm") || name.endsWith(".ts"); }
    @Override public void onBackPressed() {
        if (current == null) { super.onBackPressed(); return; }
        File parent = current.getParentFile(); boolean isRoot = false;
        if (Build.VERSION.SDK_INT >= 30) { StorageManager manager = getSystemService(StorageManager.class); if (manager != null) for (StorageVolume volume : manager.getStorageVolumes()) if (current.equals(volume.getDirectory())) isRoot = true; }
        if (isRoot || parent == null || "/storage".equals(parent.getPath())) showRoots(); else showDirectory(parent);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_MENU) { finishSelection(); return true; }
        return super.dispatchKeyEvent(event);
    }
    private final class BrowserAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = convertView instanceof TextView ? (TextView) convertView : text("", 17, 0xFFE1E7EA);
            view.setText(entries.get(position).label); view.setPadding(dp(22), 0, dp(22), 0); view.setBackgroundColor(Color.TRANSPARENT);
            view.setLayoutParams(new ListView.LayoutParams(-1, dp(54))); return view;
        }
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
