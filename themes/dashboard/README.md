# Dashboard theme

`home_layout: "dashboard"` turns Home into frosted panels over the wallpaper: clock and weather, For you,
a swipe rater, Arcade (installed games), Jarvis (the TV's assistant) and the dock. Classic stays the default.

Files under the launcher's `files/` directory (push with tools/theme.sh or the ThemeProvider):

| File | What it is |
|---|---|
| `config.json` | this folder's config; `home_layout`, `weather_city`, `dock_apps` |
| `seasons.json` | date windows that pick a wallpaper collection, greeting and accent (India time) |
| `collections/<name>/` | pictures for `everyday`, `christmas`, `new-year`, `holi`, `dussehra`, `diwali` |
| `collections.json` | tags existing pictures with more collections, so one file shows in several seasons: `{"wallpapers/01_ayodhya_twilight.jpg": ["everyday", "diwali", "dussehra"]}` |
| `recommendations.json` | `[{"title", "package", "poster", "rating"?, "deeplink"?}]`; `poster` is a file in `posters/` |
| `ratings.json` | written by the rater (Left skip, Right like, Down hide) |

Without a `collections/` folder or `collections.json`, Home rotates `wallpapers/` exactly as before.

Test a season without changing the TV's clock (lasts 24 hours, Home shows a TEST DATE badge):

    adb shell content call --uri content://com.gathin.tvhome.theme --method setTestDate --arg 2026-12-23
    adb shell content call --uri content://com.gathin.tvhome.theme --method setTestDate --arg clear

Weather comes from an optional companion app exposing `content://com.gathin.tvhome.weather.provider/current`
with columns `temp_c`, `condition`, `city`, `updated_at`; anything older than three hours shows "Weather offline".
