# DroidX — Claude Code Instructions

SAP BTP Android warehouse management app. Kotlin + SAP Mobile Services SDK (Flows V2).
Backend: CAP Node.js service (ForkQA) on BTP Cloud Foundry trial.

---

## Build & Install

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd C:/mydata/DroidX
./gradlew assembleDebug

# Install on Samsung S25 Ultra (serial R5CXC2CQKFK)
C:/Users/I584630/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s R5CXC2CQKFK install -r app/build/outputs/apk/debug/app-debug.apk
```

> JAVA_HOME must point to Android Studio's bundled JBR — NOT on system PATH.
> APK: `app/build/outputs/apk/debug/app-debug.apk`

For full commands (seed data, CF deploy, ADB, etc.) see [SKILLS.md](SKILLS.md).

---

## Architecture

### Login flow (critical — do not change without understanding this)

```
SplashActivity (LAUNCHER)
  └─ FlowType.Onboarding (MDM_ONLY)
       ├─ BeforeActivation custom step  ← THE FIX
       │    └─ updateAppConfigBeforeActivation() + flowDone()
       │         skips server registration → prevents SDK overwriting our XSUAA config
       ├─ WebView OAuth → f6c0e9f2trial.authentication.us10.hana.ondemand.com
       └─ Passcode/Biometrics
            └─ onFlowFinished → QuestionnaireActivity → MainActivity
```

**Why BeforeActivation is required:** The SDK's `reconstructAppConfig()` uses an internal
HTTP client (not interceptable via `ClientProvider`) that overwrites AppConfig with
`accounts.sap.com` endpoints after activation. `updateAppConfigBeforeActivation()` +
`flowDone()` skips ALL predefined activation steps including the server registration call.
`MDM_ONLY` alone is NOT sufficient.

### Backend

- **Service:** ForkQA CAP (Node.js) on BTP CF trial
- **Base URL:** `https://f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001.hana.ondemand.com`
- **API path:** `/api` (e.g. `/api/WarehouseTasks`, `/api/Deliveries`)
- **Auth:** XSUAA OAuth via SAP Mobile Services proxy
- **Source:** `C:/mydata/ForkQA/srv/`

All Android HTTP calls go through `ClientProvider.get()` (SAP SDK's authenticated OkHttp
client) using `BtpConfig.FORKQA_BACKEND_URL`.

### Key files

| File | Purpose |
|------|---------|
| [BtpConfig.kt](app/src/main/java/com/sap/droidx/BtpConfig.kt) | All URLs/OAuth config (values injected from `local.properties`) |
| [DroidXApplication.kt](app/src/main/java/com/sap/droidx/DroidXApplication.kt) | SDK init, `DroidXFlowActionHandler` registration |
| [DashboardActivity.kt](app/src/main/java/com/sap/droidx/ui/DashboardActivity.kt) | Main tile grid, entry point after login |
| [MainActivity.kt](app/src/main/java/com/sap/droidx/ui/MainActivity.kt) | Warehouse task list (delivery/RF flow) |
| [ConfirmTaskActivity.kt](app/src/main/java/com/sap/droidx/ui/ConfirmTaskActivity.kt) | WT confirmation with GPS, photos, barcode scan |
| [QuestionnaireActivity.kt](app/src/main/java/com/sap/droidx/ui/QuestionnaireActivity.kt) | Post-login inspector profile setup |

---

## Known Pitfalls

### RecyclerView inside ScrollView drops items
`RecyclerView` with `wrap_content` inside a `ScrollView` silently drops items outside
the initial visible area (Android measurement bug). Use a plain `LinearLayout` with
`LayoutInflater.inflate()` + `addView()` instead. See `RFMenuActivity.kt`.

### BottomAppBar has ~80dp min height
For a simple back button, use a `LinearLayout` (44dp, white, elevation 4dp) with a single
`ImageButton`. Set `ScrollView paddingBottom="44dp"`. See `activity_rf_menu.xml`.

### NCLOB / photo fields (HANA)
```kotlin
// Android — never use optString() for nullable JSON fields
val value = json.opt("PHOTO")
val photo = if (value == null || value == JSONObject.NULL) null else value.toString()
```
```javascript
// CAP READ handler — always convert Buffer to string
if (row.PHOTO && Buffer.isBuffer(row.PHOTO)) row.PHOTO = row.PHOTO.toString('utf8');
```

### HTML onclick inside SAP UI5 HTML control
Functions defined inside `sap.ui.require()` callback are not globally accessible.
Expose via `window._myFunction = myFunction` after the definition.

### `addSingleStep` lambda signature (Flows V2)
Correct: `{ _ -> LaunchedEffect(Unit) { ... } }` (ONE explicit param = NavBackStackEntry)
Wrong:   `{ _, _ -> }` (treats AnimatedContentScope receiver as explicit param)

---

## Backend Deploy

**srv-only changes** (no schema changes): `cf push ForkQA-srv` from `C:/mydata/ForkQA/srv/`

**Schema changes** (new HANA tables): patch MTAR with Python script then `cf deploy`.
Latest patch script: `C:/mydata/ForkQA/mta_archives/patch_1.4.3.py`
Always include `app/index.html` in srv replacements — it lives at path `app/index.html`
inside `ForkQA-srv/data.zip`. Omitting it leaves the web dashboard on the old version.

CF login: `cf login -a https://api.cf.us10-001.hana.ondemand.com --sso-passcode <code>`
SSO passcode: https://login.cf.us10-001.hana.ondemand.com/passcode

---

## Secrets — never commit

`local.properties` holds all BTP URLs, client ID, client secret. It is gitignored.
Never hardcode secrets in `BtpConfig.kt` — all values come from `BuildConfig` fields
injected at build time from `local.properties`.
