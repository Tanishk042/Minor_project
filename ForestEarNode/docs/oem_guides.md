# OEM background-protection guides

> Generated from `android/ForestEarNode/app/src/main/assets/config/oem_profiles.json` by `android/tools/generate_oem_docs.py`. Do not edit by hand: edit the JSON asset and re-run the generator.

Table version: `1` (updated `2026-11-15T00:00:00Z`). Generated: `2026-11-15T17:17:13Z`.

Android has no public API for most maker-specific kill switches, so the app cannot verify them. It shows the plain-language steps below, resolves each deep link before launching it, and records only what you explicitly confirm. The OS-verifiable checks are the battery-optimisation exemption and the overlay permission.

Use **Setup → step 4** in the app; the same table drives that screen. Reference site for current per-maker behaviour: <https://dontkillmyapp.com/>.

If every deep link fails on your build, the app falls back to the app-details screen and the standard Android battery-optimisation screen. Follow the plain-language steps manually; they are the source of truth.

---

## OnePlus / Oppo / Realme (ColorOS, OxygenOS, realme UI)

- Profile id: `oneplus`
- Matches: oneplus, oppo, realme
- Reference: <https://dontkillmyapp.com/oneplus>

ColorOS/OxygenOS/realme UI freeze background apps aggressively. Battery optimisation exemption alone is not enough; auto-launch and background activity are separate switches. The OnePlus Nord CE 2 Lite 5G is a known device in this family.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → Auto-launch (or App launch / Startup manager).
2. Find ForestEar Node and enable it.
3. If the list is hidden, use the search icon and type ForestEar.

**Battery optimisation**

1. Open Settings → Battery → Battery optimisation.
2. Switch to the All apps list, find ForestEar Node, choose Don't optimise / Unrestricted.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery.
2. Enable Allow background activity.
3. Also disable Sleep standby optimisation if the phone offers it.

**Lock in Recents**

1. Open Recents, long-press the ForestEar Node card.
2. Tap the lock icon so the card cannot be swiped away.

**Display over other apps (optional)**

1. Open Settings → Apps → Special app access → Display over other apps.
2. Allow ForestEar Node. This is optional and only helps bring the app back after a kill on some builds.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Battery health / Charging limit.
2. If an 80–85% cap exists, consider enabling it for a permanently powered node.
3. A capped battery still reports 100% of its usable range; do not treat the cap as a fault.

**Battery swelling inspection**

1. Inspect the battery for swelling: a bulging back cover, screen lift or wobble on a flat surface is a stop signal.
2. If the phone is permanently powered, keep it shaded and below ~40 °C ambient.
3. Replace the battery if it swells, overheats at idle or drops from full to empty in minutes.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (8), `battery` (2), `background_activity` (3), `overlay` (1), `charge_limit` (3).

Known devices in this family:

- `CPH2381|Nord CE 2 Lite|CPH2409` (not tested on hardware) — Android 13 / OxygenOS 13. 3.5 mm jack is absent on some variants; a USB-C to TRRS adapter is needed for a wired mic. 5000 mAh battery, non-removable. This is one entry in the table, not a code path.

---

## Xiaomi / Redmi / Poco (MIUI, HyperOS)

- Profile id: `xiaomi`
- Matches: xiaomi, redmi, poco
- Reference: <https://dontkillmyapp.com/xiaomi>

MIUI/HyperOS needs Autostart, Battery saver = No restrictions, and often 'Display pop-up windows while running in background'. The recents lock is essential. Some builds hide Autostart until the app has been opened once.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → Manage apps → ForestEar Node → Autostart.
2. Enable Autostart and any 'Start in background' option.
3. Also open the Security app → Permissions → Autostart and enable ForestEar Node.

**Battery optimisation**

1. Open Settings → Battery → Battery saver → App battery saver → ForestEar Node.
2. Choose No restrictions.
3. Disable 'Sleep standby optimisation' for this app if present.

**Background activity**

1. Open Settings → Apps → Manage apps → ForestEar Node → Battery saver.
2. Choose No restrictions.
3. Enable 'Display pop-up windows while running in background' if present.

**Lock in Recents**

1. Open Recents, long-press the ForestEar Node card.
2. Tap the lock icon.

**Display over other apps (optional)**

1. Open Settings → Apps → Manage apps → ForestEar Node → Other permissions.
2. Allow 'Display pop-up windows while running in background'.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Battery protection / Charging limit.
2. If an 80% cap exists, consider enabling it for a permanently powered node.

**Battery swelling inspection**

1. Inspect for swelling; MIUI reports battery health in Settings → Battery.
2. Replace a swollen or rapidly degrading battery before permanent deployment.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (3), `battery` (3), `background_activity` (2), `overlay` (1), `charge_limit` (2).

---

## Vivo / iQOO (Funtouch OS, OriginOS)

- Profile id: `vivo`
- Matches: vivo, iqoo
- Reference: <https://dontkillmyapp.com/vivo>

Funtouch/OriginOS has an Autostart manager and a separate high background power consumption allowlist. Both are needed. Some builds label it 'Background power consumption management'.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → Autostart manager (or i Manager → App manager → Autostart).
2. Enable ForestEar Node.

**Battery optimisation**

1. Open Settings → Battery → Background power consumption management.
2. Allow ForestEar Node high background power consumption.
3. Set Battery optimisation to Don't optimise.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery.
2. Allow background activity and disable battery optimisation.

**Lock in Recents**

1. Open Recents, long-press the ForestEar Node card, tap the lock icon.

**Display over other apps (optional)**

1. Open Settings → Apps → Special app access → Display over other apps.
2. Allow ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Charging limit / Battery health.
2. Enable an 80–85% cap if offered.

**Battery swelling inspection**

1. Inspect for swelling and check Settings → Battery → Battery health.
2. Replace a swollen battery immediately.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (3), `battery` (2), `background_activity` (2), `overlay` (1), `charge_limit` (1).

---

## Samsung (One UI)

- Profile id: `samsung`
- Matches: samsung
- Reference: <https://dontkillmyapp.com/samsung>

One UI uses 'Sleeping apps' and 'Deep sleeping apps'. Remove ForestEar Node from both lists and set the battery usage to Unrestricted. The recents lock is less important on Samsung but still useful.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Battery and device care → Battery → Background usage limits.
2. Remove ForestEar Node from Sleeping apps and Deep sleeping apps.
3. Add it to Never sleeping apps if the list exists.

**Battery optimisation**

1. Open Settings → Apps → ForestEar Node → Battery.
2. Choose Unrestricted.
3. Disable 'Put unused apps to sleep' if you can.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery → Unrestricted.
2. Disable 'Pause app activity if unused'.

**Lock in Recents**

1. Open Recents, tap the ForestEar Node icon, choose Keep open / Lock.

**Display over other apps (optional)**

1. Open Settings → Apps → Special access → Appear on top.
2. Allow ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Battery protection.
2. Choose Maximum (80%) or Adaptive if offered.

**Battery swelling inspection**

1. Open Samsung Members → Diagnostics → Battery status.
2. Inspect for swelling and replace a degraded battery.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (3), `battery` (3), `background_activity` (2), `overlay` (1), `charge_limit` (2).

---

## Huawei / Honor (EMUI, MagicOS)

- Profile id: `huawei`
- Matches: huawei, honor
- Reference: <https://dontkillmyapp.com/huawei>

EMUI/MagicOS uses 'App launch' with three toggles per app (Auto-launch, Secondary launch, Run in background). All three must be enabled. Also exclude the app from power-intensive background cleanup.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → App launch (or Startup manager).
2. Find ForestEar Node and turn off automatic management.
3. Enable Auto-launch, Secondary launch and Run in background.

**Battery optimisation**

1. Open Settings → Battery → More battery settings → Power intensive apps.
2. Allow ForestEar Node.
3. Set Battery optimisation to Don't optimise.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery.
2. Allow background activity and disable power-intensive prompt.

**Lock in Recents**

1. Open Recents, swipe down on the ForestEar Node card, tap the lock icon.

**Display over other apps (optional)**

1. Open Settings → Apps → Special access → Display over other apps.
2. Allow ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Charging limit / Smart charge.
2. Enable an 80–85% cap if offered.

**Battery swelling inspection**

1. Open Settings → Battery → Battery health (if present).
2. Inspect for swelling; replace a swollen battery.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (3), `battery` (3), `background_activity` (2), `overlay` (1), `charge_limit` (1).

---

## Motorola (My UX)

- Profile id: `motorola`
- Matches: motorola, moto
- Reference: <https://dontkillmyapp.com/motorola>

Near-stock Android. Battery optimisation exemption and unrestricted battery usage are usually enough. Some My UX builds add a 'Battery saver' app list.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → ForestEar Node.
2. Enable any 'Allow auto-start' switch if present.

**Battery optimisation**

1. Open Settings → Battery → Battery optimisation → All apps → ForestEar Node → Don't optimise.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery → Unrestricted.

**Lock in Recents**

1. Open Recents, long-press the ForestEar Node card, tap the lock icon.

**Display over other apps (optional)**

1. Open Settings → Apps → Special app access → Display over other apps → ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Charging limit / Overcharge protection if offered.

**Battery swelling inspection**

1. Inspect for swelling and replace a degraded battery.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (1), `battery` (2), `background_activity` (1), `overlay` (1), `charge_limit` (1).

---

## Google Pixel / stock Android

- Profile id: `google`
- Matches: google, android
- Reference: <https://dontkillmyapp.com/google>

Stock Android is the least aggressive. Battery optimisation exemption plus unrestricted battery usage is normally sufficient. Android 15 still forbids a boot receiver from starting a microphone foreground service.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → ForestEar Node.
2. No OEM autostart switch is normally present; keep auto-start enabled in the app.

**Battery optimisation**

1. Open Settings → Battery → Battery optimisation → All apps → ForestEar Node → Don't optimise.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery → Unrestricted.

**Lock in Recents**

1. Open Recents and lock the ForestEar Node card if your launcher supports it.

**Display over other apps (optional)**

1. Open Settings → Apps → Special app access → Display over other apps → ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Charging optimisation / Limit to 80% if offered.

**Battery swelling inspection**

1. Inspect for swelling and replace a degraded battery.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (1), `battery` (2), `background_activity` (1), `overlay` (1), `charge_limit` (1).

---

## Nothing (Nothing OS)

- Profile id: `nothing`
- Matches: nothing
- Reference: <https://dontkillmyapp.com/nothing>

Near-stock Android with a light battery manager. Battery exemption and unrestricted usage are normally enough.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → ForestEar Node.
2. Enable any auto-start switch if present.

**Battery optimisation**

1. Open Settings → Battery → Battery optimisation → ForestEar Node → Don't optimise.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery → Unrestricted.

**Lock in Recents**

1. Open Recents, long-press the ForestEar Node card, tap the lock icon.

**Display over other apps (optional)**

1. Open Settings → Apps → Special app access → Display over other apps → ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Charging limit if offered.

**Battery swelling inspection**

1. Inspect for swelling and replace a degraded battery.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (1), `battery` (2), `background_activity` (1), `overlay` (1), `charge_limit` (1).

---

## Asus (ZenUI, ROG UI)

- Profile id: `asus`
- Matches: asus, zenfone, rog
- Reference: <https://dontkillmyapp.com/asus>

ZenUI/ROG UI has an Auto-start manager and a Mobile Manager power-saving list. Both may need ForestEar Node allowed.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → Auto-start manager.
2. Enable ForestEar Node.
3. Also open Mobile Manager → PowerMaster → Auto-start manager.

**Battery optimisation**

1. Open Settings → Battery → Battery optimisation → ForestEar Node → Don't optimise.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery → Unrestricted.

**Lock in Recents**

1. Open Recents, long-press the ForestEar Node card, tap the lock icon.

**Display over other apps (optional)**

1. Open Settings → Apps → Special app access → Display over other apps → ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery → Charging limit if offered.

**Battery swelling inspection**

1. Inspect for swelling and replace a degraded battery.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (3), `battery` (2), `background_activity` (2), `overlay` (1), `charge_limit` (1).

---

## Other Android manufacturer

- Profile id: `generic`
- Matches: generic, other
- Reference: <https://dontkillmyapp.com/>

Unknown manufacturer. Use the standard Android battery-optimisation exemption and app-details screen, then look up the maker on dontkillmyapp.com. The app records only what the OS verifies or what you explicitly confirm.

### Steps

**Auto-launch / startup manager**

1. Open Settings → Apps → ForestEar Node.
2. Look for Auto-start, Startup manager or App launch and allow ForestEar Node.

**Battery optimisation**

1. Open Settings → Battery → Battery optimisation → All apps → ForestEar Node → Don't optimise.

**Background activity**

1. Open Settings → Apps → ForestEar Node → Battery → Unrestricted / Allow background activity.

**Lock in Recents**

1. Open Recents, long-press the ForestEar Node card, tap the lock icon if available.

**Display over other apps (optional)**

1. Open Settings → Apps → Special app access → Display over other apps → ForestEar Node.

**Charge limit / battery care (optional)**

1. Open Settings → Battery and look for a charging limit or battery protection option.

**Battery swelling inspection**

1. Inspect the battery for swelling and replace it if deformed or rapidly degrading.

Deep-link shortcuts (best effort, resolved before launch): `autostart` (1), `battery` (2), `background_activity` (1), `overlay` (1), `charge_limit` (1).

---

## Generic fallback

If no profile matches, the app uses the standard Android screens:

1. Settings → Battery → Battery optimisation → All apps → ForestEar Node → Don't optimise.
2. Settings → Apps → ForestEar Node → Battery → Unrestricted.
3. Lock the app in Recents if the maker offers it.

The Status tab keeps a warning visible while a required protection is missing.
