package com.cleantv.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Calendar;

/** Persistent local channel lineup backed by user-selected folders and files. */
final class ChannelRepository {
    static final class Program {
        String title, description, uri; long start, stop;
        Program(String title, String uri, long start, long stop) {
            this.title = title; this.uri = uri; this.start = start; this.stop = stop;
        }
    }
    static final class Channel {
        String id, number, name; int color;
        final List<String> sources = new ArrayList<>();
        final List<Program> programs = new ArrayList<>();
        Program currentProgram() {
            long now = System.currentTimeMillis();
            for (Program program : programs) if (program.start <= now && program.stop > now) return program;
            return programs.isEmpty() ? null : programs.get(0);
        }
        String currentUri() { Program program = currentProgram(); return program == null ? null : program.uri; }
    }

    private static final class Media {
        final String uri, title; final long duration;
        Media(String uri, String title, long duration) { this.uri = uri; this.title = title; this.duration = duration; }
    }

    private static final String PREFS = "local_lineup";
    private static final String KEY = "channels_v1";
    private static final int[] COLORS = {0xFF3F2A24,0xFF273D48,0xFF382B46,0xFF253C35,0xFF443526,0xFF24344A};
    private static volatile List<Channel> cache = new ArrayList<>();

    static List<Channel> load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        List<Channel> channels = new ArrayList<>();
        if (!raw.isEmpty()) try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i); Channel channel = new Channel();
                channel.id = item.optString("id", String.valueOf(i + 1));
                channel.number = item.optString("number", String.valueOf(i + 1));
                channel.name = item.optString("name", "CHANNEL " + (i + 1));
                channel.color = item.optInt("color", COLORS[i % COLORS.length]);
                JSONArray sources = item.optJSONArray("sources");
                if (sources != null) for (int s = 0; s < sources.length(); s++) channel.sources.add(sources.optString(s));
                channels.add(channel);
            }
        } catch (Exception ignored) { channels.clear(); }
        if (channels.isEmpty()) { channels = defaults(); save(context, channels); }
        rebuild(context, channels); cache = channels; return channels;
    }

    static void save(Context context, List<Channel> channels) {
        JSONArray array = new JSONArray();
        try {
            for (Channel channel : channels) {
                JSONObject item = new JSONObject(); item.put("id", channel.id); item.put("number", channel.number);
                item.put("name", channel.name); item.put("color", channel.color);
                JSONArray sources = new JSONArray(); for (String source : channel.sources) sources.put(source);
                item.put("sources", sources); array.put(item);
            }
        } catch (Exception ignored) { }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply();
    }

    static String exportJson(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
    }
    static JSONArray guideJson(Context context) {
        JSONArray guide = new JSONArray(); long now = System.currentTimeMillis();
        try {
            for (Channel channel : liveChannels(context)) {
                JSONObject item = new JSONObject(); item.put("id", channel.id); JSONArray programs = new JSONArray();
                for (Program program : channel.programs) if (program.stop > now - 3600000L) {
                    JSONObject value = new JSONObject(); value.put("title", program.title); value.put("start", program.start); value.put("stop", program.stop); programs.put(value);
                    if (programs.length() >= 16) break;
                }
                item.put("programs", programs); guide.put(item);
            }
        } catch (Exception ignored) { }
        return guide;
    }

    static boolean importJson(Context context, String raw) {
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject channel = array.getJSONObject(i);
                if (channel.optString("name").trim().isEmpty()) return false;
                if (channel.optJSONArray("sources") == null) channel.put("sources", new JSONArray());
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).commit();
            return true;
        } catch (Exception ignored) { return false; }
    }

    static Channel addChannel(List<Channel> channels) {
        Channel channel = new Channel(); int index = channels.size();
        channel.id = String.valueOf(System.currentTimeMillis()); channel.number = String.valueOf(index + 1);
        channel.name = "NEW CHANNEL"; channel.color = COLORS[index % COLORS.length]; channels.add(channel); return channel;
    }

    static void rebuild(Context context, List<Channel> channels) {
        ContentResolver resolver = context.getContentResolver(); long now = System.currentTimeMillis();
        Calendar local = Calendar.getInstance(); local.setTimeInMillis(now); local.set(Calendar.HOUR_OF_DAY, 0); local.set(Calendar.MINUTE, 0); local.set(Calendar.SECOND, 0); local.set(Calendar.MILLISECOND, 0);
        long localMidnight = local.getTimeInMillis();
        for (Channel channel : channels) {
            channel.programs.clear(); List<String> files = new ArrayList<>();
            for (String source : channel.sources) {
                if (source.startsWith("folder:")) scanFolder(new File(source.substring(7)), files, 0);
                else if (source.startsWith("tree:")) scanTree(resolver, Uri.parse(source.substring(5)), files, 0);
                else files.add(source);
                if (files.size() >= 300) break;
            }
            if (files.isEmpty()) {
                long time = now - now % 1800000;
                channel.programs.add(new Program("Assign media in Edit Mode", null, time, time + 3600000));
                channel.programs.add(new Program("Folder or multiple files", null, time + 3600000, time + 7200000));
                channel.programs.add(new Program("Press MENU to edit", null, time + 7200000, time + 10800000));
            } else {
                List<Media> media = new ArrayList<>(); long cycle = 0;
                for (String uri : files) {
                    long duration = duration(context, uri); media.add(new Media(uri, displayName(resolver, Uri.parse(uri)), duration)); cycle += duration;
                }
                long completedCycles = Math.floorDiv(now - localMidnight, Math.max(1L, cycle));
                long time = localMidnight + completedCycles * cycle; int position = 0, guard = 0;
                while (time < now + 6 * 3600000L && guard++ < 2500) {
                    Media item = media.get(position++ % media.size()); long stop = time + item.duration;
                    channel.programs.add(new Program(item.title, item.uri, time, stop)); time = stop;
                }
            }
        }
        cache = channels;
    }
    static List<Channel> liveChannels(Context context) { List<Channel> value = cache; return value.isEmpty() ? load(context) : value; }
    static Channel byId(Context context, String id) {
        for (Channel channel : liveChannels(context)) if (channel.id.equals(id)) return channel; return null;
    }
    private static long duration(Context context, String value) {
        Uri parsed = Uri.parse(value); SharedPreferences cache = context.getSharedPreferences("durations", Context.MODE_PRIVATE); String key = null;
        if ("file".equals(parsed.getScheme())) {
            File file = new File(parsed.getPath()); key = file.getPath() + "|" + file.length() + "|" + file.lastModified();
            long known = cache.getLong(key, 0); if (known > 0) return known;
        }
        long probed = Vlc.probeDuration(context, value);
        if (probed > 0) { probed = Math.max(60_000L, Math.min(12 * 3600000L, probed)); if (key != null) cache.edit().putLong(key, probed).apply(); return probed; }
        return metadataDuration(context, value);
    }
    private static long metadataDuration(Context context, String value) {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            Uri uri = Uri.parse(value);
            if ("file".equals(uri.getScheme())) metadata.setDataSource(uri.getPath()); else metadata.setDataSource(context, uri);
            String raw = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); long result = raw == null ? 0 : Long.parseLong(raw);
            return Math.max(60_000L, Math.min(12 * 3600000L, result));
        } catch (Exception ignored) { return 30 * 60_000L; }
        finally { try { metadata.release(); } catch (Exception ignored) { } }
    }
    private static void scanFolder(File folder, List<String> output, int depth) {
        if (depth > 12 || output.size() >= 300 || folder == null || !folder.isDirectory()) return;
        File[] children = folder.listFiles(); if (children == null) return;
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (output.size() >= 300) return;
            if (child.isDirectory()) scanFolder(child, output, depth + 1);
            else if (isVideo(child.getName(), null)) output.add(Uri.fromFile(child).toString());
        }
    }

    private static void scanTree(ContentResolver resolver, Uri tree, List<String> output, int depth) {
        if (depth > 12 || output.size() >= 300) return;
        try {
            String parent = DocumentsContract.getTreeDocumentId(tree);
            scanChildren(resolver, tree, parent, output, depth);
        } catch (Exception ignored) { }
    }
    private static void scanChildren(ContentResolver resolver, Uri tree, String parent, List<String> output, int depth) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext() && output.size() < 300) {
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanChildren(resolver, tree, id, output, depth + 1);
                else if (isVideo(name, mime)) output.add(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
            }
        } catch (Exception ignored) { }
    }
    private static boolean isVideo(String name, String mime) {
        if (mime != null && mime.startsWith("video/")) return true;
        String lower = name == null ? "" : name.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".webm") || lower.endsWith(".ts");
    }
    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cleanTitle(cursor.getString(0));
        } catch (Exception ignored) { }
        String segment = uri.getLastPathSegment(); return cleanTitle(segment == null ? "Untitled" : segment);
    }
    private static String cleanTitle(String value) {
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf(':')); if (slash >= 0) value = value.substring(slash + 1);
        int dot = value.lastIndexOf('.'); if (dot > 0) value = value.substring(0, dot);
        return value.replace('_', ' ').replace('.', ' ').trim();
    }
    private static List<Channel> defaults() {
        String[] names = {"PYLERIUM ONE", "CINEMA / 24", "RETRO LOOP", "AMBIENT", "WORKSHOP", "MUSIC LAB"};
        List<Channel> channels = new ArrayList<>();
        for (int i = 0; i < names.length; i++) { Channel channel = new Channel(); channel.id = String.valueOf(i + 1); channel.number = String.valueOf(i + 1); channel.name = names[i]; channel.color = COLORS[i]; channels.add(channel); }
        return channels;
    }
}
