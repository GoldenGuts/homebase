# Store listing

Copy-paste text for Google Play and the App Store. Keep "Home Assistant" as a description of what
the app works with, not as its name.

## Name

* Play (30 characters max): `Homebase: Home widgets`
* App Store (30 max): `Homebase – Home widgets`
* App Store subtitle (30 max): `Widgets for Home Assistant`

## Short description (Play, 80 max)

```
Home Assistant widgets that set themselves up: rooms, favorites, energy, doors.
```

## Promotional text (App Store, 170 max)

```
Connect once. Homebase reads your Home Assistant rooms, favorites and energy dashboard and builds home-screen widgets from them, with buttons that work in place.
```

## Description (Play 4000 max, App Store 4000 max)

```
Homebase puts your Home Assistant on your home screen, and sets it up for you.

CONNECT ONCE
Homebase finds Home Assistant on your Wi-Fi. Log in on the normal Home Assistant page (or paste a long-lived token) and it scans your home: areas, floors, devices, your favorites, your Energy dashboard, doors and locks.

WIDGETS BUILT FROM YOUR HOME
"For your home" suggests a widget per room, your favorites, what you usually switch at this time of day, energy, security, weather and more, each with a live preview. Add one with a tap. Widgets added straight from the widget list fill themselves.

THE WIDGETS
• Favorites and Suggested: tiles that toggle, open, lock and run
• Room: temperature, humidity, a thermostat with − / +, the room's lights and devices
• Energy: live grid, solar and battery power, today's kWh and how self-powered today was
• Security: "All secure", or exactly which door, window or lock is open
• Weather with hourly and daily forecasts, from any weather integration
• Now playing with artwork and controls
• Sensor graph, My devices, Who's home, Camera, Robot vacuum, Blinds & garage, Timers, Calendar, Batteries & offline, Bin day, Car charging, To-do, Scenes, TV remote (Android)
• iOS: a full-page Home dashboard, Lock Screen tile and a Control Center toggle

FREE TO TRY, ONE PRICE FOR EVERYTHING
Favorites, Tile and Weather (small) are free, and the demo home shows every widget. Homebase Pro unlocks every widget and size with one purchase: no subscription.

MADE FOR EVERY DAY
• Every widget shows how fresh its data is; tap to refresh
• Pick entities from a searchable list grouped by floor, room and device, with their live state
• Each widget has its own settings
• Unlocking a door or opening the garage asks for a second tap
• One dark design that fits any wallpaper

PRIVATE BY DESIGN
No account, no cloud, no analytics, no ads. Homebase talks to your own Home Assistant server only, and it never needs an admin user.

Requires a Home Assistant server; favorites and suggestions need a recent release. Try the built-in demo home without one. Questions or a custom setup: support@weenja.in. Homebase is an independent app and is not affiliated with the Home Assistant project or the Open Home Foundation.
```

## Keywords (App Store, 100 max)

```
home assistant,smart home,widget,dashboard,lights,energy,solar,thermostat,lock,automation,homelab
```

## What's new (3.0.0)

```
Homebase now sets itself up: it finds Home Assistant on your network, scans your rooms, favorites and energy dashboard, and suggests widgets built from them. Per-widget settings, a searchable entity picker, 15 new widgets (Favorites, Room, Energy, Security, Sensor graph and more), freshness on every widget, and backup of your widget settings.
```

## Categories

* Play: House & home. Tags: Smart home, Home automation.
* App Store: Utilities (primary), Lifestyle (secondary).

## Graphics

* Icon: `store/icon_512.png` (Play), `store/icon_1024.png` (App Store, also in the app).
* Feature graphic (Play, 1024 × 500): `store/feature_graphic.png`.
* Screenshots, real captures in a phone frame (`tools/frame_shots.py`), demo Home Assistant data:
  * Play, phone: `store/screenshots/android/` (5 × 1080 × 1920). Upload these first.
  * App Store, iPhone 6.9": `store/screenshots/ios-iphone-6.9/` (3 × 1320 × 2868).
  * App Store, iPad 13": `store/screenshots/ios-ipad-13/` (3 × 2064 × 2752). Needed because the
    app runs on iPad (`TARGETED_DEVICE_FAMILY` 1,2).
  * Extra widget renders: `store/screenshots/renders/` (5 × 1080 × 1920). Play takes up to 8
    phone screenshots, so add the best 3 of these after the real ones.
