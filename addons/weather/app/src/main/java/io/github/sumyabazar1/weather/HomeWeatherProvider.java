package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code content://com.gathin.tvhome.weather.provider/current} for the dashboard home screen: one row with
 * {@code temp_c}, {@code condition}, {@code city}, {@code updated_at} (ms), plus {@code feels_c}, {@code aqi}, {@code lat}, {@code lon} (the place, for the home screen's offline sunrise and sunset) and
 * {@code humidity}. When the last reading is older than {@link Forecast#REFRESH_MS} a query first reads Open-Meteo
 * (the caller asks off its main thread; this TV kills an idle provider process within seconds, so a background
 * fetch would not live to finish). Nothing runs unless the home screen asks: no service, no alarm.
 *
 * The city is the one chosen in the app; before one is chosen, New Delhi.
 */
public final class HomeWeatherProvider extends ContentProvider {
    static final Uri CURRENT = Uri.parse("content://com.gathin.tvhome.weather.provider/current");
    private static final String CACHE = "home_reading";
    private static final String[] COLUMNS = {"temp_c", "condition", "city", "updated_at", "feels_c", "aqi", "humidity", "lat", "lon"};
    private static final double DEFAULT_LAT = 28.6139, DEFAULT_LON = 77.2090;
    private static final String DEFAULT_CITY = "New Delhi";

    private final AtomicBoolean fetching = new AtomicBoolean();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null) return null;
        SharedPreferences cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        SharedPreferences place = context.getSharedPreferences("place", Context.MODE_PRIVATE);
        String city = place.getString("name", DEFAULT_CITY);
        long now = System.currentTimeMillis();
        long at = cache.getLong("at", 0);
        long tried = cache.getLong("tried", 0);
        boolean samePlace = city.equals(cache.getString("city", ""));
        // readings cached before the switch to India's CPCB scale are refetched
        boolean stale = !samePlace || now - at > Forecast.REFRESH_MS || !cache.getBoolean("cpcb", false);
        if (stale && now - tried > Forecast.RETRY_MS / 5) {
            refresh(context, cache, place, city);
            at = cache.getLong("at", 0);
            samePlace = city.equals(cache.getString("city", ""));
        }

        MatrixCursor cursor = new MatrixCursor(COLUMNS, 1);
        if (at > 0 && samePlace) {
            double temp = Double.longBitsToDouble(cache.getLong("temp", 0));
            double feels = Double.longBitsToDouble(cache.getLong("feels", 0));
            int code = cache.getInt("code", 3);
            boolean day = cache.getBoolean("day", true);
            cursor.addRow(new Object[]{
                    String.valueOf(Math.round(temp)),
                    context.getString(Forecast.sky(code, day)),
                    city,
                    at,
                    String.valueOf(Math.round(feels)),
                    cache.getBoolean("cpcb", false) ? cache.getInt("aqi", -1) : -1,
                    cache.getInt("humidity", -1),
                    place.contains("lat") ? Double.longBitsToDouble(place.getLong("lat", 0)) : DEFAULT_LAT,
                    place.contains("lon") ? Double.longBitsToDouble(place.getLong("lon", 0)) : DEFAULT_LON});
        }
        return cursor;
    }

    private void refresh(Context context, SharedPreferences cache, SharedPreferences place, String city) {
        if (!fetching.compareAndSet(false, true)) return;
        cache.edit().putLong("tried", System.currentTimeMillis()).apply();
        double lat = place.contains("lat") ? Double.longBitsToDouble(place.getLong("lat", 0)) : DEFAULT_LAT;
        double lon = place.contains("lon") ? Double.longBitsToDouble(place.getLong("lon", 0)) : DEFAULT_LON;
        {
            try {
                Forecast.Report r = Forecast.fetch(lat, lon, Forecast.MIN_DAYS);
                cache.edit()
                        .putString("city", city)
                        .putLong("at", System.currentTimeMillis())
                        .putLong("temp", Double.doubleToRawLongBits(r.tempC))
                        .putLong("feels", Double.doubleToRawLongBits(r.feelsC))
                        .putInt("code", r.code)
                        .putBoolean("day", r.day)
                        .putInt("aqi", r.aqi)
                        .putBoolean("cpcb", true)
                        .putInt("humidity", r.humidity)
                        .commit();
            } catch (Exception e) {
                android.util.Log.w("HomeWeather", "fetch failed", e);
                // offline: the home screen keeps the last reading until it is three hours old
            } finally {
                fetching.set(false);
            }
        }
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.gathin.weather";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
