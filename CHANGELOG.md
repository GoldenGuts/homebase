# Changelog

## 3.0.0 · 2026-09-28

Homebase now sets itself up, and there is an iOS version.

* **Samsung cover screen.** Every widget can be added to the Flex Window on Galaxy Z Flip phones.

* **Auto-setup.** Home Assistant servers on your Wi-Fi are found (mDNS); log in and Homebase scans
  your areas, floors, devices, favorites, energy dashboard and security devices, then suggests
  widgets built from them under **For your home**, each with a live preview and one-tap *Add to
  home screen*. Widgets added from the launcher fill themselves (favorites, then what Home Assistant
  suggests for the time of day, then room rules). Your dashboards can be imported as suggestions.
* **Per-widget settings.** Every widget has its own entities; long-press → *Reconfigure*. The old
  global entity list is now the fallback default.
* **Entity picker** instead of text fields: filtered by what fits, grouped floor → area → device,
  with the live state ("Kitchen ceiling · on · 60%") and search.
* **New widgets:** Favorites, Suggested, Room, Energy, Security, Sensor graph, Who's home, Camera,
  Vacuum, Blinds & garage, Timers, Calendar, Batteries & offline, Bin day, Car charging.
* **My devices** takes any number of lights, switches, fans, covers, locks and input booleans; the
  widget size decides how many show.
* **Weather** works with any weather integration (`weather.get_forecasts`), no template sensor.
* **Freshness.** Every widget says how old its data is; tap to refresh. Refresh keeps going in
  battery saver and after a reboot. Missing entities show "1 entity not found · tap to fix".
* **Backup.** Widget settings follow Android backup and device transfer (never the login), and can
  be saved to a file.
* **Extras.** Mac, Claude Code, gaming PC, money, fuel, screen time and planes overhead are hidden on
  new installs; *Show Extras* lists them with a guide and a copy-paste YAML package each.
* **iOS:** Home (a full-page dashboard), Favorites, Suggested, Room, Energy, Security, Weather, Now
  playing, Sensor graph and a Tile that fits the Lock Screen; buttons work in place; a Control
  Center toggle on iOS 18. A demo home for trying it without Home Assistant.
* **Homebase Pro.** Favorites, Tile and Weather · small are free; one purchase through Google Play
  or the App Store unlocks every widget, for good. The demo home shows everything.
* **Onboarding** in steps: welcome, connect, and a live checklist of what the scan found.
* Want something custom? The app says where to ask (Settings, the bottom of the home screen).
* *Send feedback* (Settings, the bottom of the home screen) opens an e-mail with three short
  questions: what you like, what is missing, what does not work.
* Unlocking a door or opening the garage from a widget asks for a second tap.
* Targets Android 16 (API 36); still runs on Android 12 and later.

## 2.0.0 · 2026-09-21

First public release.

* **Configurable entities.** Every entity id, script name and dashboard path lives in the app under
  *Entities*, grouped per widget. Empty fields keep the defaults; unknown ids are flagged.
* **First-run setup.** No hard-coded server: enter your Home Assistant URL, log in with the normal
  Home Assistant login page, done. Long-lived tokens still work.
* **Widgets fill their height.** Tiles, rings and rows grow with the widget; chip rows sit at the bottom.
  Very short sizes get one-line layouts instead of clipped ones. Small widgets refuse tile sizes.
* **Weather** shows more forecast days and up to three hourly rows when tall.
* **Mac** uses a 3 x 2 app grid when tall, a 6-in-a-row strip when short.
* Wake-on-LAN targets, place name and currency symbol are settings, not code.
* Release build is minified (4 MB instead of 11 MB) and can be signed with your own key.

## 1.5

Internal builds.
