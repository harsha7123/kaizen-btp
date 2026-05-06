# DroidX — Quick Actions Reference

Fast copy-paste commands for common dev tasks. All commands run from a bash shell on Windows.

---

## 1. Build & Install APK

```bash
# Build debug APK
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd C:/mydata/DroidX
./gradlew assembleDebug

# Install on emulator
C:/Users/I584630/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r \
  app/build/outputs/apk/debug/app-debug.apk

# Install on Samsung device (serial R5CXC2CQKFK)
C:/Users/I584630/AppData/Local/Android/Sdk/platform-tools/adb.exe -s R5CXC2CQKFK install -r \
  app/build/outputs/apk/debug/app-debug.apk
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

> JAVA_HOME must point to Android Studio's bundled JBR — it is NOT on the system PATH.

---

## 2. Deploy Backend (srv-only — no schema changes)

Use `cf push` when only `.js` / `.cds` / `server.js` changed and no new HANA tables are needed. ~30 s.

```bash
cd C:/mydata/ForkQA/srv
cf push ForkQA-srv
```

---

## 3. Deploy Backend (new HANA tables — full MTAR)

### 3a. Patch an existing MTAR with Python (Windows workaround — `mbt` is broken)

1. Copy and edit the patch script template:

```python
# Patch script lives in: C:/mydata/ForkQA/mta_archives/patch_1.4.x.py
# Key variables to update for a new version:
SRC  = "C:/mydata/ForkQA/mta_archives/ForkQA_1.4.3.mtar"   # previous version
DST  = "C:/mydata/ForkQA/mta_archives/ForkQA_1.4.4.mtar"   # new version

# db_replacements: map inner-zip path -> local file path
# e.g. "src/tables/NEW_TABLE.hdbtable": open("C:/mydata/ForkQA/db/src/tables/NEW_TABLE.hdbtable", "rb").read()

# srv_replacements: ALWAYS include all four:
#   "catalog-service.js", "catalog-service.cds", "server.js", "app/index.html"
```

> **Important:** `app/index.html` must be listed in the srv replacements even if it hasn't changed, otherwise the deployed version goes out of sync with local files.

2. Run it:

```bash
cd C:/mydata/ForkQA/mta_archives
python patch_1.4.2.py   # replace with your script name
```

3. Verify output size is roughly the same as the source (±a few KB).

### 3b. Deploy the new MTAR

```bash
cf deploy C:/mydata/ForkQA/mta_archives/ForkQA_1.4.3.mtar -f
```

> `-f` = force (skip confirmation prompt). Do NOT use `--no-confirm` — it is not a valid flag.

> CF SSO passcode: `cf login -a https://api.cf.us10-001.hana.ondemand.com --sso-passcode <passcode>`
> Get passcode from: https://login.cf.us10-001.hana.ondemand.com/passcode

---

## 4. Seed / Reset Test Data

```bash
BASE=https://f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001.hana.ondemand.com

# Warehouse tasks (8 tasks, numbers 0000000001–0000000008, STATUS=OPEN)
curl -X POST $BASE/api/taskSeed

# Production orders + GR items
curl -X POST $BASE/api/prodSeed

# QC items (10 items, mix of PENDING/APPROVED/REJECTED)
curl -X POST $BASE/api/qcSeed

# Full reset — clears GR, HU (GR status), production orders, QC items, warehouse tasks, then re-inserts all
# Response: { prodOrders: 5, qcItems: 10, wtasks: 8 }
curl -X POST $BASE/api/resetData
```

---

## 5. Check Backend Health

```bash
BASE=https://f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001.hana.ondemand.com

# HTTP status check (expect 200)
curl -s -o /dev/null -w "%{http_code}" $BASE/api/Deliveries

# Spot-check warehouse tasks
curl -s "$BASE/api/WarehouseTasks?\$top=3" | python -m json.tool

# Confirm a specific task was saved
curl -s "$BASE/api/WarehouseTasks?\$filter=TASK_NUMBER eq '0000000001'" | python -m json.tool
```

---

## 6. CF Status & Logs

```bash
# App status
cf apps

# Recent logs (srv is the long-running process)
cf logs ForkQA-srv --recent

# Streaming logs (follow)
cf logs ForkQA-srv

# Check bound services / HDI container
cf services

# Restart srv without redeploying
cf restart ForkQA-srv
```

Expected `cf apps` output:
```
name        requested state  instances  memory  routes
ForkQA-db   stopped          0/1        256M    (no route)    ← normal after HDI deploy
ForkQA-srv  started          1/1        256M    f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001...
```

---

## 7. ADB Device Commands

```bash
ADB=C:/Users/I584630/AppData/Local/Android/Sdk/platform-tools/adb.exe

$ADB devices                                     # list connected devices/emulators
$ADB logcat -s "DroidX" "ConfirmTask" "WH"      # filtered logcat
$ADB logcat -c                                   # clear logcat buffer
$ADB shell am start -n com.sap.droidx/.ui.SplashActivity   # launch app
$ADB shell am force-stop com.sap.droidx         # force-stop app
```

---

## 8. Adding a New Feature — Checklist

### Backend (new entity)

- [ ] Add `.hdbtable` to `C:/mydata/ForkQA/db/src/tables/NEW_TABLE.hdbtable`
- [ ] Add entity to `C:/mydata/ForkQA/srv/catalog-service.cds`
- [ ] Add READ / CREATE / UPDATE / DELETE handlers to `C:/mydata/ForkQA/srv/catalog-service.js`
- [ ] Add seed endpoint to `C:/mydata/ForkQA/srv/server.js` (optional)
- [ ] Write Python patch script based on `patch_1.4.2.py`, bump version to `1.x.y`
- [ ] Run patch script, then `cf deploy ... -f`
- [ ] Verify with `curl` before touching Android code

### Android (new screen)

- [ ] Create `data/NewEntityRepository.kt` (OkHttp calls via `ClientProvider.get()` + `BtpConfig.FORKQA_BACKEND_URL`)
- [ ] Create `data/NewEntity.kt` data class (map all fields including confirmed/status fields)
- [ ] Create `ui/NewActivity.kt` (extend `AppCompatActivity`, use `lifecycleScope.launch`)
- [ ] Create `res/layout/activity_new.xml` (CoordinatorLayout + NestedScrollView + BottomAppBar)
- [ ] Add `<activity android:name=".ui.NewActivity" android:theme="@style/Theme.DroidX.NoActionBar" />` to `AndroidManifest.xml`
- [ ] Wire navigation from Dashboard tile or RF Menu item
- [ ] Add scan launchers with `registerForActivityResult(ScanContract())`
- [ ] For camera: add FileProvider cache-path to `res/xml/file_paths.xml`
- [ ] Build and install: steps 1 above

### NCLOB / photo fields — mandatory patterns

```javascript
// CAP READ handler — always convert Buffer to string
if (row.PHOTO && Buffer.isBuffer(row.PHOTO)) row.PHOTO = row.PHOTO.toString('utf8');

// CAP INSERT — always include in column list AND values array
`INSERT INTO "TABLE" (..., "PHOTO") VALUES (?, ..., ?)`, [..., d.PHOTO || null]
```

```kotlin
// Android — never use optString() for nullable JSON fields
val value = json.opt("PHOTO")
val photo = if (value == null || value == JSONObject.NULL) null else value.toString()
```

### Android known pitfall — RecyclerView inside ScrollView

`RecyclerView` with `wrap_content` inside a `ScrollView` silently drops items that fall outside the initial visible area (Android measurement bug). For static lists, replace RecyclerView + Adapter with a plain `LinearLayout` populated via `LayoutInflater.inflate()` + `addView()` in `onCreate`. See `RFMenuActivity.kt` for the pattern.

### Bottom navigation bar — use slim LinearLayout, not BottomAppBar

`BottomAppBar` has a built-in min-height of ~56 dp plus internal padding (~80 dp total gap). For a simple back button, use a `LinearLayout` (44 dp, white, elevation 4 dp) with a single `ImageButton` inside, and set `ScrollView paddingBottom="44dp"`. See `activity_rf_menu.xml`.

---

## 9. MTAR Structure Reference

```
ForkQA_1.x.y.mtar           ← outer ZIP
  ForkQA-db/data.zip         ← inner ZIP: db node_modules + src/tables/*.hdbtable
  ForkQA-srv/data.zip        ← inner ZIP: srv node_modules + catalog-service.js/.cds + server.js
  META-INF/MANIFEST.MF
  META-INF/mtad.yaml
```

Inner ZIP entry paths (forward slash, no leading `./`):
- db: `src/tables/TABLE_NAME.hdbtable`
- srv: `catalog-service.js`, `catalog-service.cds`, `server.js`, `app/index.html`, `package.json`

> `app/index.html` lives at path `app/index.html` inside `ForkQA-srv/data.zip`. Always include it in the srv replacements so web dashboard changes deploy with the MTAR.

---

## 10. Key File Locations

| Purpose | Path |
|---------|------|
| BTP config (URLs, client ID) | [`app/src/main/java/com/sap/droidx/BtpConfig.kt`](app/src/main/java/com/sap/droidx/BtpConfig.kt) |
| CAP service definition | `C:/mydata/ForkQA/srv/catalog-service.cds` |
| CAP handlers | `C:/mydata/ForkQA/srv/catalog-service.js` |
| Express server / seed endpoints | `C:/mydata/ForkQA/srv/server.js` |
| HANA table DDL | `C:/mydata/ForkQA/db/src/tables/*.hdbtable` |
| MTAR archives | `C:/mydata/ForkQA/mta_archives/` |
| Latest Python patch script | `C:/mydata/ForkQA/mta_archives/patch_1.4.3.py` |
| FileProvider paths | [`app/src/main/res/xml/file_paths.xml`](app/src/main/res/xml/file_paths.xml) |
| Dashboard tiles | [`app/src/main/java/com/sap/droidx/ui/DashboardActivity.kt`](app/src/main/java/com/sap/droidx/ui/DashboardActivity.kt) |
| RF Menu items | [`app/src/main/java/com/sap/droidx/ui/RFMenuActivity.kt`](app/src/main/java/com/sap/droidx/ui/RFMenuActivity.kt) |
