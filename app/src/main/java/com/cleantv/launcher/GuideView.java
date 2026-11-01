package com.cleantv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class GuideView extends View {
    interface Listener { void play(ChannelRepository.Channel channel); void setup(); void refresh(); }
    private static final int ORANGE = 0xFFFF7B1C, CYAN = 0xFF26D8EE;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<ChannelRepository.Channel> channels = new ArrayList<>();
    private final float density;
    private Listener listener;
    private int row, column, firstRow;
    private int visibleRows = 4;
    private long windowStart;
    private float channelWidth, headerHeight, rowHeight, minuteWidth, detailsHeight, clipRight = -1;

    public GuideView(Context context) {
        super(context); density = getResources().getDisplayMetrics().density;
        setFocusable(true); setFocusableInTouchMode(true); setLayerType(View.LAYER_TYPE_HARDWARE, null);
        try { paint.setTypeface(Typeface.createFromAsset(context.getAssets(), "fonts/Onest.ttf")); }
        catch (RuntimeException ignored) { paint.setTypeface(Typeface.DEFAULT); }
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(2)); stroke.setColor(ORANGE);
        resetTime();
    }
    void setListener(Listener listener) { this.listener = listener; }
    int selectedChannelIndex() { return row; }
    void setChannels(List<ChannelRepository.Channel> value) {
        channels.clear(); channels.addAll(value); row = 0; column = currentProgramIndex(0); firstRow = 0; resetTime(); invalidate(); requestFocus();
    }
    private void resetTime() { long now = System.currentTimeMillis(); windowStart = now - now % 1800000; }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        channelWidth = Math.max(dp(210), w * .20f); headerHeight = dp(48); detailsHeight = dp(84);
        rowHeight = Math.max(dp(72), (h - headerHeight - detailsHeight - dp(6)) / 4f); minuteWidth = (w - channelWidth) / 120f;
        visibleRows = Math.max(1, (int) ((h - headerHeight - detailsHeight + dp(8)) / rowHeight));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (channels.isEmpty()) return;
        drawHeader(canvas);
        int visible = Math.min(visibleRows, channels.size() - firstRow);
        for (int i = 0; i < visible; i++) drawRow(canvas, firstRow + i, headerHeight + i * rowHeight);
        float nowX = channelWidth + (System.currentTimeMillis() - windowStart) / 60000f * minuteWidth;
        if (nowX >= channelWidth && nowX <= getWidth()) {
            paint.setColor(ORANGE); canvas.drawRect(nowX, headerHeight - dp(5), nowX + dp(2), headerHeight + visible * rowHeight, paint);
            canvas.drawCircle(nowX + dp(1), headerHeight - dp(6), dp(5), paint);
        }
        drawDetails(canvas);
    }
    private void drawDetails(Canvas canvas) {
        ChannelRepository.Channel channel = channels.get(row); if (channel.programs.isEmpty()) return;
        ChannelRepository.Program program = channel.programs.get(Math.min(column, channel.programs.size() - 1)); long now = System.currentTimeMillis();
        float top = getHeight() - detailsHeight + dp(6), bottom = getHeight() - dp(2);
        paint.setColor(0xF2101820); canvas.drawRoundRect(0, top, getWidth(), bottom, dp(12), dp(12), paint);
        paint.setColor(channel.color); canvas.drawRoundRect(0, top, dp(6), bottom, dp(3), dp(3), paint);
        boolean live = program.start <= now && program.stop > now, past = program.stop <= now; long minutes = Math.max(1, (program.stop - program.start) / 60000);
        String state = live ? "LIVE NOW" : past ? "EARLIER" : "UP NEXT"; int accent = live ? ORANGE : CYAN;
        clipRight = getWidth() - dp(24);
        label(canvas, state + "   •   CH " + channel.number + "  " + channel.name, dp(24), top + dp(26), 11, accent, true);
        label(canvas, program.title, dp(24), top + dp(52), 20, Color.WHITE, true);
        String length = minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60 == 0 ? "" : (minutes % 60) + " min") : minutes + " min";
        label(canvas, timeRange(program) + "   •   " + length.trim() + (program.uri == null ? "   •   PRESS MENU TO ASSIGN MEDIA" : live ? "   •   PRESS OK TO WATCH" : ""), dp(24), top + dp(72), 12, 0xFF8FA2AC, false);
        clipRight = -1;
    }
    private void drawHeader(Canvas canvas) {
        paint.setColor(0xFF11191E); canvas.drawRoundRect(0, 0, getWidth(), headerHeight - dp(5), dp(10), dp(10), paint);
        label(canvas, "CHANNEL", dp(18), dp(29), 11, 0xFF758892, true);
        SimpleDateFormat format = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (int m = 0; m <= 120; m += 30) {
            float x = channelWidth + m * minuteWidth;
            label(canvas, format.format(new Date(windowStart + m * 60000L)), x + dp(12), dp(29), 11, 0xFFA3B4BC, false);
            paint.setColor(0xFF27343B); canvas.drawRect(x, headerHeight - dp(10), x + 1, getHeight(), paint);
        }
    }
    private void drawRow(Canvas canvas, int index, float top) {
        ChannelRepository.Channel channel = channels.get(index); float bottom = top + rowHeight - dp(7);
        paint.setColor(index == row ? 0xFF182329 : 0xE911181D); canvas.drawRoundRect(0, top, channelWidth - dp(8), bottom, dp(10), dp(10), paint);
        paint.setColor(channel.color); canvas.drawRoundRect(dp(10), top + dp(10), dp(58), bottom - dp(10), dp(7), dp(7), paint);
        label(canvas, channel.number, dp(34), top + rowHeight / 2 + dp(5), 14, Color.WHITE, false, Paint.Align.CENTER);
        clipRight = channelWidth - dp(16);
        label(canvas, channel.name, dp(72), top + dp(35), 14, 0xFFF0F3F4, true);
        String live = currentTitle(channel); label(canvas, live, dp(72), top + dp(57), 11, 0xFF82959F, false);
        clipRight = -1;

        for (int p = 0; p < channel.programs.size(); p++) {
            ChannelRepository.Program program = channel.programs.get(p);
            float left = channelWidth + (program.start - windowStart) / 60000f * minuteWidth;
            float right = channelWidth + (program.stop - windowStart) / 60000f * minuteWidth;
            if (right < channelWidth || left > getWidth()) continue;
            left = Math.max(channelWidth + dp(3), left + dp(3)); right = Math.min(getWidth(), right - dp(3));
            boolean selected = index == row && p == column;
            paint.setColor(selected ? 0xFF39271D : ((p & 1) == 0 ? 0xFF202A30 : 0xFF1C252A));
            canvas.drawRoundRect(left, top, right, bottom, dp(9), dp(9), paint);
            if (selected) canvas.drawRoundRect(left + dp(1), top + dp(1), right - dp(1), bottom - dp(1), dp(8), dp(8), stroke);
            long now = System.currentTimeMillis();
            if (program.start <= now && program.stop > now) {
                float fraction = (now - program.start) / (float) Math.max(1, program.stop - program.start);
                paint.setColor(0x33FF7B1C); canvas.drawRoundRect(left + dp(14), bottom - dp(9), right - dp(14), bottom - dp(6), dp(1.5f), dp(1.5f), paint);
                paint.setColor(ORANGE); canvas.drawRoundRect(left + dp(14), bottom - dp(9), left + dp(14) + (right - left - dp(28)) * fraction, bottom - dp(6), dp(2), dp(2), paint);
            }
            clipRight = right - dp(10);
            label(canvas, program.title, left + dp(14), top + dp(30), selected ? 15 : 14, selected ? Color.WHITE : 0xFFD5DDE1, selected);
            label(canvas, timeRange(program), left + dp(14), top + dp(50), 10, selected ? 0xFFFFAF7B : 0xFF768992, false);
            clipRight = -1;
        }
    }
    private String currentTitle(ChannelRepository.Channel channel) {
        long now = System.currentTimeMillis(); for (ChannelRepository.Program p : channel.programs) if (p.start <= now && p.stop > now) return "NOW  " + p.title; return "GUIDE READY";
    }
    private String timeRange(ChannelRepository.Program p) { SimpleDateFormat f = new SimpleDateFormat("h:mm", Locale.getDefault()); return f.format(new Date(p.start)) + " – " + f.format(new Date(p.stop)); }
    private void label(Canvas c, String value, float x, float y, float size, int color, boolean bold) { label(c, value, x, y, size, color, bold, Paint.Align.LEFT); }
    private void label(Canvas c, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
        paint.setTextSize(dp(size)); paint.setColor(color); paint.setTextAlign(align); paint.setTypeface(Typeface.create(paint.getTypeface(), bold ? Typeface.BOLD : Typeface.NORMAL));
        c.save(); c.clipRect(x - (align == Paint.Align.CENTER ? dp(24) : 0), y - dp(26), clipRight > 0 ? clipRight : getWidth() - dp(6), y + dp(8)); c.drawText(fit(value == null ? "" : value, (clipRight > 0 ? clipRight : getWidth() - dp(6)) - x), x, y, paint); c.restore();
    }
    private String fit(String value, float width) {
        if (value.isEmpty() || width <= 0) return value; int fits = paint.breakText(value, true, width, null);
        return fits >= value.length() ? value : value.substring(0, Math.max(0, fits - 1)).trim() + "…";
    }
    private int currentProgramIndex(int channelIndex) {
        if (channels.isEmpty() || channelIndex >= channels.size()) return 0; long now = System.currentTimeMillis(); List<ChannelRepository.Program> list = channels.get(channelIndex).programs;
        for (int i = 0; i < list.size(); i++) if (list.get(i).start <= now && list.get(i).stop > now) return i; return 0;
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (channels.isEmpty()) return super.onKeyDown(key, event);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: row = Math.max(0, row - 1); keepVisible(); column = currentProgramIndex(row); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: row = Math.min(channels.size() - 1, row + 1); keepVisible(); column = currentProgramIndex(row); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: column = Math.max(0, column - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: column = Math.min(Math.max(0, channels.get(row).programs.size() - 1), column + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: if (listener != null) listener.play(channels.get(row)); break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: if (listener != null) listener.play(channels.get(row)); break;
            case KeyEvent.KEYCODE_REFRESH: if (listener != null) listener.refresh(); break;
            default: return super.onKeyDown(key, event);
        }
        invalidate(); return true;
    }
    private void keepVisible() { if (row < firstRow) firstRow = row; if (row >= firstRow + visibleRows) firstRow = row - visibleRows + 1; }
    private float dp(float value) { return value * density; }
}
