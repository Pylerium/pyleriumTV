package com.cleantv.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Persistent paired sync endpoint. Discovery never exposes channel or media data. */
public final class SyncService extends Service {
    static final int DISCOVERY_PORT = 45872, SYNC_PORT = 45873, STREAM_PORT = 45874;
    static final String CHANGED = "com.pylerium.tv.LINEUP_CHANGED";
    private static final String PREFS = "paired_studio";
    private static final String DISCOVERY = "PYLERIUM_TV_DISCOVER_V1";
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Set<String> usedNonces = java.util.Collections.synchronizedSet(new HashSet<>());
    private final ConcurrentHashMap<String, Upload> uploads = new ConcurrentHashMap<>();
    private volatile boolean running;
    private DatagramSocket udp;
    private ServerSocket tcp;
    private ServerSocket streamTcp;

    static void start(Context context) {
        Intent intent = new Intent(context, SyncService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
    static String newPairCode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String code = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1000000));
        prefs.edit().putString("pair_code", code).putLong("pair_expires", System.currentTimeMillis() + 300000).apply();
        start(context); return code;
    }
    static void revokeAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE); SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith("client_")) edit.remove(key);
        edit.remove("pair_code").remove("pair_expires").apply();
    }

    @Override public void onCreate() {
        super.onCreate(); createNotification(); running = true;
        workers.execute(this::cleanOrphans); workers.execute(this::discoveryLoop); workers.execute(this::serverLoop); workers.execute(this::streamLoop);
    }
    private void createNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel("studio_link", "Pylerium Studio Link", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "studio_link").setSmallIcon(R.drawable.pylerium_logo)
                .setContentTitle("pyleriumTV studio link").setContentText("Ready for your paired desktop studio").setOngoing(true).setContentIntent(open).build();
        startForeground(9204, notification);
    }
    private String deviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE); String id = prefs.getString("device_id", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); prefs.edit().putString("device_id", id).apply(); } return id;
    }
    private void discoveryLoop() {
        byte[] buffer = new byte[512];
        try {
            udp = new DatagramSocket(DISCOVERY_PORT); udp.setBroadcast(true);
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length); udp.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                if (!DISCOVERY.equals(request)) continue;
                JSONObject response = new JSONObject(); response.put("protocol", 1); response.put("device_id", deviceId()); response.put("name", android.os.Build.MODEL + " / pyleriumTV"); response.put("port", SYNC_PORT); response.put("version", "0.7");
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                udp.send(new DatagramPacket(bytes, bytes.length, packet.getAddress(), packet.getPort()));
            }
        } catch (Exception ignored) { }
    }
    private void serverLoop() {
        try {
            tcp = new ServerSocket(SYNC_PORT); tcp.setReuseAddress(true);
            while (running) { Socket socket = tcp.accept(); socket.setSoTimeout(120000); socket.setReceiveBufferSize(1024 * 1024); socket.setSendBufferSize(1024 * 1024); workers.execute(() -> handle(socket)); }
        } catch (Exception ignored) { }
    }
    private void streamLoop() {
        try {
            streamTcp = new ServerSocket(STREAM_PORT); streamTcp.setReuseAddress(true);
            while (running) { Socket socket = streamTcp.accept(); socket.setSoTimeout(120000); socket.setReceiveBufferSize(2 * 1024 * 1024); workers.execute(() -> receiveStream(socket)); }
        } catch (Exception ignored) { }
    }
    private void receiveStream(Socket socket) {
        Upload upload = null;
        try (Socket connection = socket; BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 1024 * 1024); BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
            JSONObject header = new JSONObject(readLine(input, 8192)); String id = header.optString("upload_id"); upload = uploads.remove(id);
            if (upload == null || !upload.client.equals(header.optString("client_id"))) throw new SecurityException("Upload session expired");
            String secret = getSharedPreferences(PREFS, MODE_PRIVATE).getString("client_" + upload.client, ""); byte[] iv = Base64.decode(header.optString("iv"), Base64.NO_WRAP);
            if (secret.isEmpty() || iv.length != 16) throw new SecurityException("Invalid stream identity");
            byte[] encryptionKey = MessageDigest.getInstance("SHA-256").digest((secret + ":upload:" + id).getBytes(StandardCharsets.UTF_8));
            byte[] macKey = MessageDigest.getInstance("SHA-256").digest((secret + ":mac:" + id).getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            Mac streamMac = Mac.getInstance("HmacSHA256"); streamMac.init(new SecretKeySpec(macKey, "HmacSHA256")); streamMac.update(iv);
            MessageDigest fileHash = MessageDigest.getInstance("SHA-256"); long remaining = upload.size; byte[] buffer = new byte[1024 * 1024];
            try (FileOutputStream file = new FileOutputStream(upload.part)) {
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining)); if (read < 0) throw new java.io.EOFException();
                    streamMac.update(buffer, 0, read); byte[] plain = cipher.update(buffer, 0, read); if (plain != null) { file.write(plain); fileHash.update(plain); } remaining -= read;
                }
                byte[] tail = cipher.doFinal(); if (tail != null && tail.length > 0) { file.write(tail); fileHash.update(tail); }
                file.getFD().sync();
            }
            byte[] suppliedMac = readExactly(input, 32); if (!MessageDigest.isEqual(streamMac.doFinal(), suppliedMac) || upload.part.length() != upload.size) throw new SecurityException("Stream verification failed");
            publish(upload); sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
            JSONObject result = new JSONObject(); result.put("ok", true); result.put("path", upload.file.getPath()); result.put("size", upload.file.length()); result.put("sha256", hex(fileHash.digest()));
            output.write((result.toString() + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); upload = null;
        } catch (Exception error) {
            if (upload != null) upload.abort();
        }
    }
    private static String readLine(BufferedInputStream input, int limit) throws Exception {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream(); int value;
        while ((value = input.read()) >= 0 && value != '\n') { if (line.size() >= limit) throw new java.io.IOException("Header too large"); line.write(value); }
        if (value < 0) throw new java.io.EOFException(); return line.toString("UTF-8");
    }
    private static byte[] readExactly(BufferedInputStream input, int length) throws Exception {
        byte[] bytes = new byte[length]; int offset = 0; while (offset < length) { int read = input.read(bytes, offset, length - offset); if (read < 0) throw new java.io.EOFException(); offset += read; } return bytes;
    }
    private void handle(Socket socket) {
        try (Socket connection = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 12_000_000) return;
                JSONObject request = new JSONObject(line); String action = request.optString("action"); String client = request.optString("client_id"); String nonce = request.optString("nonce");
                JSONObject response;
                if ("pair".equals(action)) response = pair(request, client, nonce);
                else response = authenticated(request, action, client, nonce);
                writer.write(response.toString()); writer.write("\n"); writer.flush();
            }
        } catch (Exception ignored) { }
    }
    private JSONObject pair(JSONObject request, String client, String nonce) throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE); String code = prefs.getString("pair_code", ""); long expires = prefs.getLong("pair_expires", 0);
        if (code.isEmpty() || System.currentTimeMillis() > expires || client.isEmpty() || nonce.isEmpty()) return error("Pairing code expired");
        String expected = hmac(code, client + "\n" + nonce);
        if (!constant(expected, request.optString("proof"))) return error("Pairing code incorrect");
        String secret = sha256("pyleriumtv:" + code + ":" + client + ":" + deviceId());
        prefs.edit().putString("client_" + client, secret).remove("pair_code").remove("pair_expires").apply();
        JSONObject payload = new JSONObject(); payload.put("device_id", deviceId()); payload.put("name", android.os.Build.MODEL + " / pyleriumTV");
        return response(true, nonce, payload.toString(), secret);
    }
    private JSONObject authenticated(JSONObject request, String action, String client, String nonce) throws Exception {
        String secret = getSharedPreferences(PREFS, MODE_PRIVATE).getString("client_" + client, "");
        long timestamp = request.optLong("timestamp"); String payload64 = request.optString("payload");
        if (secret.isEmpty()) return error("Device is not paired");
        if (Math.abs(System.currentTimeMillis() - timestamp) > 120000 || nonce.isEmpty() || !usedNonces.add(nonce)) return error("Expired or repeated request");
        if (usedNonces.size() > 2000) usedNonces.clear();
        String signed = action + "\n" + timestamp + "\n" + nonce + "\n" + payload64;
        if (!constant(hmac(secret, signed), request.optString("signature"))) return error("Signature rejected");
        String payloadText = new String(decrypt(secret, action, payload64), StandardCharsets.UTF_8);
        JSONObject output = new JSONObject();
        if ("state".equals(action)) {
            JSONArray guide = ChannelRepository.guideJson(this); output.put("lineup", new JSONArray(ChannelRepository.exportJson(this))); output.put("guide", guide);
            output.put("device_time", System.currentTimeMillis()); output.put("timezone", java.util.TimeZone.getDefault().getID());
        }
        else if ("inventory".equals(action)) output = inventory();
        else if ("upload_start".equals(action)) output = uploadStart(new JSONObject(payloadText), client);
        else if ("upload_chunk".equals(action)) output = uploadChunk(new JSONObject(payloadText), client);
        else if ("upload_finish".equals(action)) output = uploadFinish(new JSONObject(payloadText), client);
        else if ("push".equals(action)) {
            JSONObject payload = new JSONObject(payloadText); boolean saved = ChannelRepository.importJson(this, payload.getJSONArray("lineup").toString());
            if (!saved) return error("Lineup validation failed");
            sendBroadcast(new Intent(CHANGED).setPackage(getPackageName())); output.put("saved", true);
            output.put("applied_at", System.currentTimeMillis()); output.put("timezone", java.util.TimeZone.getDefault().getID());
        } else return error("Unknown action");
        if (output.has("ok") && !output.optBoolean("ok", true)) return output;
        return response(true, nonce, output.toString(), secret);
    }
    /** An in-flight upload. Bytes land in a hidden {@code .part} file and only become a library video once verified. */
    private static final class Upload {
        final String client; final File file, part; final long size; volatile long touched = System.currentTimeMillis();
        Upload(String client, File file, long size) { this.client = client; this.file = file; this.size = size; this.part = partFor(file); }
        void abort() { part.delete(); }
    }
    private static File partFor(File file) { return new File(file.getParentFile(), "." + file.getName() + ".part"); }
    /** Fsyncs and atomically publishes a verified upload under its final name. */
    private static void publish(Upload upload) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(upload.part)) { in.getFD().sync(); } catch (java.io.IOException ignored) { }
        if (!upload.part.renameTo(upload.file)) throw new java.io.IOException("Could not finalize " + upload.file.getName());
    }
    private void sweepStaleUploads() {
        long cutoff = System.currentTimeMillis() - 15 * 60_000L;
        for (java.util.Map.Entry<String, Upload> entry : uploads.entrySet()) if (entry.getValue().touched < cutoff && uploads.remove(entry.getKey(), entry.getValue())) entry.getValue().abort();
    }
    /** Removes partial files left behind by a previous crash or dropped connection. */
    private void cleanOrphans() {
        File[] children = importRoot().listFiles(); if (children == null) return;
        for (File child : children) if (child.getName().endsWith(".part") && child.getName().startsWith(".")) child.delete();
    }
    private JSONObject uploadStart(JSONObject payload, String client) throws Exception {
        String name = new File(payload.optString("name", "movie.mp4")).getName().replaceAll("[^A-Za-z0-9._ ()-]", "_");
        long size = payload.optLong("size", -1);
        if (size < 0 || size > 2_000_000_000_000L || !isVideo(name)) return error("Invalid movie upload");
        sweepStaleUploads();
        File root = importRoot(); if (!root.exists() && !root.mkdirs()) return error("TV storage is not writable");
        if (root.getUsableSpace() < size + 64L * 1024 * 1024) return error("Not enough free space on the TV drive");
        File file = uniqueFile(root, name);
        String id = UUID.randomUUID().toString(); uploads.put(id, new Upload(client, file, size));
        JSONObject out = new JSONObject(); out.put("upload_id", id); out.put("path", file.getPath()); return out;
    }
    private JSONObject uploadChunk(JSONObject payload, String client) throws Exception {
        String id = payload.optString("upload_id"); Upload upload = uploads.get(id); if (upload == null || !upload.client.equals(client)) return error("Upload session expired");
        long offset = payload.optLong("offset", -1); byte[] bytes = Base64.decode(payload.optString("data"), Base64.NO_WRAP);
        if (offset < 0 || offset + bytes.length > upload.size || bytes.length > 4 * 1024 * 1024) return error("Invalid upload chunk");
        try (RandomAccessFile file = new RandomAccessFile(upload.part, "rw")) { file.seek(offset); file.write(bytes); } upload.touched = System.currentTimeMillis();
        JSONObject out = new JSONObject(); out.put("received", offset + bytes.length); return out;
    }
    private JSONObject uploadFinish(JSONObject payload, String client) throws Exception {
        String id = payload.optString("upload_id"); Upload upload = uploads.remove(id); if (upload == null || !upload.client.equals(client)) return error("Upload session expired");
        String expected = payload.optString("sha256");
        if (expected.length() != 64 || upload.part.length() != upload.size || !expected.equals(fileSha256(upload.part))) { upload.abort(); return error("Movie verification failed"); }
        publish(upload); sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
        JSONObject out = new JSONObject(); out.put("path", upload.file.getPath()); out.put("size", upload.file.length()); return out;
    }
    private File importRoot() {
        StorageManager manager = getSystemService(StorageManager.class);
        if (manager != null && android.os.Build.VERSION.SDK_INT >= 30) for (StorageVolume volume : manager.getStorageVolumes()) {
            File root = volume.getDirectory(); if (root != null && volume.isRemovable() && root.canWrite()) return new File(root, "PyleriumTV Imports");
        }
        File root = getExternalFilesDir(null); return new File(root == null ? getFilesDir() : root, "Imports");
    }
    private File uniqueFile(File root, String name) {
        File candidate = new File(root, name); int dot = name.lastIndexOf('.'); String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; candidate.exists() || partFor(candidate).exists(); i++) candidate = new File(root, base + " (" + i + ")" + ext); return candidate;
    }
    private String fileSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[1024 * 1024];
        try (FileInputStream input = new FileInputStream(file)) { int read; while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read); }
        return hex(digest.digest());
    }
    private JSONObject inventory() throws Exception {
        JSONObject output = new JSONObject(); JSONArray drives = new JSONArray(), files = new JSONArray();
        StorageManager manager = getSystemService(StorageManager.class); int[] count = {0};
        if (manager != null && android.os.Build.VERSION.SDK_INT >= 30) for (StorageVolume volume : manager.getStorageVolumes()) {
            File root = volume.getDirectory(); if (root == null || !root.canRead()) continue;
            JSONObject drive = new JSONObject(); drive.put("name", volume.getDescription(this)); drive.put("path", root.getPath()); drive.put("removable", volume.isRemovable()); drives.put(drive);
            scan(root, files, count, 0);
        }
        output.put("drives", drives); output.put("files", files); output.put("truncated", count[0] >= 10000); return output;
    }
    private void scan(File folder, JSONArray output, int[] count, int depth) {
        if (depth > 14 || count[0] >= 10000) return; File[] children = folder.listFiles(); if (children == null) return;
        java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : children) {
            if (count[0] >= 10000) return;
            if (file.isDirectory()) scan(file, output, count, depth + 1);
            else if (isVideo(file.getName())) try { JSONObject item = new JSONObject(); item.put("path", file.getPath()); item.put("name", file.getName()); item.put("folder", file.getParent()); item.put("size", file.length()); output.put(item); count[0]++; } catch (Exception ignored) { }
        }
    }
    private boolean isVideo(String name) { String n = name.toLowerCase(); return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov") || n.endsWith(".m4v") || n.endsWith(".webm") || n.endsWith(".ts"); }
    private JSONObject response(boolean ok, String nonce, String payload, String secret) throws Exception {
        String encoded = encrypt(secret, "response", payload.getBytes(StandardCharsets.UTF_8)); JSONObject result = new JSONObject(); result.put("ok", ok); result.put("nonce", nonce); result.put("payload", encoded); result.put("signature", hmac(secret, ok + "\n" + nonce + "\n" + encoded)); return result;
    }
    private JSONObject error(String message) { JSONObject result = new JSONObject(); try { result.put("ok", false); result.put("error", message); } catch (Exception ignored) { } return result; }
    private static String hmac(String secret, String value) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
    private static String sha256(String value) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static byte[] secretBytes(String secret) {
        byte[] out = new byte[secret.length() / 2]; for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(secret.substring(i * 2, i * 2 + 2), 16); return out;
    }
    private static String encrypt(String secret, String purpose, byte[] plain) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secretBytes(secret), "AES"), new GCMParameterSpec(128, nonce)); cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain), joined = new byte[nonce.length + encrypted.length]; System.arraycopy(nonce, 0, joined, 0, nonce.length); System.arraycopy(encrypted, 0, joined, nonce.length, encrypted.length);
        return Base64.encodeToString(joined, Base64.NO_WRAP);
    }
    private static byte[] decrypt(String secret, String purpose, String encoded) throws Exception {
        byte[] raw = Base64.decode(encoded, Base64.NO_WRAP), nonce = java.util.Arrays.copyOfRange(raw, 0, 12), encrypted = java.util.Arrays.copyOfRange(raw, 12, raw.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretBytes(secret), "AES"), new GCMParameterSpec(128, nonce)); cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8)); return cipher.doFinal(encrypted);
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format(java.util.Locale.US, "%02x", value & 255)); return out.toString(); }
    private static boolean constant(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public void onDestroy() { running = false; try { if (udp != null) udp.close(); if (tcp != null) tcp.close(); if (streamTcp != null) streamTcp.close(); } catch (Exception ignored) { } workers.shutdownNow(); super.onDestroy(); }
}
