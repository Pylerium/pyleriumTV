package com.cleantv.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Linear playback: tuning joins the programme at the wall-clock position, never at its beginning. */
public final class PlayerActivity extends Activity {
    private static final int ORANGE = 0xFFFF7B1C, MAX_RETRIES = 1, STALL_MS = 25000;
    private VLCVideoLayout videoLayout; private MediaPlayer player; private ProgressBar spinner, progress;
    private TextView badge, title, subtitle, notice, bug;
    private View infoPanel;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private List<ChannelRepository.Channel> channels; private int channelIndex, retries;
    private ChannelRepository.Program current; private long generation; private boolean paused;
    private final Runnable hideInfo = () -> { infoPanel.animate().alpha(0).setDuration(300).start(); setScrim(0); };
    private final Runnable hideNotice = () -> notice.animate().alpha(0).setDuration(250).start();
    private final Runnable tick = new Runnable() { @Override public void run() { updateProgress(); clock.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Typeface font; try { font = Typeface.createFromAsset(getAssets(), "fonts/Onest.ttf"); } catch (RuntimeException e) { font = Typeface.DEFAULT; }
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        videoLayout = new VLCVideoLayout(this); root.addView(videoLayout, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this); root.addView(spinner, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));

        View scrim = new View(this); scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF2050709, 0xB3050709, 0x00050709}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(230), Gravity.BOTTOM)); scrim.setTag("scrim");

        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); infoPanel = panel; panel.setPadding(dp(48), 0, dp(48), dp(36));
        LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL);
        badge = label(font, 20, Color.WHITE, true); badge.setGravity(Gravity.CENTER); badge.setBackground(round(ORANGE, 12));
        line.addView(badge, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(18), 0, 0, 0);
        title = label(font, 24, Color.WHITE, true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle = label(font, 14, 0xFFA3B4BC, false); subtitle.setLetterSpacing(.04f);
        names.addView(title); names.addView(subtitle); line.addView(names, new LinearLayout.LayoutParams(0, -2, 1)); panel.addView(line);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        progress.setProgressDrawable(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{round(0x44FFFFFF, 3),
                new android.graphics.drawable.ClipDrawable(round(ORANGE, 3), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)}));
        ((android.graphics.drawable.LayerDrawable) progress.getProgressDrawable()).setId(0, android.R.id.background);
        ((android.graphics.drawable.LayerDrawable) progress.getProgressDrawable()).setId(1, android.R.id.progress);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(5)); barLp.topMargin = dp(18); panel.addView(progress, barLp);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        bug = label(font, 11, 0xCCFFFFFF, true); bug.setText("PYLERIUM  •  LIVE"); bug.setLetterSpacing(.14f); bug.setPadding(dp(14), dp(7), dp(14), dp(7)); bug.setBackground(round(0x99050709, 8));
        FrameLayout.LayoutParams bugLp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP); bugLp.setMargins(0, dp(26), dp(32), 0); root.addView(bug, bugLp);
        notice = label(font, 17, Color.WHITE, true); notice.setPadding(dp(22), dp(11), dp(22), dp(11)); notice.setBackground(round(0xE60B1115, 12)); notice.setAlpha(0);
        FrameLayout.LayoutParams noticeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP); noticeLp.topMargin = dp(26); root.addView(notice, noticeLp);
        setContentView(root);

        player = new MediaPlayer(Vlc.get(this)); player.attachViews(videoLayout, null, false, false); player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        player.setEventListener(event -> {
            long token = generation;
            switch (event.type) {
                case MediaPlayer.Event.Playing: clock.post(() -> { if (token == generation) { spinner.setVisibility(View.GONE); retries = 0; } }); break;
                case MediaPlayer.Event.EndReached: clock.post(() -> { if (token == generation) onProgramEnded(); }); break;
                case MediaPlayer.Event.EncounteredError: clock.post(() -> { if (token == generation) onPlaybackError(); }); break;
                default: break;
            }
        });
        channels = ChannelRepository.liveChannels(this); String id = getIntent().getStringExtra("channel_id");
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(id)) { channelIndex = i; break; }
    }

    private TextView label(Typeface font, float size, int color, boolean bold) {
        TextView view = new TextView(this); view.setTextSize(size); view.setTextColor(color); view.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL); return view;
    }
    private GradientDrawable round(int color, float radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }

    @Override protected void onStart() { super.onStart(); paused = false; tune(null, true); }

    /** Tunes the channel's current programme, or {@code override} (from its beginning) when a file proved unplayable. */
    private void tune(ChannelRepository.Program override, boolean fresh) {
        generation++; clock.removeCallbacks(hideInfo); player.stop(); spinner.setVisibility(View.VISIBLE); if (fresh) retries = 0;
        ChannelRepository.Channel channel = channels.get(channelIndex); ChannelRepository.Program program = override != null ? override : channel.currentProgram();
        if (program == null || program.uri == null) { toast(channel.name + " has no scheduled media"); finish(); return; }
        current = program; long token = generation, now = System.currentTimeMillis();
        long offset = override != null ? 0 : Math.max(0, now - program.start), remaining = override != null ? Math.max(500, program.stop - program.start) : Math.max(500, program.stop - now);
        showInfo(channel, program);
        Media media = new Media(Vlc.get(this), Uri.parse(program.uri));
        if (offset > 1500) media.addOption(":start-time=" + (offset / 1000.0));
        media.setHWDecoderEnabled(true, false);
        player.setMedia(media); media.release(); player.play();
        clock.removeCallbacksAndMessages(null); clock.post(tick);
        clock.postDelayed(() -> { if (token == generation) tune(null, true); }, remaining + 250);
        clock.postDelayed(() -> { if (token == generation && spinner.getVisibility() == View.VISIBLE) onPlaybackError(); }, STALL_MS);
        showInfoBriefly();
    }

    private void onProgramEnded() {
        // The file ended: if the schedule still has meaningful time left, move on rather than replaying the same file at its end.
        if (current != null && current.stop - System.currentTimeMillis() > 5000) { ChannelRepository.Program next = nextProgram(); if (next != null && next != current) { tune(next, false); return; } }
        tune(null, true);
    }
    private void onPlaybackError() {
        spinner.setVisibility(View.GONE);
        if (retries++ < MAX_RETRIES) { toast("Retrying…"); long token = generation; clock.postDelayed(() -> { if (token == generation) tune(current, false); }, 1200); return; }
        ChannelRepository.Program next = nextProgram();
        if (next != null && next != current && next.uri != null) { toast("Skipping an unplayable file"); tune(next, false); } else { toast("Could not play this channel"); }
    }
    private ChannelRepository.Program nextProgram() {
        List<ChannelRepository.Program> list = channels.get(channelIndex).programs; int i = list.indexOf(current); return i >= 0 && i + 1 < list.size() ? list.get(i + 1) : null;
    }

    private void showInfo(ChannelRepository.Channel channel, ChannelRepository.Program program) {
        String until = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(program.stop));
        badge.setText(channel.number); title.setText(program.title); subtitle.setText(channel.name + "   •   UNTIL " + until.toUpperCase()); updateProgress();
    }
    private void showInfoBriefly() { infoPanel.animate().cancel(); infoPanel.setAlpha(1); setScrim(1); clock.removeCallbacks(hideInfo); clock.postDelayed(hideInfo, 4500); }
    private void setScrim(float alpha) { View scrim = ((FrameLayout) infoPanel.getParent()).findViewWithTag("scrim"); if (scrim != null) scrim.animate().alpha(alpha).setDuration(300).start(); }
    private void updateProgress() {
        if (current == null) return; long span = Math.max(1, current.stop - current.start); progress.setProgress((int) Math.max(0, Math.min(1000, (System.currentTimeMillis() - current.start) * 1000 / span)));
    }
    private void toast(String message) { notice.setText(message); notice.animate().cancel(); notice.setAlpha(1); clock.removeCallbacks(hideNotice); clock.postDelayed(hideNotice, 2600); }

    private void changeChannel(int direction) {
        if (channels.isEmpty()) return; channelIndex = (channelIndex + direction + channels.size()) % channels.size(); tune(null, true);
    }
    private void cycleAudio() {
        MediaPlayer.TrackDescription[] tracks = player.getAudioTracks(); if (tracks == null || tracks.length < 3) { toast("Only one audio track"); return; }
        int active = player.getAudioTrack(), pick = -1;
        for (int i = 1; i < tracks.length; i++) if (tracks[i].id == active) { pick = i + 1 < tracks.length ? i + 1 : 1; break; }
        if (pick < 0) pick = 1; player.setAudioTrack(tracks[pick].id); toast("Audio: " + tracks[pick].name);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: changeChannel(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: changeChannel(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (paused) { paused = false; tune(null, true); } else { paused = true; player.pause(); toast("❚❚  Paused — resume to rejoin live"); showInfoBriefly(); } return true;
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_SETTINGS: case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: cycleAudio(); return true;
            case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: showInfoBriefly(); return true;
            default: return super.dispatchKeyEvent(event);
        }
    }
    @Override protected void onStop() { generation++; clock.removeCallbacksAndMessages(null); player.stop(); super.onStop(); }
    @Override protected void onDestroy() { player.setEventListener(null); player.detachViews(); player.release(); super.onDestroy(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
