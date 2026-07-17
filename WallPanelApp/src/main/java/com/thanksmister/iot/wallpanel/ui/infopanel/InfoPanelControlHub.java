/*
 * Copyright (c) 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.thanksmister.iot.wallpanel.ui.infopanel;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.koushikdutta.async.http.server.AsyncHttpServer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

import timber.log.Timber;

/** Coordinates local touch, LAN HTTP and UDP control through one state machine. */
public class InfoPanelControlHub {
    public interface Listener {
        void onInfoPanelAction(String action, JSONObject state);
    }

    public static final int HTTP_PORT = 8080;
    public static final int UDP_PORT = 40404;

    private static final String AUDIO_URL = "https://m10.music.126.net/20260717194559/ea695780cf9d6ebb11210513b3ba69a2/ymusic/obj/w5zDlMODwrDDiGjCn8Ky/13573238776/73a2/ad6c/e33a/bf7c94b4e8676a0bf820ffd45d7e0a47.mp3?vuutv=T9oIA+zL3YKnu/PppEOewihNCooTlAxL9zpo08IMWhxY4GlB6MFK2y4SRYxsut7nf5v2nu0udJaeg18FW/hVfxePUtPOptACGmZ6TfNs4cs=&cdntag=bWFyaz1vc193ZWI";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Object stateLock = new Object();
    private final AsyncHttpServer httpServer = new AsyncHttpServer();
    private volatile boolean running;
    private volatile DatagramSocket udpSocket;
    private volatile Listener listener;
    private MediaPlayer player;
    private boolean playerPrepared;
    private boolean noisePlaying;
    private boolean noisePreparing;
    private String screen = "clock";
    private String lastError = "";

    public InfoPanelControlHub(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        startHttpServer();
        startUdpServer();
    }

    public void stop() {
        running = false;
        listener = null;
        httpServer.stop();
        DatagramSocket socket = udpSocket;
        if (socket != null) socket.close();
        mainHandler.post(this::releaseNoiseInternal);
    }

    public void performAction(String rawAction) {
        final String action = normalizeAction(rawAction);
        if (action == null) return;
        mainHandler.post(() -> performActionOnMain(action));
    }

    public JSONObject getStateJson() {
        synchronized (stateLock) {
            JSONObject state = new JSONObject();
            try {
                state.put("screen", screen);
                state.put("noisePlaying", noisePlaying);
                state.put("noisePreparing", noisePreparing);
                state.put("lastError", lastError);
                state.put("httpPort", HTTP_PORT);
                state.put("udpPort", UDP_PORT);
            } catch (JSONException ignored) { }
            return state;
        }
    }

    private void performActionOnMain(String action) {
        switch (action) {
            case "left":
                synchronized (stateLock) { screen = "weather"; }
                break;
            case "clock":
                synchronized (stateLock) { screen = "clock"; }
                break;
            case "right":
                synchronized (stateLock) { screen = "media"; }
                break;
            case "center":
            case "noise_toggle":
                if (noisePlaying || noisePreparing) stopNoiseInternal(); else startNoiseInternal();
                break;
            case "noise_start":
                if (!noisePlaying && !noisePreparing) startNoiseInternal();
                break;
            case "noise_stop":
                stopNoiseInternal();
                break;
            default:
                return;
        }
        notifyListener(action);
    }

    private void startNoiseInternal() {
        MediaPlayer reusablePlayer = player;
        if (reusablePlayer != null && playerPrepared) {
            try {
                int duration = reusablePlayer.getDuration();
                if (duration > 1000) reusablePlayer.seekTo(random.nextInt(duration));
                reusablePlayer.start();
                synchronized (stateLock) {
                    noisePreparing = false;
                    noisePlaying = true;
                    lastError = "";
                }
                return;
            } catch (RuntimeException error) {
                Timber.w(error, "Unable to reuse prepared white-noise player");
                releasePlayer();
            }
        }
        releasePlayer();
        synchronized (stateLock) {
            noisePreparing = true;
            noisePlaying = false;
            lastError = "";
        }
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setLooping(true);
            player.setDataSource(AUDIO_URL);
            player.setOnPreparedListener(mediaPlayer -> {
                if (player != mediaPlayer) return;
                playerPrepared = true;
                int duration = mediaPlayer.getDuration();
                if (duration > 1000) mediaPlayer.seekTo(random.nextInt(duration));
                mediaPlayer.start();
                synchronized (stateLock) {
                    noisePreparing = false;
                    noisePlaying = true;
                }
                notifyListener("noise_state");
            });
            player.setOnErrorListener((mediaPlayer, what, extra) -> {
                if (player != mediaPlayer) return true;
                synchronized (stateLock) {
                    noisePreparing = false;
                    noisePlaying = false;
                    lastError = "音频加载失败（" + what + "/" + extra + "）";
                }
                releasePlayer();
                notifyListener("noise_state");
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException error) {
            synchronized (stateLock) {
                noisePreparing = false;
                noisePlaying = false;
                lastError = "音频加载失败：" + error.getMessage();
            }
            releasePlayer();
        }
    }

    private void stopNoiseInternal() {
        MediaPlayer currentPlayer = player;
        if (currentPlayer != null && playerPrepared) {
            try {
                if (currentPlayer.isPlaying()) currentPlayer.pause();
            } catch (RuntimeException error) {
                Timber.w(error, "Unable to pause white-noise player");
                releasePlayer();
            }
        } else if (noisePreparing) {
            releasePlayer();
        }
        synchronized (stateLock) {
            noisePreparing = false;
            noisePlaying = false;
        }
    }

    private void releaseNoiseInternal() {
        releasePlayer();
        synchronized (stateLock) {
            noisePreparing = false;
            noisePlaying = false;
        }
    }

    private void releasePlayer() {
        MediaPlayer oldPlayer = player;
        player = null;
        playerPrepared = false;
        if (oldPlayer != null) {
            try { oldPlayer.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void notifyListener(String action) {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onInfoPanelAction(action, getStateJson());
    }

    private void startHttpServer() {
        httpServer.get("/", (request, response) -> sendAsset(response, "infopanel/remote.html", "text/html; charset=utf-8"));
        httpServer.get("/panel", (request, response) -> sendAsset(response, "infopanel/index.html", "text/html; charset=utf-8"));
        httpServer.get("/panel/", (request, response) -> sendAsset(response, "infopanel/index.html", "text/html; charset=utf-8"));
        httpServer.get("/panel/index.html", (request, response) -> sendAsset(response, "infopanel/index.html", "text/html; charset=utf-8"));
        httpServer.get("/panel/style.css", (request, response) -> sendAsset(response, "infopanel/style.css", "text/css; charset=utf-8"));
        httpServer.get("/panel/app.js", (request, response) -> sendAsset(response, "infopanel/app.js", "application/javascript; charset=utf-8"));
        httpServer.get("/api/state", (request, response) -> {
            response.getHeaders().set("Cache-Control", "no-store");
            response.send(getStateJson());
        });
        httpServer.get("/api/action", (request, response) -> {
            String action = request.getQuery().getString("name");
            String normalized = normalizeAction(action);
            if (normalized == null) {
                response.code(400).send("application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown action\"}");
                return;
            }
            mainHandler.post(() -> {
                performActionOnMain(normalized);
                JSONObject result = getStateJson();
                try { result.put("ok", true); } catch (JSONException ignored) { }
                response.getHeaders().set("Cache-Control", "no-store");
                response.send(result);
            });
        });
        httpServer.setErrorCallback(error -> Timber.e(error, "InfoPanel HTTP server stopped"));
        try {
            httpServer.listen(HTTP_PORT);
        } catch (RuntimeException error) {
            Timber.e(error, "Unable to start InfoPanel HTTP server");
        }
    }

    private void sendAsset(com.koushikdutta.async.http.server.AsyncHttpServerResponse response,
                           String path,
                           String contentType) {
        response.getHeaders().set("Cache-Control", "no-store");
        response.send(contentType, readAsset(path));
    }

    private String readAsset(String path) {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (IOException error) {
            Timber.e(error, "Unable to read InfoPanel server page");
            return "<!doctype html><meta charset=utf-8><title>InfoPanel</title><p>InfoPanel page unavailable</p>";
        }
    }

    private void startUdpServer() {
        Thread udpThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                DatagramSocket socket = new DatagramSocket(null);
                udpSocket = socket;
                socket.setReuseAddress(true);
                socket.setSoTimeout(1000);
                socket.bind(new InetSocketAddress(UDP_PORT));
                while (running) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    }
                    String command = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                    String action = parseUdpCommand(command);
                    String reply;
                    if (action == null) {
                        reply = "INFOPANEL ERROR expected: INFOPANEL NOISE START|STOP|TOGGLE";
                    } else {
                        performAction(action);
                        reply = "INFOPANEL OK " + action.toUpperCase(Locale.US);
                    }
                    byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(replyBytes, replyBytes.length, packet.getAddress(), packet.getPort()));
                }
            } catch (IOException error) {
                if (running) Timber.e(error, "InfoPanel UDP server stopped");
            } finally {
                DatagramSocket socket = udpSocket;
                if (socket != null) socket.close();
                udpSocket = null;
            }
        }, "InfoPanel-UDP");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    private String parseUdpCommand(String command) {
        String normalized = command.trim().replaceAll("\\s+", " ").toUpperCase(Locale.US);
        if ("INFOPANEL NOISE START".equals(normalized)) return "noise_start";
        if ("INFOPANEL NOISE STOP".equals(normalized)) return "noise_stop";
        if ("INFOPANEL NOISE TOGGLE".equals(normalized)) return "noise_toggle";
        return null;
    }

    private String normalizeAction(String action) {
        if (action == null) return null;
        String normalized = action.trim().toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
        switch (normalized) {
            case "left":
            case "center":
            case "right":
            case "clock":
            case "noise_start":
            case "noise_stop":
            case "noise_toggle":
                return normalized;
            default:
                return null;
        }
    }
}
