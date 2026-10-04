# Homebase privacy policy

Homebase (widgets for Home Assistant) is made by Jyotirmay (Weenja, https://weenja.in).
Last updated: 5 October 2026.

Homebase talks to **your own Home Assistant server only**. There is no analytics, no crash reporting,
no ads, no account and no third-party service. The app never sends any of your data to the developer.

## What stays on your device

* **Connection.** Your Home Assistant address (and an optional second one) and the login token: the
  OAuth refresh token from the normal Home Assistant login, or a long-lived token you paste.
  Android keeps them in the app's private storage; iOS keeps the token in the Keychain.
* **Widget settings.** Which entities, rooms and options each widget shows.
* **A cache** of entity states, forecasts, energy totals, calendar events, sensor history and media
  artwork, so widgets can draw without network. On iOS it lives in the app's App Group, shared only
  with the Homebase widgets.
* **The scan** of your home: the area, floor, device and entity registries, favorites and suggestions
  Home Assistant reports, and your Energy dashboard setup. It is used to suggest and fill widgets.

## Network

* The app connects to the Home Assistant address you gave it, nothing else. It uses the REST API and
  the WebSocket API with your own user; it needs no admin rights.
* Finding Home Assistant uses mDNS / Bonjour on your local network (`_home-assistant._tcp`). Nothing
  leaves the network for this.
* The Overhead widget (an optional Android Extra) opens flightradar24.com in your browser when you
  tap it. The app sends nothing to it.

## Purchases

Homebase Pro is bought through Google Play or the App Store. They handle the payment; the app only
learns whether this account owns Homebase Pro and remembers that on the device. The developer gets
no payment details.

## Backups

* Android: the operating system's backup and device transfer carry **widget settings and defaults
  only**. Tokens, the address, caches and the scan are excluded. *Settings → Back up* writes the same
  settings to a file you choose; restoring it never touches your login.
* iOS: the token is a Keychain item that is never synced to iCloud Keychain; like other Keychain
  items it is only carried by an encrypted device backup. Widget settings are part of the widget
  configuration iOS keeps for you.

## Deleting your data

*Log out* deletes the tokens, the cache and the scan. Uninstalling the app deletes everything.

## Feedback and e-mail

*Send feedback* and *Get in touch* open your own mail app with a message to support@weenja.in. The
feedback message already lists the app version, the operating system version and the phone model;
you can edit or delete that before you send. Nothing is sent unless you send the e-mail yourself, and
the app never sends anything on its own.

## Contact

Questions: support@weenja.in
