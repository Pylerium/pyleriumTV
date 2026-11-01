package com.cleantv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements GuideView.Listener {
    private static final int ORANGE = 0xFFFF7B1C;
    private static final int PICK_FOLDER = 40, PICK_FILES = 41;
    private final List<ChannelRepository.Channel> channels = new ArrayList<>();
    private final ExecutorService mediaScanner = Executors.newSingleThreadExecutor();
    private GuideView guide;
    private TextView status;
    private Typeface font;
    private int editingChannel;
    private int pendingBrowserMode;
    private final BroadcastReceiver storageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reload(); }
    };
    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reload(); Toast.makeText(context, "Library updated from desktop studio", Toast.LENGTH_SHORT).show(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xFF050709);
        try { font = Typeface.createFromAsset(getAssets(), "fonts/Onest.ttf"); }
        catch (RuntimeException ignored) { font = Typeface.DEFAULT; }

        FrameLayout root = new FrameLayout(this); root.setBackgroundResource(R.drawable.home_backdrop);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(46), dp(22), dp(46), dp(24)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.pylerium_logo); logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        top.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView brand = text("PYLERIUM / TV", 20, ORANGE); brand.setLetterSpacing(.14f); brand.setTypeface(font, Typeface.BOLD);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, dp(52), 1); brandLp.setMarginStart(dp(12)); top.addView(brand, brandLp);
        status = text("SCANNING STORAGE…", 12, 0xFF9DAEB7); status.setLetterSpacing(.06f);
        top.addView(status, new LinearLayout.LayoutParams(-2, dp(48))); page.addView(top);

        TextView title = text("Your media. Your channels.", 30, 0xFFF3F3F3); title.setTypeface(font, Typeface.BOLD);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(46)));
        TextView hint = text("Browse  •  OK to watch  •  MENU for Edit Mode", 13, 0xFF7E929D);
        page.addView(hint, new LinearLayout.LayoutParams(-1, dp(30)));
        guide = new GuideView(this); guide.setListener(this); page.addView(guide, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); SyncService.start(this); reload();
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL); view.setTypeface(font); return view;
    }
    private void reload() {
        status.setText("SCANNING STORAGE…");
        mediaScanner.execute(() -> {
            List<ChannelRepository.Channel> loaded = ChannelRepository.load(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                channels.clear(); channels.addAll(loaded); guide.setChannels(channels); updateStorageStatus();
            });
        });
    }
    private void save() { ChannelRepository.save(this, channels); reload(); }

    private void updateStorageStatus() {
        List<StorageVolume> removable = removableVolumes();
        if (removable.isEmpty()) status.setText(channels.size() + " CHANNELS  •  INTERNAL STORAGE");
        else status.setText(removable.size() + " DRIVE" + (removable.size() == 1 ? "" : "S") + " DETECTED  •  " + channels.size() + " CHANNELS");
    }
    private List<StorageVolume> removableVolumes() {
        List<StorageVolume> result = new ArrayList<>(); StorageManager manager = getSystemService(StorageManager.class);
        if (manager != null) for (StorageVolume volume : manager.getStorageVolumes())
            if (volume.isRemovable() && android.os.Environment.MEDIA_MOUNTED.equals(volume.getState())) result.add(volume);
        return result;
    }
    private String driveDetails() {
        List<StorageVolume> volumes = removableVolumes(); if (volumes.isEmpty()) return "No removable drive detected. You can still choose internal storage.";
        StringBuilder text = new StringBuilder("Detected: ");
        for (int i = 0; i < volumes.size(); i++) { if (i > 0) text.append(", "); text.append(volumes.get(i).getDescription(this)); }
        return text.toString();
    }

    private void editMode() {
        editingChannel = Math.max(0, guide.selectedChannelIndex());
        ChannelRepository.Channel channel = channels.get(editingChannel);
        String[] actions = {"Pair desktop studio", "Reset paired studios", "Rename channel", "Add a folder", "Add multiple files", "Create new channel", "Clear channel media", "Delete channel", "Refresh storage"};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("EDIT MODE  /  " + channel.name + "\n" + driveDetails())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showPairCode();
                    else if (which == 1) resetPairings();
                    else if (which == 2) rename(channel);
                    else if (which == 3) pickFolder();
                    else if (which == 4) pickFiles();
                    else if (which == 5) createChannel();
                    else if (which == 6) confirmClear(channel);
                    else if (which == 7) confirmDelete(channel);
                    else { reload(); Toast.makeText(this, driveDetails(), Toast.LENGTH_SHORT).show(); }
                }).setNegativeButton("Done", null).show();
    }
    private void resetPairings() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Reset paired studios?")
                .setMessage("Every desktop will need a new one-time pairing code.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset", (d, w) -> {
                    SyncService.revokeAll(this); Toast.makeText(this, "Desktop pairings reset", Toast.LENGTH_SHORT).show();
                }).show();
    }
    private void showPairCode() {
        String code = SyncService.newPairCode(this);
        TextView value = text(code.substring(0, 3) + "  " + code.substring(3), 42, Color.WHITE); value.setGravity(Gravity.CENTER); value.setPadding(dp(20), dp(24), dp(20), dp(24));
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("PAIR DESKTOP STUDIO")
                .setMessage("Enter this one-time code in PyleriumTV Studio. It expires in five minutes.")
                .setView(value).setPositiveButton("Done", null).show();
    }
    private void rename(ChannelRepository.Channel channel) {
        EditText input = editText(channel.name);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Rename channel").setView(input)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim(); if (!name.isEmpty()) { channel.name = name.toUpperCase(); save(); }
                }).show();
    }
    private void createChannel() {
        ChannelRepository.Channel channel = ChannelRepository.addChannel(channels); editingChannel = channels.indexOf(channel); rename(channel);
    }
    private EditText editText(String value) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(value); input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS); input.setTextColor(Color.WHITE);
        input.setPadding(dp(24), dp(12), dp(24), dp(12)); return input;
    }
    private void confirmClear(ChannelRepository.Channel channel) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Clear " + channel.name + "?")
                .setMessage("The channel remains, but its assigned folders and files are removed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> { channel.sources.clear(); save(); }).show();
    }
    private void confirmDelete(ChannelRepository.Channel channel) {
        if (channels.size() == 1) { Toast.makeText(this, "At least one channel is required", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Delete " + channel.name + "?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> { channels.remove(channel); renumber(); save(); }).show();
    }
    private void renumber() { for (int i = 0; i < channels.size(); i++) channels.get(i).number = String.valueOf(i + 1); }

    private void pickFolder() {
        openBrowser(PICK_FOLDER);
    }
    private void pickFiles() {
        openBrowser(PICK_FILES);
    }
    private void openBrowser(int mode) {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            pendingBrowserMode = mode;
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            Toast.makeText(this, "Allow file access, then return to pyleriumTV", Toast.LENGTH_LONG).show(); return;
        }
        pendingBrowserMode = 0;
        startActivityForResult(new Intent(this, FileBrowserActivity.class).putExtra("mode", mode == PICK_FOLDER ? "folder" : "files"), mode);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK || data == null || channels.isEmpty()) return;
        ChannelRepository.Channel channel = channels.get(Math.min(editingChannel, channels.size() - 1));
        ArrayList<String> paths = data.getStringArrayListExtra("paths");
        if (paths == null || paths.isEmpty()) return;
        if (requestCode == PICK_FOLDER) {
            String source = "folder:" + paths.get(0);
            if (!channel.sources.contains(source)) channel.sources.add(source); save();
            Toast.makeText(this, "Folder added to " + channel.name, Toast.LENGTH_SHORT).show();
        } else if (requestCode == PICK_FILES) {
            int added = 0;
            for (String path : paths) { String uri = Uri.fromFile(new java.io.File(path)).toString(); if (!channel.sources.contains(uri)) { channel.sources.add(uri); added++; } }
            save(); Toast.makeText(this, added + " file" + (added == 1 ? "" : "s") + " added to " + channel.name, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void play(ChannelRepository.Channel channel) {
        ChannelRepository.Program program = channel.currentProgram(); if (program == null || program.uri == null) { Toast.makeText(this, "Press MENU and assign media to this channel", Toast.LENGTH_SHORT).show(); return; }
        startActivity(new Intent(this, PlayerActivity.class).putExtra("channel_id", channel.id));
    }
    @Override public void setup() { editMode(); }
    @Override public void refresh() { reload(); }
    @Override protected void onStart() {
        super.onStart(); IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED); filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED); filter.addAction(Intent.ACTION_MEDIA_REMOVED); filter.addDataScheme("file");
        registerReceiver(storageReceiver, filter);
        androidx.core.content.ContextCompat.registerReceiver(this, syncReceiver, new IntentFilter(SyncService.CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override protected void onStop() { unregisterReceiver(storageReceiver); unregisterReceiver(syncReceiver); super.onStop(); }
    @Override protected void onResume() {
        super.onResume(); if (guide != null) updateStorageStatus();
        if (pendingBrowserMode != 0 && (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())) {
            int mode = pendingBrowserMode; pendingBrowserMode = 0; openBrowser(mode);
        }
    }
    @Override protected void onDestroy() { mediaScanner.shutdownNow(); super.onDestroy(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && (event.getKeyCode() == KeyEvent.KEYCODE_MENU || event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS)) { editMode(); return true; }
        return super.dispatchKeyEvent(event);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
