/*
 * Copyright (c) 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.thanksmister.iot.wallpanel.ui.infopanel;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.core.content.ContextCompat;

import com.thanksmister.iot.wallpanel.persistence.Configuration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InfoPanelRepository {
    private static final String PREFS = "infopanel_cache";
    private static final String KEY_WEATHER_JSON = "weather_json";
    private static final String KEY_WEATHER_FETCHED_AT = "weather_fetched_at";
    private static final String KEY_WEATHER_ERROR = "weather_error";
    private static final long WEATHER_RETRY_MS = 5L * 60L * 1000L;

    private final Context appContext;
    private final Configuration configuration;
    private final SharedPreferences cache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean weatherRefreshRunning = false;
    private volatile long lastWeatherAttempt = 0L;

    public InfoPanelRepository(Context context, Configuration configuration) {
        this.appContext = context.getApplicationContext();
        this.configuration = configuration;
        this.cache = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public JSONObject getSnapshot() {
        refreshWeatherIfStale(false);
        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put("now", System.currentTimeMillis());
            snapshot.put("settings", settingsJson());
            snapshot.put("weather", cachedWeatherJson());
            snapshot.put("calendar", calendarJson());
        } catch (JSONException ignored) {
        }
        return snapshot;
    }

    public void refreshWeatherNow() {
        refreshWeatherIfStale(true);
    }

    public boolean hasCalendarPermission() {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private JSONObject settingsJson() throws JSONException {
        JSONObject settings = new JSONObject();
        settings.put("temperatureUnit", configuration.getInfoPanelUnits());
        settings.put("calendarDays", configuration.getInfoPanelCalendarDays());
        settings.put("calendarLimit", configuration.getInfoPanelCalendarLimit());
        return settings;
    }

    private JSONObject cachedWeatherJson() throws JSONException {
        String raw = cache.getString(KEY_WEATHER_JSON, "");
        JSONObject weather = raw == null || raw.length() == 0 ? new JSONObject() : new JSONObject(raw);
        weather.put("fetchedAt", cache.getLong(KEY_WEATHER_FETCHED_AT, 0L));
        weather.put("error", cache.getString(KEY_WEATHER_ERROR, ""));
        return weather;
    }

    private JSONObject calendarJson() throws JSONException {
        JSONObject calendar = new JSONObject();
        calendar.put("permissionGranted", hasCalendarPermission());
        JSONArray events = new JSONArray();
        calendar.put("events", events);
        if (!hasCalendarPermission()) {
            return calendar;
        }

        long start = System.currentTimeMillis();
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.setTimeInMillis(start);
        endCalendar.add(Calendar.DAY_OF_YEAR, configuration.getInfoPanelCalendarDays());
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(builder, start);
        ContentUris.appendId(builder, endCalendar.getTimeInMillis());

        String[] projection = new String[]{
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY
        };

        Cursor cursor = null;
        try {
            cursor = appContext.getContentResolver().query(
                    builder.build(),
                    projection,
                    null,
                    null,
                    CalendarContract.Instances.BEGIN + " ASC");
            int limit = configuration.getInfoPanelCalendarLimit();
            while (cursor != null && cursor.moveToNext() && events.length() < limit) {
                JSONObject event = new JSONObject();
                event.put("title", cursor.getString(0) == null ? "" : cursor.getString(0));
                event.put("begin", cursor.getLong(1));
                event.put("end", cursor.getLong(2));
                event.put("location", cursor.getString(3) == null ? "" : cursor.getString(3));
                event.put("allDay", cursor.getInt(4) == 1);
                events.put(event);
            }
        } catch (Exception e) {
            calendar.put("error", e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return calendar;
    }

    private void refreshWeatherIfStale(boolean force) {
        long now = System.currentTimeMillis();
        long fetchedAt = cache.getLong(KEY_WEATHER_FETCHED_AT, 0L);
        long interval = Math.max(5, configuration.getInfoPanelRefreshMinutes()) * 60L * 1000L;
        boolean stale = now - fetchedAt > interval;
        boolean retryReady = now - lastWeatherAttempt > WEATHER_RETRY_MS;
        if (weatherRefreshRunning || (!force && !stale) || (!force && !retryReady)) {
            return;
        }

        weatherRefreshRunning = true;
        lastWeatherAttempt = now;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject weather = fetchWeather();
                    cache.edit()
                            .putString(KEY_WEATHER_JSON, weather.toString())
                            .putLong(KEY_WEATHER_FETCHED_AT, System.currentTimeMillis())
                            .putString(KEY_WEATHER_ERROR, "")
                            .apply();
                } catch (Exception e) {
                    cache.edit().putString(KEY_WEATHER_ERROR, e.getMessage() == null ? "Weather unavailable" : e.getMessage()).apply();
                } finally {
                    weatherRefreshRunning = false;
                }
            }
        });
    }

    private JSONObject fetchWeather() throws Exception {
        String endpoint = configuration.getInfoPanelWeatherEndpoint().trim();
        String url = endpoint.length() > 0 ? endpoint : openMeteoUrl();
        if (url.startsWith("http://") && !configuration.getInfoPanelAllowHttp()) {
            throw new IllegalArgumentException("HTTP weather endpoint is disabled");
        }
        JSONObject source = new JSONObject(readUrl(url));
        if (endpoint.length() > 0) {
            return normalizeCustomWeather(source);
        }
        return normalizeOpenMeteoWeather(source);
    }

    private String openMeteoUrl() throws Exception {
        String latitude = URLEncoder.encode(configuration.getInfoPanelLatitude(), "UTF-8");
        String longitude = URLEncoder.encode(configuration.getInfoPanelLongitude(), "UTF-8");
        String unit = "fahrenheit".equals(configuration.getInfoPanelUnits()) ? "fahrenheit" : "celsius";
        return "https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                + "&longitude=" + longitude
                + "&current_weather=true&temperature_unit=" + unit
                + "&timezone=auto";
    }

    private String readUrl(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            reader.close();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Weather HTTP " + code);
            }
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject normalizeOpenMeteoWeather(JSONObject source) throws JSONException {
        JSONObject current = source.getJSONObject("current_weather");
        JSONObject weather = new JSONObject();
        weather.put("source", "Open-Meteo");
        weather.put("temperature", current.optDouble("temperature"));
        weather.put("unit", "fahrenheit".equals(configuration.getInfoPanelUnits()) ? "F" : "C");
        weather.put("weatherCode", current.optInt("weathercode"));
        weather.put("description", describeWeatherCode(current.optInt("weathercode")));
        weather.put("windSpeed", current.optDouble("windspeed"));
        weather.put("time", current.optString("time"));
        return weather;
    }

    private JSONObject normalizeCustomWeather(JSONObject source) throws JSONException {
        JSONObject weather = new JSONObject();
        weather.put("source", source.optString("source", "Custom"));
        weather.put("temperature", firstDouble(source, "temp", "temperature", "current_temperature", "currentTemp"));
        weather.put("unit", source.optString("unit", "fahrenheit".equals(configuration.getInfoPanelUnits()) ? "F" : "C"));
        weather.put("weatherCode", firstInt(source, "weather_code", "weatherCode", "code"));
        weather.put("description", firstString(source, "description", "text", "summary", "condition"));
        weather.put("windSpeed", firstDouble(source, "wind_speed", "windSpeed", "windspeed"));
        weather.put("time", firstString(source, "updated_at", "updatedAt", "time"));
        return weather;
    }

    private double firstDouble(JSONObject object, String... names) {
        for (String name : names) {
            if (object.has(name)) {
                return object.optDouble(name);
            }
        }
        return 0.0d;
    }

    private int firstInt(JSONObject object, String... names) {
        for (String name : names) {
            if (object.has(name)) {
                return object.optInt(name);
            }
        }
        return 0;
    }

    private String firstString(JSONObject object, String... names) {
        for (String name : names) {
            String value = object.optString(name, "");
            if (value.length() > 0) {
                return value;
            }
        }
        return "";
    }

    private String describeWeatherCode(int code) {
        if (code == 0) return "Clear";
        if (code == 1 || code == 2 || code == 3) return "Partly cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 67) return "Drizzle";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Rain showers";
        if (code >= 95) return "Thunderstorm";
        return "Weather";
    }
}
