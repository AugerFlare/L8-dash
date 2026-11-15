# L8 Dash — build & handoff

Live wallpaper for a BYD Leopard 8 (Fangchengbao Bao 8, Chinese-market DiLink head unit, imported to UAE).
Pure black dashboard: pencil-sketch car (animated: parked / driving / charging / door open), tyre psi + temp,
battery & fuel rings, fuel level, speed, odometer, EV / fuel / total range, 12V, outside temp.
Not interactive.

## STATUS (2026-11-15): built, installed, working on the car ✅
- Toolchain bumped for JDK 25: AGP 9.4.1, Gradle 9.7.1, compileSdk 36.
- **Data path changed.** Direct reflection on `android.hardware.bydauto.*` is DENIED for normal apps on this car
  (signature-only *_GET perms; getInt is hidden-API blocked). CarData now reads through `ShellHelper`
  (runs as shell on 127.0.0.1:47824, read-only txn 5/7), using the CAN-FD feature IDs verified in
  `../../FINDINGS.md`. Start it once per car boot: `sh start-helper.sh`.
- **Safe insets** added in Dash.java (INSET_TOP 5.5%, INSET_BOTTOM 7.5%) so content clears DiLink's
  persistent top status bar and bottom icon dock (they overlay wallpaper AND full-screen apps).
- Verified live: battery/fuel %, EV/fuel/total range, odometer, 12V, outside temp, 4 tyre psi.
  Tyre temp shows "—" (this car exposes tyre pressure only, no temp value).
- Preview reads the car on a background thread (socket I/O must never touch the UI thread).
- **Live wallpaper works** on this DiLink launcher, but setting it is interactive only: the picker
  (`com.android.wallpaper.livepicker/.LiveWallpaperChange`) won't apply from adb (no shell permission for
  `setWallpaperComponent`), so the user sets it via the wallpaper picker / tap-to-set. **Reinstalling the APK
  resets the wallpaper to stock** (binding drops), so finalize the design via MainActivity preview, then set
  the wallpaper last.
- The DiLink home overlays its own clock (top-left) and control dock (bottom) on the wallpaper. The state
  label was moved to the right column to clear the clock; the insets clear the dock.

**Never compiled before this.**

## 1. Build the APK on a PC

**Easiest — Android Studio**
1. Install Android Studio (it brings the JDK and Android SDK).
2. File → Open → select the `L8Dash` folder. Let Gradle sync (it downloads Gradle 8.7 + AGP 8.5.2).
   - If it asks to create the Gradle wrapper, accept.
3. Build → Build App Bundle(s) / APK(s) → Build APK(s).
4. APK: `app/build/outputs/apk/debug/app-debug.apk`

**Command line** (needs JDK 17 + Android SDK with platform 34; set `ANDROID_HOME` or add `local.properties` with `sdk.dir=...`)
```
gradle wrapper --gradle-version 8.7   # once, needs Gradle installed (no gradlew is included)
./gradlew assembleDebug               # gradlew.bat on Windows
```

**With Claude Code**: open a terminal in this folder, run `claude`, say "Read HANDOFF.md, build the APK and fix any build errors."

## 2. Install on the car
Car connected over ADB:
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.l8dash android.permission.BYDAUTO_STATISTIC_GET
adb shell pm grant com.l8dash android.permission.BYDAUTO_TYRE_GET
adb shell pm grant com.l8dash android.permission.BYDAUTO_SPEED_GET
adb shell pm grant com.l8dash android.permission.BYDAUTO_AC_GET
adb shell pm grant com.l8dash android.permission.BYDAUTO_BODYWORK_GET
adb shell pm grant com.l8dash android.permission.BYDAUTO_CHARGING_GET
```
(Grant errors like "not a changeable permission" are fine — skip them.)

Open **L8 Dash** → full-screen live preview. Tap it → live wallpaper picker → set.
If BYD's launcher doesn't show live wallpapers, the preview app itself still works as a dashboard.

Screen size check: `adb shell wm size`. Layout auto-switches: wide screen = two columns, tall = stacked.

## 3. Things that will probably need tuning (all in `CarData.java`, top of file)
| Symptom | Fix |
|---|---|
| A value shows "—" | BYD class/method name guess is wrong. Find real names by pulling and decompiling the BYD framework (`adb shell ls /system/framework`, then JADX on the `bydauto` jar). |
| Tyre psi wrong | `TYRE_TO_PSI` (0.145 = kPa→psi; 1.0 if already psi; 14.5 if bar). |
| Tyres in wrong order | `TYRE_AREAS`. |
| Charging never shows | `CHARGING_STATE` code / method name. |
| Door never shows | `DOOR_OPEN_STATE` code / `getDoorState` name. |

All BYD access is read-only via reflection on `android.hardware.bydauto.*`. Never write to driving-related systems.

## 4. Files
- `app/src/main/java/com/l8dash/CarData.java` — reads the car (reflection, knobs above).
- `Dash.java` — all drawing + animation. Door outlines = `DOORS` polygons (pixel coords on the 1102×627 sketch).
- `DashWallpaper.java` — wallpaper service: ~20 fps animation, reads car every 5 s, only while visible.
- `MainActivity.java` — full-screen preview, tap to set wallpaper.
- `res/drawable-nodpi/car_sketch.png` — pencil sketch (sand colour #D8CBB4, transparent background).
- `tools/make_sketch.py` + `tools/car_photo.jpg` — regenerates the sketch:
  `pip install opencv-python-headless numpy` then
  `python tools/make_sketch.py tools/car_photo.jpg app/src/main/res/drawable-nodpi/car_sketch.png`
  (keep the same photo/crop or the door outlines won't line up).

## 5. Open ideas
- Make the wheels rounder in the sketch.
- Drop the duplicate outside temp from the top-right if not wanted.
- Next modules from the bigger car-app plan: passenger screen, external speaker (parked only), cluster.
