#!/usr/bin/env python3
"""
Clean TV setup wizard: asks a few questions and sets up the dashboard Home on an Android TV.

    python3 tools/setup_wizard.py 192.168.1.50        # the TV's IP address (Network debugging on)
    python3 tools/setup_wizard.py --offline out/      # no TV: write the files to out/ to look at

It asks, in order:
  1. your name for the greeting, the city shown with the weather, the clock format
  2. how many apps go in the dock and which ones (suggestions come from what's installed)
  3. holidays: each one ON or OFF, with the dates it starts and ends (and add your own)
  4. wallpapers: picture sets from this repo's themes and/or a folder of your own, how often they change
  5. other options: glass opacity, fade when idle, clock screensaver, Arcade panel, and where the For you
     posters get their ratings (IMDb, TMDB or none)
  6. remote buttons, last: you go through every button at your own pace; any that does nothing (or not
     what you want) you press while the wizard listens, and pick what it should open. Test it straight away.

Needs Python 3.8+ and adb. First it installs the launcher and the add-ons you have built (see the README)
if they aren't on the TV yet, and gives them the permissions they need.
Answers are saved in ~/.clean-tv/<tv>.json, so running it again starts from your last choices.
Standard library only.
"""
import datetime as dt
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

PKG = "com.gathin.tvhome"
FOR_YOU = "com.gathin.tvhome.foryou"
FOR_YOU_FILES = f"/sdcard/Android/data/{FOR_YOU}/files"
FILES = f"/sdcard/Android/data/{PKG}/files"
KEY_SERVICE = f"{PKG}/.InputKeyService"
LEARN_TAG = "TvHomeKeyLearn"
REPO = Path(__file__).resolve().parent.parent
THEMES = REPO / "themes"
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}

# Popular TV apps, most-suggested first. Only the installed ones are offered as suggestions.
KNOWN_APPS = [
    ("com.google.android.youtube.tv", "YouTube"),
    ("com.netflix.ninja", "Netflix"),
    ("com.amazon.amazonvideo.livingroom", "Prime Video"),
    ("in.startv.hotstar", "JioHotstar"),
    ("com.disney.disneyplus", "Disney+"),
    ("com.sonyliv", "SonyLIV"),
    ("com.graymatrix.did", "ZEE5"),
    ("com.jio.media.stb.ondemand", "JioCinema"),
    ("com.google.android.youtube.tvmusic", "YouTube Music"),
    ("com.spotify.tv.android", "Spotify"),
    ("com.wbd.hbomax", "Max"),
    ("com.apple.atve.androidtv.appletv", "Apple TV"),
    ("com.plexapp.android", "Plex"),
    ("org.xbmc.kodi", "Kodi"),
    ("com.mxtech.videoplayer.television", "MX Player"),
    ("tv.twitch.android.app", "Twitch"),
    ("com.google.android.videos", "Google TV"),
    ("com.android.vending", "Play Store"),
    ("com.android.chrome", "Chrome"),
    ("com.android.tv.settings", "TV Settings"),
]
KNOWN = dict(KNOWN_APPS)

# Holidays offered with default dates. Lunar-calendar festivals move every year: check those dates.
HOLIDAYS = [
    # name, greeting, accent, default from, default to, fixed date?
    ("christmas", "Merry Christmas", "#E0463C", "12-23", "12-30", True),
    ("new-year", "Happy New Year", "#FFD166", "12-31", "01-07", True),
    ("pongal-sankranti", "Happy Pongal", "#F2C14E", "01-13", "01-16", True),
    ("republic-day", "Happy Republic Day", "#FF9933", "01-25", "01-27", True),
    ("valentines", "Happy Valentine's Day", "#FF4F79", "02-13", "02-15", True),
    ("holi", "Happy Holi", "#FF4FA3", "2027-03-19", "2027-03-24", False),
    ("eid", "Eid Mubarak", "#3FB68B", "2027-03-08", "2027-03-12", False),
    ("easter", "Happy Easter", "#A8D672", "2027-03-26", "2027-03-29", False),
    ("independence-day", "Happy Independence Day", "#138808", "08-14", "08-16", True),
    ("ganesh-chaturthi", "Ganpati Bappa Morya", "#FF7A00", "2027-09-04", "2027-09-14", False),
    ("dussehra", "Happy Dussehra", "#FF9933", "2027-10-06", "2027-10-11", False),
    ("halloween", "Happy Halloween", "#FF7518", "10-25", "10-31", True),
    ("diwali", "Happy Diwali", "#FFB23C", "2027-10-25", "2027-11-02", False),
]

# What a button can do besides opening an app (RemoteKeys action words).
ACTIONS = [
    ("homesettings", "Clean TV settings (this launcher's Settings screen)"),
    ("settings", "TV system settings"),
    ("assist", "Voice assistant (what Ask on Home opens)"),
    ("home", "Home"),
    ("back", "Back"),
    ("library", "All apps"),
    ("screensaver", "Start the screensaver"),
    ("mute", "Mute"),
    ("sleep", "Sleep (screen off)"),
    ("recents", "Recent apps"),
    ("ignore", "Nothing (swallow the button)"),
]
# Buttons people rarely want to remap: asked again before taking them over.
CORE_KEYS = {"KEYCODE_DPAD_UP", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT",
             "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_BACK", "KEYCODE_HOME", "KEYCODE_VOLUME_UP",
             "KEYCODE_VOLUME_DOWN", "KEYCODE_POWER"}


# ---- console helpers ------------------------------------------------------------------------------------

def say(text=""):
    print(text, flush=True)


def heading(n, total, title):
    say()
    say(f"━━ {n}/{total}  {title} " + "━" * max(3, 60 - len(title)))


def ask(prompt, default=None):
    hint = f" [{default}]" if default not in (None, "") else ""
    try:
        answer = input(f"{prompt}{hint}: ").strip()
    except EOFError:
        say("\nInput ended before the questions did. Nothing more was changed.")
        sys.exit(1)
    return answer if answer else ("" if default is None else str(default))


def ask_yes(prompt, default=True):
    while True:
        a = ask(prompt + (" (Y/n)" if default else " (y/N)")).lower()
        if not a:
            return default
        if a in ("y", "yes", "on"):
            return True
        if a in ("n", "no", "off"):
            return False
        say("  Please answer y or n.")


def ask_int(prompt, default, lo, hi):
    while True:
        a = ask(f"{prompt} ({lo}-{hi})", default)
        try:
            v = int(a)
            if lo <= v <= hi:
                return v
        except ValueError:
            pass
        say(f"  Please type a number from {lo} to {hi}.")


def ask_choice(prompt, options, default):
    """options: list of (value, label). Returns a value."""
    for i, (_, label) in enumerate(options, 1):
        say(f"  {i}. {label}")
    values = [v for v, _ in options]
    d = values.index(default) + 1 if default in values else 1
    return values[ask_int(prompt, d, 1, len(options)) - 1]


def parse_date(text, year_hint):
    """'2026-12-23', '12-23' or '23/12' -> date; month-day forms take year_hint."""
    text = text.strip()
    for fmt in ("%Y-%m-%d", "%d/%m/%Y"):
        try:
            return dt.datetime.strptime(text, fmt).date()
        except ValueError:
            pass
    for fmt in ("%Y-%m-%d", "%Y-%d/%m"):
        try:
            return dt.datetime.strptime(f"{year_hint}-{text}", fmt).date()
        except ValueError:
            pass
    return None


# ---- the TV -----------------------------------------------------------------------------------------------

class TV:
    def __init__(self, host):
        self.serial = host if ":" in host else f"{host}:5555"
        self.adb = shutil.which("adb")
        if not self.adb:
            sys.exit("adb was not found. Install Android platform-tools and try again.")

    def run(self, *args, check=False, timeout=60):
        r = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def sh(self, cmd, timeout=60):
        return self.run("shell", cmd, timeout=timeout).replace("\r", "")

    def connect(self):
        subprocess.run([self.adb, "connect", self.serial], capture_output=True, text=True, timeout=30)
        state = subprocess.run([self.adb, "-s", self.serial, "get-state"], capture_output=True, text=True).stdout.strip()
        if state != "device":
            sys.exit(f"Could not reach {self.serial}. Turn on Network debugging on the TV, accept the "
                     "'Allow debugging?' prompt on screen, and try again.")

    def installed(self, pkg):
        return f"package:{pkg}" in self.sh(f"pm path {pkg}")

    def install(self, apk):
        r = subprocess.run([self.adb, "-s", self.serial, "install", "-r", str(apk)], capture_output=True, text=True,
                           timeout=600)
        out = (r.stdout + r.stderr).strip()
        if "Success" in out:
            return True
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
            say("  The copy on the TV was signed with a different key. Uninstall it first (adb uninstall <package>)")
            say("  and run the wizard again; the launcher's settings on the TV go with it.")
        else:
            say("  Install failed: " + out.splitlines()[-1] if out else "  Install failed.")
        return False

    def model(self):
        return self.sh("getprop ro.product.model").strip()

    def launchable(self):
        out = self.sh("cmd package query-activities --brief -a android.intent.action.MAIN "
                      "-c android.intent.category.LEANBACK_LAUNCHER")
        pkgs = []
        for line in out.splitlines():
            m = re.match(r"\s*([\w.]+)/", line)
            if m and m.group(1) not in pkgs and m.group(1) != PKG:
                pkgs.append(m.group(1))
        return pkgs

    def read_json(self, name):
        text = self.sh(f"cat {FILES}/{name} 2>/dev/null")
        try:
            return json.loads(text)
        except ValueError:
            return None

    def push_dir(self, local_dir):
        """Copy local_dir's contents into the launcher's files folder (adb push can't create folders there)."""
        self.sh("rm -rf /data/local/tmp/cleantv && mkdir -p /data/local/tmp/cleantv")
        self.run("push", f"{local_dir}/.", "/data/local/tmp/cleantv", check=True, timeout=1800)
        ok = self.sh(f"mkdir -p {FILES} && cp -R /data/local/tmp/cleantv/. {FILES}/ && rm -rf /data/local/tmp/cleantv && echo ok")
        if "ok" not in ok:
            raise RuntimeError("copying the files into the launcher's folder failed")

    def push_text(self, folder, name, text):
        """Write a small text file into another app's files folder."""
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write(text)
        try:
            self.run("push", f.name, f"/data/local/tmp/{name}", check=True)
            self.sh(f"mkdir -p {folder} && cp /data/local/tmp/{name} {folder}/{name} && rm /data/local/tmp/{name}")
        finally:
            os.unlink(f.name)

    def rebind_keys(self):
        """(Re)start the key helper so it reads config.json and the key-learn flag again."""
        current = self.sh("settings get secure enabled_accessibility_services").strip()
        others = [s for s in current.split(":") if s and s != "null" and s != KEY_SERVICE]
        self.sh("settings put secure enabled_accessibility_services " + (":".join(others) if others else "''"))
        time.sleep(1)
        self.sh("settings put secure enabled_accessibility_services " + ":".join(others + [KEY_SERVICE]))
        self.sh("settings put secure accessibility_enabled 1")
        time.sleep(1)

    def restart_home(self):
        self.sh(f"am force-stop {PKG}")
        self.sh(f"cmd package set-home-activity --user 0 {PKG}/.MainActivity")
        self.sh(f"am start -n {PKG}/.MainActivity")


# ---- installing ---------------------------------------------------------------------------------------

# package, name, built APK (see the README for building), what it is for
APPS = [
    (PKG, "Clean TV launcher", REPO / "launcher/app/build/outputs/apk/release/app-release.apk", "the Home screen"),
    ("com.gathin.tvhome.weather", "Weather add-on", REPO / "addons/weather/app/build/outputs/apk/release/app-release.apk",
     "weather and air quality on Home"),
    (FOR_YOU, "For you add-on", REPO / "addons/for-you/app/build/outputs/apk/release/app-release.apk",
     "the For you posters: what you were watching, with ratings"),
]


def install_apps(tv):
    for pkg, name, apk, what in APPS:
        have = tv.installed(pkg)
        if not apk.is_file():
            if pkg == PKG and not have:
                sys.exit("The Clean TV launcher isn't on this TV and isn't built yet. Build it first "
                         "(cd launcher && ./gradlew assembleRelease), then run the wizard again.")
            if not have:
                say(f"  {name} ({what}): not built, skipped. Build it in {apk.parents[5].relative_to(REPO)} to add it.")
            continue
        if have:
            if ask_yes(f"  {name} is installed. Update it from the one built on this computer?", False):
                say("    Updated." if tv.install(apk) else "    Kept the installed one.")
        elif ask_yes(f"  Install the {name} ({what})?", True):
            if not tv.install(apk) and pkg == PKG:
                sys.exit("The launcher could not be installed, so there is nothing to set up yet.")
    # what the apps need to work, granted over adb (the TV has no screen for these)
    for cmd in (f"pm grant {PKG} android.permission.WRITE_SECURE_SETTINGS",
                f"pm grant {PKG} android.permission.ACCESS_FINE_LOCATION",
                f"cmd appops set {PKG} AUTO_START allow",
                f"pm grant {FOR_YOU} android.permission.READ_TV_LISTINGS",
                f"cmd notification allow_listener {FOR_YOU}/.NowPlayingListener",
                f"cmd package set-home-activity --user 0 {PKG}/.MainActivity"):
        tv.sh(cmd + " 2>/dev/null")


# ---- the questions ------------------------------------------------------------------------------------

def app_label(pkg):
    return KNOWN.get(pkg, pkg)


def basics(prev, cfg):
    name = ask("Your name for the greeting (e.g. Good evening, Asha); leave empty for none",
               prev.get("greeting_name", cfg.get("greeting_name", "")))
    city = ask("City shown with the weather", prev.get("weather_city", cfg.get("weather_city", "Delhi NCR")))
    say("Clock format:")
    clock = ask_choice("Choose", [("12", "12-hour (5:30 pm)"), ("24", "24-hour (17:30)"),
                                  ("system", "Follow the TV's setting")], prev.get("clock_format", "12"))
    return {"greeting_name": name, "weather_city": city, "clock_format": clock}


def dock(prev, cfg, installed):
    say("The dock is the row of big app tiles at the bottom of Home.")
    current = [p for p in prev.get("dock_apps", cfg.get("dock_apps", [])) if p in installed]
    count = ask_int("How many apps in the dock", len(current) if 3 <= len(current) <= 7 else 5, 3, 7)
    suggested = [p for p, _ in KNOWN_APPS if p in installed]
    rest = sorted((p for p in installed if p not in suggested), key=app_label)
    menu = suggested + rest
    say("Installed apps (suggestions first):")
    for i, p in enumerate(menu, 1):
        star = " *" if p in suggested[:count] else ""
        say(f"  {i:2}. {app_label(p)}{star}" + (f"  ({p})" if p in KNOWN else ""))
    default = current[:count] if len(current) >= count else (current + [p for p in suggested if p not in current])[:count]
    default_nums = ",".join(str(menu.index(p) + 1) for p in default if p in menu)
    while True:
        picked = ask(f"Type {count} numbers in the order you want them, left to right", default_nums)
        try:
            nums = [int(x) for x in re.split(r"[ ,]+", picked) if x]
        except ValueError:
            nums = []
        if len(nums) == count and all(1 <= n <= len(menu) for n in nums) and len(set(nums)) == count:
            apps = [menu[n - 1] for n in nums]
            say("  Dock: " + "  |  ".join(app_label(p) for p in apps))
            return {"dock_apps": apps}
        say(f"  Please type exactly {count} different numbers from the list, e.g. 1,2,3.")


def holidays(prev):
    today = dt.date.today()
    saved = {h["name"]: h for h in prev.get("holidays", [])}
    say("Each holiday changes the greeting, the accent colour and (if it has pictures) the wallpapers")
    say("between its ON and OFF dates. Dates are YYYY-MM-DD, or MM-DD for the next one.")
    say("Festivals that follow the moon move every year: check those dates (marked ~).")
    result = []
    entries = [(n, g, a, f, t, fixed) for n, g, a, f, t, fixed in HOLIDAYS]
    entries += [(h["name"], h["greeting"], h.get("accent", "#FFD166"), h["from"], h["to"], False)
                for h in prev.get("holidays", []) if h["name"] not in {e[0] for e in HOLIDAYS}]
    for name, greeting, accent, d_from, d_to, fixed in entries:
        s = saved.get(name)
        on_default = s["on"] if s else name in ("christmas", "new-year", "holi", "dussehra", "diwali")
        start = parse_date(s["from"] if s else d_from, today.year)
        end = parse_date(s["to"] if s else d_to, today.year)
        if fixed and not s:
            if end < start:   # runs over New Year's Eve
                end = end.replace(year=end.year + 1)
            while end < today:   # this year's has passed: next year's
                start, end = start.replace(year=start.year + 1), end.replace(year=end.year + 1)
        mark = "" if fixed else " ~"
        label = name.replace("-", " ").title()
        on = ask_yes(f"\n{label}{mark}: {start:%d %b %Y} to {end:%d %b %Y}. ON?", on_default)
        if on:
            start, end = holiday_dates(label, start, end, today)
            greeting = ask("  Greeting", s["greeting"] if s else greeting)
        result.append({"name": name, "on": on, "from": start.isoformat(), "to": end.isoformat(),
                       "greeting": greeting, "accent": accent})
    while ask_yes("\nAdd a holiday of your own (a birthday, an anniversary...)?", False):
        label = ask("  Name (e.g. Asha's birthday)")
        if not label:
            continue
        name = re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-") or "holiday"
        start = None
        while not start:
            start = parse_date(ask("  ON date (YYYY-MM-DD)"), today.year)
            if not start:
                say("  Please type a date like 2027-05-14.")
        end = None
        while not end or end < start:
            end = parse_date(ask("  OFF date", start.isoformat()), start.year)
            if not end or end < start:
                say("  The OFF date must be the same as or after the ON date.")
        greeting = ask("  Greeting", f"Happy {label}")
        result.append({"name": name, "on": True, "from": start.isoformat(), "to": end.isoformat(),
                       "greeting": greeting, "accent": "#FFD166"})
    return {"holidays": result}


def holiday_dates(label, start, end, today):
    if not ask_yes("  Keep these dates?", True):
        while True:
            s = parse_date(ask("  ON date", start.isoformat()), start.year)
            e = parse_date(ask("  OFF date", end.isoformat()), (s or start).year)
            if s and e and e >= s:
                start, end = s, e
                break
            say("  The OFF date must be the same as or after the ON date.")
    if end < today:
        say(f"  Note: {label} has already ended for these dates; it will start again only with new dates.")
    return start, end


def picture_sets():
    """Theme folders in this repo that carry pictures: name -> list of image files."""
    sets = {}
    for d in sorted(THEMES.iterdir()) if THEMES.is_dir() else []:
        pics = []
        if (d / "wallpapers").is_dir():
            pics += sorted(p for p in (d / "wallpapers").iterdir() if p.suffix.lower() in IMAGE_EXT)
        if (d / "wallpaper.jpg").is_file():
            pics.append(d / "wallpaper.jpg")
        if pics:
            sets[d.name] = pics
    return sets


def folder_pictures(path):
    path = path.strip().strip("'\"")
    p = Path(os.path.expanduser(path))
    if not path or not p.is_dir():
        return None
    return sorted(f for f in p.iterdir() if f.suffix.lower() in IMAGE_EXT)


def wallpapers(prev, holiday_answers):
    sets = picture_sets()
    say("Everyday wallpapers show whenever no holiday is ON. Pick one or more picture sets, a folder of")
    say("your own pictures (1920x1080 JPGs look best), or keep what is on the TV now.")
    names = list(sets)
    for i, n in enumerate(names, 1):
        say(f"  {i:2}. {n} ({len(sets[n])} pictures)")
    say(f"  {len(names) + 1:2}. A folder of my own")
    say(f"  {len(names) + 2:2}. Keep what is on the TV now")
    keep_n = len(names) + 2
    default = prev.get("wallpaper_pick", str(keep_n))
    everyday, chosen, folder = [], [], prev.get("wallpaper_folder", "")
    while True:
        picked = ask("Numbers, comma separated", default)
        try:
            nums = [int(x) for x in re.split(r"[ ,]+", picked) if x]
        except ValueError:
            nums = []
        if nums and all(1 <= n <= keep_n for n in nums):
            break
        say("  Please type numbers from the list.")
    keep = keep_n in nums
    for n in nums:
        if n <= len(names):
            everyday += sets[names[n - 1]]
            chosen.append(names[n - 1])
        elif n == len(names) + 1:
            while True:
                folder = ask("  Folder path", folder)
                pics = folder_pictures(folder)
                if pics:
                    everyday += pics
                    chosen.append("own folder")
                    break
                say("  No pictures found there (jpg, png or webp). Try again.")
    if everyday:
        say(f"  {len(everyday)} everyday pictures from: {', '.join(chosen)}")
    minutes = ask_int("Change the wallpaper every how many minutes (0 = never)",
                      prev.get("wallpaper_rotation_minutes", 10), 0, 1440)
    say("Order:")
    order = ask_choice("Choose", [("sequential", "In order"), ("shuffle", "Shuffled")],
                       prev.get("wallpaper_order", "sequential"))
    per_holiday = {}
    on = [h for h in holiday_answers["holidays"] if h["on"]]
    if on and ask_yes("Give any holiday its own pictures? (Otherwise it keeps the everyday ones)", False):
        for h in on:
            built_in = sets.get(h["name"])
            hint = f" (Enter = this repo's {h['name']} theme, {len(built_in)} pictures)" if built_in else ""
            path = ask(f"  Folder for {h['name']}{hint}; '-' to skip", "" if built_in else "-")
            if path == "" and built_in:
                per_holiday[h["name"]] = built_in
            elif path != "-":
                pics = folder_pictures(path)
                if pics:
                    per_holiday[h["name"]] = pics
                else:
                    say("    No pictures there; skipped.")
    return {"wallpaper_pick": picked, "wallpaper_folder": folder, "wallpaper_rotation_minutes": minutes, "wallpaper_order": order,
            "_everyday": [str(p) for p in everyday], "_keep": keep or not everyday,
            "_holiday_pictures": {k: [str(p) for p in v] for k, v in per_holiday.items()}}


def other_options(prev):
    say("Glass opacity: how see-through the panels are (100 = frosted, 30 = mostly wallpaper).")
    opacity = ask_choice("Choose", [(100, "100%"), (70, "70%"), (50, "50%"), (30, "30%")],
                         prev.get("dock_opacity", 100))
    fade = ask_yes("Fade Home away after 20 seconds without a button press, so the wallpaper shows?",
                   prev.get("fade_when_idle", True))
    clock = ask_int("Show the big clock screensaver after how many idle minutes on Home (0 = off)",
                    prev.get("home_idle_clock_minutes", 2), 0, 1440)
    arcade = ask_yes("Show the Arcade panel (games) on Home?", prev.get("arcade_tile", True))
    greet = ask_yes("Show the greeting (Good evening...) on Home?", prev.get("show_greeting", True))
    out = {"dock_opacity": opacity, "fade_when_idle": fade, "home_idle_clock_minutes": clock,
           "arcade_tile": arcade, "show_greeting": greet}
    out.update(ratings(prev))
    return out


def ratings(prev):
    say()
    say("For you shows what you were watching, as posters with a rating badge (needs the For you add-on).")
    say("Where should the ratings and posters come from?")
    source = ask_choice("Choose", [
        ("imdb", "IMDb (works everywhere, nothing to sign up for)"),
        ("tmdb", "TMDB (The Movie Database; needs your own free API key)"),
        ("off", "No ratings (the apps' own pictures, no lookups)"),
    ], prev.get("ratings", "imdb"))
    key = prev.get("tmdb_key", "")
    if source == "tmdb":
        say("  Warning: some Indian internet providers (Jio among them) block TMDB. If yours does, no ratings")
        say("  or posters will show; run the wizard again and pick IMDb.")
        say("  Get a key at themoviedb.org: sign up, then Settings > API. The 'API Key' or the")
        say("  'API Read Access Token' both work.")
        while True:
            key = ask("  TMDB key", key)
            if re.fullmatch(r"[A-Za-z0-9._-]{20,400}", key or ""):
                break
            say("  That doesn't look like a TMDB key (20 or more letters and numbers).")
        if not ask_yes("  Use TMDB even if it may be blocked?", True):
            source = "imdb"
    return {"ratings": source, "tmdb_key": key if source == "tmdb" else ""}


def for_you_json(answers):
    out = {"ratings": answers.get("ratings", "imdb")}
    if out["ratings"] == "tmdb":
        out["tmdb_key"] = answers.get("tmdb_key", "")
    return out


# ---- remote buttons -------------------------------------------------------------------------------------

class KeyListener:
    """Follows the launcher's key-learn log lines on the TV."""

    def __init__(self, tv):
        tv.run("logcat", "-c")
        self.proc = subprocess.Popen([tv.adb, "-s", tv.serial, "logcat", "-v", "brief", "-s", f"{LEARN_TAG}:I"],
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.q = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            m = re.search(r"keycode=(-?\d+) name=(\S+) scancode=(-?\d+)", line)
            if m:
                self.q.put((int(m.group(1)), m.group(2), int(m.group(3))))

    def drain(self):
        while not self.q.empty():
            self.q.get_nowait()

    def next_key(self, seconds):
        try:
            return self.q.get(timeout=seconds)
        except queue.Empty:
            return None

    def stop(self):
        self.proc.terminate()


def describe_target(target):
    for word, label in ACTIONS:
        if target == word:
            return label
    return app_label(target)


def describe_entry(entry):
    """'hold:scan:187:netflix.pkg' -> 'Open Netflix (held)'."""
    rest, hold = (entry[5:], True) if entry.startswith("hold:") else (entry, False)
    if rest.startswith("scan:"):
        rest = rest[5:]
    target = rest.split(":", 1)[1] if ":" in rest else rest
    text = describe_target(target)
    if target not in dict(ACTIONS) and not target.startswith("input:"):
        text = "Open " + text
    return text + (" (held)" if hold else "")


def pick_target(installed, dock_apps):
    options = [(w, label) for w, label in ACTIONS]
    apps = dock_apps + [p for p, _ in KNOWN_APPS if p in installed and p not in dock_apps]
    apps += sorted((p for p in installed if p not in apps), key=app_label)
    options += [(p, f"Open {app_label(p)}") for p in apps]
    return ask_choice("What should this button do", options, options[0][0])


def remote_buttons(tv, prev, installed, dock_apps, apply):
    entries = list(prev.get("app_keys", []))
    say("Now the remote. Take your time: press every button on your remote and check it does what you")
    say("expect. Coloured buttons and app buttons (Netflix, YouTube, Settings...) often stop working once")
    say("the TV's own home screen is switched off. Come back here when you find one to fix.")
    tv.sh(f"touch {FILES}/key-learn")
    tv.rebind_keys()
    listener = KeyListener(tv)
    try:
        while True:
            say()
            if entries:
                say("Buttons set up so far:")
                for i, e in enumerate(entries, 1):
                    say(f"  {i}. {e}  ->  {describe_entry(e)}")
            say("  m = map a button    r = remove a mapping    d = done")
            choice = ask("Choice", "m" if not entries else "d").lower()
            if choice == "d":
                break
            if choice == "r" and entries:
                n = ask_int("Remove which", 1, 1, len(entries))
                entries.pop(n - 1)
                apply(entries)
                continue
            if choice != "m":
                continue
            listener.drain()
            say("Press the button on the remote now, once. (Waiting up to 2 minutes.)")
            key = listener.next_key(120)
            if not key:
                say("Nothing reached the TV's apps. Either that button doesn't send anything the TV receives")
                say("(point the remote straight at the TV and try again), or the TV handles it before any app")
                say("can see it (Power often is). Such a button can't be remapped.")
                continue
            time.sleep(0.6)
            listener.drain()   # extra presses / repeats
            code, name, scan = key
            say(f"Got it: {name} (keycode {code}, scancode {scan}).")
            if name in CORE_KEYS and not ask_yes("That's a button you use to move around. Really remap it?", False):
                continue
            target = pick_target(installed, dock_apps)
            hold = ask_yes("Only when the button is held down (a long press)?", False)
            key_part = str(code) if code > 0 else f"scan:{scan}"   # buttons Android has no name for: by scancode
            entry = ("hold:" if hold else "") + f"{key_part}:{target}"
            entries = [e for e in entries if not e.startswith(("hold:" if hold else "") + key_part + ":")]
            entries.append(entry)
            apply(entries)
            say(f"Saved. Press that button now to try it ({describe_target(target)}), then come back here.")
    finally:
        listener.stop()
        tv.sh(f"rm -f {FILES}/key-learn")
    return {"app_keys": entries}


# ---- writing it all out --------------------------------------------------------------------------------

def seasons_json(holiday_list):
    out = []
    for h in holiday_list:
        if h["on"]:
            out.append({"name": h["name"], "from": h["from"], "to": h["to"], "collection": h["name"],
                        "greeting": h["greeting"], "accent": h["accent"], "rotation_minutes": 10,
                        "order": "sequential"})
    return out


def build_config(base, answers):
    cfg = dict(base)
    cfg.setdefault("home_layout", "dashboard")
    for k in ("greeting_name", "weather_city", "clock_format", "dock_apps", "wallpaper_rotation_minutes",
              "wallpaper_order", "dock_opacity", "fade_when_idle", "home_idle_clock_minutes", "arcade_tile",
              "show_greeting", "app_keys"):
        if k in answers:
            cfg[k] = answers[k]
    cfg["wallpaper"] = True
    cfg["restore_key_services"] = True
    return cfg


def stage(out, answers, cfg, with_pictures=True):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "config.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "seasons.json").write_text(json.dumps(seasons_json(answers.get("holidays", [])), ensure_ascii=False,
                                                 indent=2), encoding="utf-8")
    if not with_pictures:
        return
    groups = {}
    if answers.get("_everyday"):
        groups["everyday"] = answers["_everyday"]
    groups.update(answers.get("_holiday_pictures", {}))
    for coll, pics in groups.items():
        d = out / "collections" / coll
        d.mkdir(parents=True, exist_ok=True)
        for i, src in enumerate(pics, 1):
            src = Path(src)
            shutil.copy2(src, d / f"{i:03d}_{re.sub(r'[^A-Za-z0-9._-]+', '_', src.name)}")


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        say(__doc__)
        return 2
    offline = args[0] == "--offline"
    if offline and len(args) < 2:
        sys.exit("usage: setup_wizard.py --offline <folder>")
    store = Path.home() / ".clean-tv"
    store.mkdir(exist_ok=True)

    tv = None
    if offline:
        key = "offline"
        installed = [p for p, _ in KNOWN_APPS]
        base = json.loads((THEMES / "dashboard" / "config.json").read_text())
    else:
        tv = TV(args[0])
        tv.connect()
        key = tv.serial.replace(":", "_")
        say(f"Connected to {tv.model()} ({tv.serial}).")
        say(); say("━━ Apps on the TV " + "━" * 48)
        install_apps(tv)
        installed = tv.launchable()
        on_tv = tv.read_json("config.json")
        base = on_tv or json.loads((THEMES / "dashboard" / "config.json").read_text())
    saved_file = store / f"{key}.json"
    prev = json.loads(saved_file.read_text()) if saved_file.is_file() else {}
    if prev:
        say(f"Starting from your last answers ({saved_file}). Press Enter to keep any of them.")

    total = 5 if offline else 6
    answers = {}
    heading(1, total, "You and the clock")
    answers.update(basics(prev, base))
    heading(2, total, "The dock")
    answers.update(dock(prev, base, installed))
    heading(3, total, "Holidays")
    answers.update(holidays(prev))
    heading(4, total, "Wallpapers")
    answers.update(wallpapers(prev, answers))
    heading(5, total, "Other options")
    answers.update(other_options(prev))
    if "app_keys" in base:
        answers["app_keys"] = prev.get("app_keys", base["app_keys"])

    def remember():
        saved_file.write_text(json.dumps({k: v for k, v in answers.items() if not k.startswith("_")},
                                         ensure_ascii=False, indent=2))

    remember()
    if offline:
        stage(args[1], answers, build_config(base, answers))
        (Path(args[1]) / "for-you.json").write_text(json.dumps(for_you_json(answers), indent=2))
        say(f"\nWrote the files to {args[1]} (config.json, seasons.json, collections/, and for-you.json for the")
        say(f"For you add-on, which goes in {FOR_YOU_FILES}/).")
        return 0

    say("\nSending everything to the TV (pictures can take a minute)...")
    with tempfile.TemporaryDirectory() as tmp:
        stage(tmp, answers, build_config(base, answers))
        if not on_tv:   # a fresh TV: the dashboard theme's other files too
            for extra in ("recommendations.json",):
                if (THEMES / "dashboard" / extra).is_file():
                    shutil.copy2(THEMES / "dashboard" / extra, Path(tmp) / extra)
        if answers.get("_everyday"):
            tv.sh(f"rm -rf {FILES}/collections/everyday")
        for coll in answers.get("_holiday_pictures", {}):
            tv.sh(f"rm -rf {FILES}/collections/{coll}")
        tv.push_dir(tmp)
    if f"package:{FOR_YOU}" in tv.sh(f"pm path {FOR_YOU}"):
        tv.push_text(FOR_YOU_FILES, "for-you.json", json.dumps(for_you_json(answers)))
        tv.sh(f"content query --uri 'content://{FOR_YOU}.provider/titles?refresh=1' > /dev/null 2>&1")
    elif answers.get("ratings") != "off":
        say("Note: the For you add-on isn't installed, so the ratings choice is saved for when it is.")
    tv.restart_home()
    tv.rebind_keys()
    say("Done: Home has restarted with your choices.")

    heading(6, total, "Remote buttons")

    def apply(entries):
        answers["app_keys"] = entries
        remember()
        with tempfile.TemporaryDirectory() as tmp:
            stage(tmp, answers, build_config(tv.read_json("config.json") or base, answers), with_pictures=False)
            tv.push_dir(tmp)
        tv.rebind_keys()

    answers.update(remote_buttons(tv, prev, installed, answers["dock_apps"], apply))
    apply(answers["app_keys"])
    say()
    say("All set. A few things to know:")
    say("  - Choices made in Home's own Settings screen win over these until you pick 'Follow theme' there.")
    say(f"  - Run this again any time: python3 tools/setup_wizard.py {args[0]}")
    say("  - Your answers are saved in " + str(saved_file))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        say("\nStopped. Nothing more was changed.")
        sys.exit(130)
