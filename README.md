# Quest Manager
On-headset manager: pairs with its own Wireless debugging (no PC), lists apps with storage, uninstalls, opens Meta Store pages, per-game goals/tips checklist, UI settings.

## Build the .apk (no PC build needed)
1. Make a free GitHub repo, upload this folder, open Actions -> "Build APK" -> run. Download `QuestManager-apk` (app-debug.apk).
   Or: install Android Studio, open this folder, Build -> Build APK(s).
2. Install on the headset once (Developer Mode on): SideQuest on a phone, or `adb install app-debug.apk`.
3. Open it from Apps -> Unknown Sources. Enable Wireless debugging, pair, connect.

## Notes
- If the libadb-android line fails to resolve, use the latest tag from jitpack.io/#MuntashirAkon/libadb-android.
- Add games to app/src/main/assets/games.json (package name -> name + goals).
