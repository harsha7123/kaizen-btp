# DroidX — SAP BTP Trial Android Client

DroidX is a Kotlin/Android client that authenticates against an **SAP BTP Trial**
subaccount using the **SAP BTP SDK for Android 26.1.2** and reads/writes data in
**SAP HANA Cloud** via the CAP OData service.

## Findings & Resolution Log (Migration to SDK 26.1.2)

During the migration from SDK 24.x to 26.1.2, several critical platform behaviors were identified and resolved:

### 1. OAuth2 Logon Fix (Bypassing Proxy)
*   **Problem**: Using the standard Mobile Services proxy authorize endpoints (`/oauth2/api/v1/authorize`) resulted in an `invalid_request` JSON error in the WebView, stating that `client_id` and `response_type` were missing.
*   **Root Cause**: The Mobile Services proxy on BTP Trial organziations can sometimes strip or fail to forward query parameters to XSUAA.
*   **Resolution**: Switched to **Direct XSUAA Authentication**. The app now points directly to the XSUAA identity service (`...authentication.us10.hana.ondemand.com`) for the `authorize` and `token` calls, bypassing the proxy routing issues.

### 2. Android 13/14 Certificate Trust
*   **Problem**: SSL Handshake errors or blank screens when loading the SAP ID logon page.
*   **Root Cause**: Android 13+ removed trust for several legacy root certificates. SAP BTP often uses the **DigiCert Global Root G5**.
*   **Resolution**: Implemented `app/src/main/res/xml/network_security_config.xml` to explicitly trust the DigiCert Global Root G5. This is linked in the `AndroidManifest.xml` under `android:networkSecurityConfig`.

### 3. "Landing Error" (Wildcard URLs)
*   **Problem**: WebView redirected to a URL like `https://%2A.cfapps.../**`.
*   **Findings**: This indicates a routing failure in Mobile Services where it cannot resolve the destination organization. This is avoided by ensuring the `SERVER_URL` in `BtpConfig.kt` matches the organizational host provided in the APIs tab, and using the correct `Redirect URL`.

### 4. Stale Session Clearing
*   **Problem**: "Identity Provider could not process the authentication request" error.
*   **Resolution**: Added `CookieManager.getInstance().removeAllCookies(null)` in `SplashActivity.kt`. This ensures that every logon attempt starts with a clean slate, preventing stale cookies from interfering with XSUAA state.

---

## Backend Configuration Requirements

To validate the connection, ensure the following are set in the **SAP BTP Cockpit**:

1.  **Mobile Services App**: A Native app with ID `com.sap.droidx`.
2.  **Security**: Set to **OAuth 2.0**.
3.  **Allowed Redirect URLs**: Must include `com.sap.droidx://oauth`.
4.  **XSUAA Service Key**: 
    *   If using a specific service key (the `sb-` client ID), the `redirect-uris` array in the service instance parameters MUST contain `com.sap.droidx://oauth`.
    *   The `OAUTH_CLIENT_ID` in the app must match the `clientid` from this key.

## Project Layout

| Component | File |
|------|------|
| **BTP Config** | [`BtpConfig.kt`](app/src/main/java/com/sap/droidx/BtpConfig.kt) |
| **SDK Bootstrap** | [`DroidXApplication.kt`](app/src/main/java/com/sap/droidx/DroidXApplication.kt) |
| **Onboarding Start** | [`SplashActivity.kt`](app/src/main/java/com/sap/droidx/ui/SplashActivity.kt) |
| **Post-Auth Questionnaire** | [`QuestionnaireActivity.kt`](app/src/main/java/com/sap/droidx/ui/QuestionnaireActivity.kt) |
| **Home Screen** | [`MainActivity.kt`](app/src/main/java/com/sap/droidx/ui/MainActivity.kt) |
| **Certificate Fix** | [`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml) |

---

## 5. "Where to?" Page & Login Screen Fix — Root Cause & Resolution

### Problem
On every fresh launch the app showed the SAP Flows V2 **"Where to?"** screen, and clicking "Register this device" loaded `accounts.sap.com` with `ERR_NAME_NOT_RESOLVED` instead of the XSUAA login page.

Two requirements were also established:
- A custom **Inspector Setup questionnaire** (name / site / role) must appear **after** successful login.
- The login WebView must load `f6c0e9f2trial.authentication.us10.hana.ondemand.com`, not `accounts.sap.com`.

### Root Cause Analysis (confirmed from logcat + SDK API inspection)

The **"Where to?"** screen is `step_flow_start` — the activation-method selection screen that is the first route of `FlowType.Onboarding`. With the default `ActivationOption.DS_OR_QR`, the SDK shows this screen and waits for the user to tap one of the activation buttons. Every button triggers the **SAP Discovery Service** (routing through `accounts.sap.com`), which bypasses the `AppConfig` we supplied in `FlowContext` and uses a foreign `client_id` (`8c960bdf-...`) that belongs to Mobile Services, not our XSUAA tenant. Result: `ERR_NAME_NOT_RESOLVED` because `accounts.sap.com` is unreachable from the emulator.

**Attempted but failed fix — `ActivationOption.DS_ONLY`:**
Setting `DS_ONLY` does auto-skip the activation-selection UI, but it still routes through the Discovery Service using the same `accounts.sap.com` flow — the SDK ignores the provided `AppConfig` and makes a DS network request. Same `ERR_NAME_NOT_RESOLVED` result.

### SDK API Research (flows-compose-26.1.2-api.jar, inspected via javap)

| API | Relevance |
|-----|-----------|
| `ActivationOption.DS_OR_QR` (default) | Shows "Where to?" screen; all paths go through DS |
| `ActivationOption.DS_ONLY` | Skips UI but still calls DS network — does NOT use our AppConfig |
| `ActivationOption.MDM_ONLY` | Skips UI **and** calls `FlowActionHandler.activateFromManagedConfig()` — no DS network request |
| `FlowActionHandler.activateFromManagedConfig(Bundle): AppConfig` | Suspend function; override to return our AppConfig directly |
| `FlowContext(flowActionHandler = ...)` | Constructor parameter accepting a custom `FlowActionHandler` |

### Fix Applied — `MDM_ONLY` + custom `FlowActionHandler`

**`ActivationOption.MDM_ONLY`** tells the SDK to use the MDM activation path. Instead of launching the Discovery Service, it calls `FlowActionHandler.activateFromManagedConfig()` on whatever handler is registered in `FlowContext`. By overriding that method to return our pre-built `AppConfig`, the SDK never touches `accounts.sap.com` and proceeds directly to EULA → our XSUAA OAuth WebView.

**New file — `DroidXFlowActionHandler.kt`:**
```kotlin
class DroidXFlowActionHandler(private val appConfig: AppConfig) : FlowActionHandler() {
    override suspend fun activateFromManagedConfig(bundle: Bundle): AppConfig = appConfig
}
```

**Updated `SplashActivity.kt`:**
```kotlin
val myAppConfig = buildAppConfig()
val flowContext = FlowContext(
    appConfig = myAppConfig,
    flowType = FlowType.Onboarding,
    flowOptions = FlowOptions(
        activationOption = ActivationOption.MDM_ONLY,
        oAuthOption = OAuthOption(enablePKCE = true)
    ),
    flowStateListener = DroidXFlowListener(onDone = { /* → QuestionnaireActivity */ }, ...),
    flowActionHandler = DroidXFlowActionHandler(myAppConfig)
)
```

**Log evidence — after fix:**
```
FlowActivity: Current route: step_flow_start      ← auto-skipped (~300ms, no tap)
FlowActivity: Current route: step_onboarding_eula ← straight to EULA
... WebViewActivity loads f6c0e9f2trial.authentication.us10.hana.ondemand.com ...
DroidX: Onboarding DONE → launching Questionnaire
```

Zero `accounts.sap.com` entries in logs.

### Final Navigation Chain

```
SplashActivity  ← LAUNCHER
  └─ FlowType.Onboarding (SDK, MDM_ONLY)
       ├─ step_flow_start  (auto-skipped via MDM_ONLY, ~300ms, invisible)
       ├─ step_onboarding_eula
       ├─ WebView OAuth → f6c0e9f2trial.authentication.us10.hana.ondemand.com (XSUAA)
       └─ Passcode/Biometrics
            └─ onFlowFinished → QuestionnaireActivity (Inspector Setup)
                 └─ on submit (or profile exists) → MainActivity
```

**Changed / new files:**

| File | Change |
|------|--------|
| [`DroidXFlowActionHandler.kt`](app/src/main/java/com/sap/droidx/ui/DroidXFlowActionHandler.kt) | **New** — returns our AppConfig from `activateFromManagedConfig`; bypasses DS |
| [`SplashActivity.kt`](app/src/main/java/com/sap/droidx/ui/SplashActivity.kt) | Added `MDM_ONLY` + `flowActionHandler = DroidXFlowActionHandler(myAppConfig)` |
| [`QuestionnaireActivity.kt`](app/src/main/java/com/sap/droidx/ui/QuestionnaireActivity.kt) | **New** — post-login form; collects name/site/role; skips if profile exists |
| [`activity_questionnaire.xml`](app/src/main/res/layout/activity_questionnaire.xml) | **New** — form layout |

**Profile data is accessible anywhere in the app:**
```kotlin
val prefs = context.getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
val name = prefs.getString(QuestionnaireActivity.KEY_NAME, "")
val site = prefs.getString(QuestionnaireActivity.KEY_SITE, "")
val role = prefs.getString(QuestionnaireActivity.KEY_ROLE, "")
```

### Notes for Future Projects

1. **Always use `MDM_ONLY` + `FlowActionHandler`** when providing `AppConfig` directly and bypassing Discovery Service. `DS_ONLY` still calls the DS network; only `MDM_ONLY` skips it entirely.
2. **`activateFromManagedConfig` is the clean injection point** — override it in a `FlowActionHandler` subclass and return your `AppConfig`. No need for reflection or internal SDK constants.
3. **Post-login screens go in `onFlowFinished`** — launch the questionnaire (or any post-auth screen) from `DroidXFlowListener.onFlowFinished`. The SDK flow is complete at that point and the token is stored.
4. **Profile-gate pattern** — check for saved profile in the post-login activity's `onCreate`; if complete, skip the form and go straight to `MainActivity`.

---

## Development Setup

```bash
# Build the project (JAVA_HOME must point to the bundled JDK)
JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew assembleDebug

# Install on the My_Pixel AVD
JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew installDebug

# Launch (SplashActivity is the launcher)
adb shell am start -n "com.sap.droidx/.ui.SplashActivity"
```

> **JAVA_HOME note:** The `jbr-17.0.14` JDK lives in `~/.jdks/` (placed there by IntelliJ/Android Studio).
> It is not on the system PATH. Always set `JAVA_HOME` explicitly or run the build from the IDE.

On first launch:
1. **EULA** — accept (SDK screen).
2. **BTP / XSUAA login** — enter trial credentials in the WebView (`f6c0e9f2trial.authentication.us10.hana.ondemand.com`).
3. **Passcode / Biometric** — set up device unlock.
4. **Inspector Setup** — enter your name, site, and role (`QuestionnaireActivity`).
5. **Home screen** — `MainActivity` fetches forklift inspections from HANA Cloud.

> The "Where to?" SDK screen no longer appears. `ActivationOption.MDM_ONLY` + `DroidXFlowActionHandler` causes `step_flow_start` to auto-route (~300ms) without user interaction, and the XSUAA login page loads directly without touching `accounts.sap.com`.

---

## 6. Camera Capture, Photo Storage, and Detail View

### Features Added
- Camera photo capture in `NewInspectionActivity` using `ActivityResultContracts.TakePicture()`
- Photo compressed (max 1024 px, JPEG 70%) and base64-encoded before POST
- `PHOTO_DATA : LargeString` added to `catalog-service.cds` and `NCLOB` column added to `INSPECTIONS.hdbtable`
- `InspectionDetailActivity` — tap any list row to see full inspection details + photo

### 6a. Camera → Logon Redirect (SDKInitializer)

**Problem:** After taking a photo, the app jumped back to the SAP logon screen.

**Root Cause:** `SDKInitializer.start()` in `Application.onCreate()` registers a `ProcessLifecycleOwner` observer. Every time the app returns to the foreground (including from the camera), the observer fires and triggers a re-authentication flow.

**Fix:** Remove `SDKInitializer.start()` entirely. The app does not use Mobile Services at runtime, so there is nothing to initialize. No `MobileService` instances need to be passed.

```kotlin
// DroidXApplication.kt — DO NOT call this:
// SDKInitializer.start(this, *arrayOf<MobileService>())
```

### 6b. Camera → Logon Redirect (FlowTimeoutLauncherActivity)

**Problem:** Even after removing `SDKInitializer`, returning from camera still triggered the logon screen in some cases.

**Root Cause:** `FlowContextRegistry.flowContext` persists across Activity transitions. The SDK's `FlowTimeoutLauncherActivity` monitors this context and re-runs the stored flow when the app returns from background. After onboarding completes, the registry still holds an active `FlowContext` with a real `FlowStateListener` whose `onFlowFinished` navigates to `QuestionnaireActivity` — so the SDK fires it on every camera return.

**Fix:** Immediately after onboarding completes, replace the stored `FlowContext` with a no-op listener:

```kotlin
private fun installNoOpFlowContext(appConfig: AppConfig = buildAppConfig()) {
    FlowContextRegistry.flowContext = FlowContext(
        appConfig = appConfig,
        flowType = FlowType.Restore,
        flowStateListener = object : FlowStateListener() {
            override suspend fun onFlowFinished(flowName: String?) { /* no-op */ }
            override suspend fun onFlowFinishedWithData(flowName: String?, data: Intent?) { /* no-op */ }
        }
    )
}
```

Call `installNoOpFlowContext()` in two places:
1. In `markDoneAndProceed()` — immediately after onboarding
2. In `onCreate()` when `KEY_ONBOARDED` is already true — so the no-op is set on every subsequent launch

> **Key insight:** `FlowContextRegistry.flowContext` is non-null (`FlowContext` type). You cannot assign `null`. You must replace it with a new `FlowContext` that has a no-op `FlowStateListener`.

### 6c. Race Condition — Photo Null at Submit Time

**Problem:** `PHOTO_DATA` was null in the database even though the user took a photo.

**Root Cause:** `showPhotoPreview()` encodes the photo asynchronously in a `lifecycleScope.launch(Dispatchers.IO)` coroutine. If the user tapped Submit before the coroutine completed, `photoBase64` was still null and the field was excluded from the POST body.

**Fix:** Disable the Submit button when the camera returns, and re-enable it only after `photoBase64` is set:

```kotlin
private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
    if (success && photoUri != null) {
        binding.btnSubmit.isEnabled = false   // disable while encoding
        showPhotoPreview(photoUri!!)
    }
}

// inside showPhotoPreview, after photoBase64 = Base64.encodeToString(...):
withContext(Dispatchers.Main) {
    binding.btnSubmit.isEnabled = true        // re-enable only when ready
}
```

Also handle the failure path (bitmap decode returns null) — re-enable Submit so the user is not stuck.

### 6d. PHOTO_DATA Missing from CAP Hand-Written SQL INSERT

**Problem:** `PHOTO_DATA` was always null in the database even though the POST body included it. HTTP 201 returned successfully.

**Root Cause:** The `CREATE` handler in `catalog-service.js` used a hand-written SQL INSERT listing every column explicitly. `PHOTO_DATA` was added to the CDS model and the `.hdbtable` schema but was never added to the INSERT column list.

**Fix:** Always keep the SQL column list and values array in sync when adding new fields:

```javascript
await tx.run(
    `INSERT INTO "INSPECTIONS" (
        ..., "CREATED_AT", "PHOTO_DATA"   // ← add new column here
    ) VALUES (?, ..., ?, ?)`,              // ← and add value here
    [..., now, d.PHOTO_DATA || null]
);
```

> **Rule:** When using raw SQL handlers in CAP, `SELECT *` or CDS-generated queries pick up new columns automatically. Hand-written INSERT/UPDATE statements do not — every new column must be added manually to both the column list and the values array.

### 6e. HANA NCLOB Returned as Node.js Buffer

**Problem:** `Base64.decode()` threw `IllegalArgumentException: bad base-64`. The `PHOTO_DATA` value in the JSON response was `{"type":"Buffer","data":[47,57,106,47,...]}` instead of a plain base64 string.

**Root Cause:** The HANA driver (`@sap/hana-client` / `hdb`) returns `NCLOB` column data as a Node.js `Buffer` object, not a JavaScript string. When CAP serializes this to JSON, `JSON.stringify(buffer)` produces the `{"type":"Buffer","data":[...]}` format.

**Fix:** In the CAP `READ` handler, explicitly convert the Buffer to a UTF-8 string before returning:

```javascript
const rows = await tx.run('SELECT * FROM "INSPECTIONS" WHERE "INSPECTION_ID" = ?', [id]);
const row = rows[0];
if (row && row.PHOTO_DATA && Buffer.isBuffer(row.PHOTO_DATA)) {
    row.PHOTO_DATA = row.PHOTO_DATA.toString('utf8');
}
return row;
```

> **Rule:** Any `NCLOB` (or `LargeString`) field retrieved via raw SQL in a CAP handler must be explicitly converted from Buffer to string. CDS-generated queries handle this automatically; raw `tx.run()` does not.

### 6f. MaterialToolbar Back Navigation

**Problem:** The back arrow on `InspectionDetailActivity` either did nothing or crashed the app.

**Root Cause:** `setSupportActionBar(toolbar)` causes `AppCompatDelegateImpl` to lazily replace the toolbar's navigation click listener with its own delegate. A subsequent `setNavigationOnClickListener { finish() }` gets overwritten. Later, both the delegate path (`onSupportNavigateUp`) and the custom listener path fire simultaneously on some API levels, causing a double-`finish()` that can crash.

**Fix:** Do not use `setSupportActionBar` in activities that only need back navigation. Drive the toolbar directly:

```kotlin
// No setSupportActionBar call
binding.toolbar.setNavigationOnClickListener { finish() }
binding.toolbar.title = row.forkliftId   // set title directly, not via supportActionBar
```

For a bottom back button, add a `BottomAppBar` to the `CoordinatorLayout`:

```xml
<com.google.android.material.bottomappbar.BottomAppBar
    android:id="@+id/bottomBar"
    android:layout_gravity="bottom"
    app:navigationIcon="?attr/homeAsUpIndicator" />
```

```kotlin
binding.bottomBar.setNavigationOnClickListener { finish() }
```

Add `android:paddingBottom="80dp"` and `android:clipToPadding="false"` to the `ScrollView` so content is not hidden behind the bottom bar.

### 6g. CAP Single-Entity vs List READ Handler

When overriding `READ` for an entity with a custom SQL handler, distinguish single-entity from collection requests using `req.params`:

```javascript
this.on('READ', 'Inspections', async (req) => {
    const tx = cds.transaction(req);
    if (req.params && req.params.length > 0) {
        // Single entity: GET /Inspections('id')
        const id = req.params[0].INSPECTION_ID ?? req.params[0];
        const rows = await tx.run('SELECT * FROM "INSPECTIONS" WHERE "INSPECTION_ID" = ?', [id]);
        const row = rows[0];
        if (row?.PHOTO_DATA && Buffer.isBuffer(row.PHOTO_DATA))
            row.PHOTO_DATA = row.PHOTO_DATA.toString('utf8');
        return row;
    }
    // Collection: exclude large fields for performance
    return tx.run('SELECT col1, col2, ... FROM "INSPECTIONS" ORDER BY "CREATED_AT" DESC');
});
```

`req.params[0]` is `{ KEY_FIELD: 'value' }` for a single-key entity. Do not parse `req.query.SELECT.where` — it is fragile and its structure varies by CAP version.

### 6h. InspectionCache — Sharing List Data to Detail Activity

`InspectionDetailActivity` needs the full row data (without re-fetching it) for instant display. Use a companion singleton:

```kotlin
object InspectionCache {
    var rows: List<InspectionRow> = emptyList()
}
```

Populate it in `MainActivity` after each list load:
```kotlin
InspectionCache.rows = rows
adapter.submit(rows)
```

Pass only the key via Intent; the detail activity resolves the row from the cache:
```kotlin
startActivity(Intent(this, InspectionDetailActivity::class.java)
    .putExtra(InspectionDetailActivity.EXTRA_ID, row.inspectionId))

// In InspectionDetailActivity:
val row = InspectionCache.rows.find { it.inspectionId == id }
```

This keeps the Intent payload small and avoids Parcelable boilerplate for large data classes.

### 6i. CAP / Express Body Size Limit for Photo Upload

**Problem:** `413 Request Entity Too Large` when submitting an inspection with a photo taken on a real device.

**Root Cause:** Express's default JSON body-parser limit is 100 KB. Real device camera photos (even after compression) produce base64 strings larger than this. The emulator produced smaller images during development, masking the issue.

**Fix — Backend:** Mount the JSON body-parser with a raised limit **before** `cds.serve()` in `server.js`:

```javascript
const app = express();
app.use(express.json({ limit: '10mb' }));  // must come before cds.serve()
await cds.serve('all').from(cds.model).in(app);
```

> If you use the default `cds.connect()` bootstrap without a custom `server.js`, set the limit in `package.json` under `"cds": { "server": { "body-parser": { "json": { "limit": "10mb" } } } }`.

**Fix — Android:** Also reduce photo size to stay well within the limit even before it is raised:
- Max dimension: 800 px (down from 1024 px)
- JPEG quality: 60% (down from 70%)

A real device photo at 800px / 60% produces roughly 40–80 KB of base64, well under the 10 MB backend limit and fast to upload over mobile networks.

```kotlin
private fun compressBitmap(src: Bitmap): ByteArray {
    val maxDim = 800
    val scaled = if (src.width > maxDim || src.height > maxDim) {
        val ratio = maxDim.toFloat() / maxOf(src.width, src.height)
        Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
    } else src
    return ByteArrayOutputStream().also {
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, it)
    }.toByteArray()
}
```

### 6j. Deploying Backend-Only Changes Without MTAR Rebuild

When only `srv/` files change (no HANA artifacts), skip the full MTAR build and push directly:

```bash
cd forkqa/srv
cf push ForkQA-srv   # re-stages and restarts only the Node.js app
```

This is significantly faster than `cf deploy` with a full MTAR and does not touch the HDI container or HANA schema.

### 6k. Delete Record Feature

**Requirements:** Tap a trash icon to delete an inspection; confirm via dialog; list refreshes automatically after deletion.

#### Android — Confirmation dialog + OkHttp DELETE

```kotlin
// InspectionDetailActivity.kt
private fun confirmDelete(id: String) {
    MaterialAlertDialogBuilder(this)
        .setTitle("Delete Inspection")
        .setMessage("Permanently delete this inspection record?")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete") { _, _ -> deleteRecord(id) }
        .show()
}

private fun deleteRecord(id: String) {
    lifecycleScope.launch {
        runCatching { repo.deleteInspection(id) }
            .onSuccess {
                InspectionCache.rows = InspectionCache.rows.filterNot { it.inspectionId == id }
                setResult(RESULT_OK)
                finish()
            }
            .onFailure {
                Snackbar.make(binding.root, "Delete failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
    }
}
```

```kotlin
// InspectionRepository.kt
suspend fun deleteInspection(id: String) = withContext(Dispatchers.IO) {
    val url = "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}/Inspections('$id')"
    val response = http.newCall(Request.Builder().url(url).delete().build()).execute()
    if (!response.isSuccessful)
        throw IllegalStateException("HTTP ${response.code}: ${response.body?.string()}")
}
```

#### Android — Delete icon on BottomAppBar (not top toolbar)

Place the menu icon on the `BottomAppBar` so it sits alongside the bottom back arrow:

```kotlin
binding.bottomBar.inflateMenu(R.menu.menu_inspection_detail)
binding.bottomBar.setOnMenuItemClickListener { item ->
    if (item.itemId == R.id.action_delete) { confirmDelete(id); true } else false
}
```

```xml
<!-- menu_inspection_detail.xml -->
<item android:id="@+id/action_delete"
    android:title="Delete"
    android:icon="@android:drawable/ic_menu_delete"
    app:showAsAction="always" />
```

> **Note:** If `inflateMenu` is called on the top `MaterialToolbar` when `setSupportActionBar` is NOT used, the icons render but `setOnMenuItemClickListener` is never invoked reliably. Always attach both `inflateMenu` and `setOnMenuItemClickListener` to the same bar widget.

#### Android — List refresh after delete using ActivityResultLauncher

`startActivity()` gives no callback. Use `ActivityResultContracts.StartActivityForResult()` so the list reloads when the detail activity closes after a successful delete:

```kotlin
// MainActivity.kt
private val detailLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    if (result.resultCode == RESULT_OK) load()
}

// In adapter click handler:
detailLauncher.launch(
    Intent(this, InspectionDetailActivity::class.java)
        .putExtra(InspectionDetailActivity.EXTRA_ID, row.inspectionId)
)

// In InspectionDetailActivity after delete:
setResult(RESULT_OK)
finish()
```

The same pattern works for `NewInspectionActivity` — use a separate `newInspectionLauncher` to reload the list after a successful create.

#### Backend — CAP DELETE handler

```javascript
this.on('DELETE', 'Inspections', async (req) => {
    const tx = cds.transaction(req);
    const id = req.params[0].INSPECTION_ID ?? req.params[0];
    await tx.run('DELETE FROM "INSPECTIONS" WHERE "INSPECTION_ID" = ?', [id]);
});
```

`req.params[0]` is `{ INSPECTION_ID: 'uuid' }` for a single-key entity. The `?? req.params[0]` fallback handles cases where CDS passes the raw string instead of an object.

---

### 6l. JSONObject.optString() Returns the String "null" for JSON null

**Problem:** `getInspectionPhoto()` returned a non-null value of length 4 (`"null"`), causing `Base64.decode()` to return garbage instead of a valid bitmap.

**Root Cause:** `JSONObject.optString("PHOTO_DATA")` returns the **string** `"null"` when the JSON value is `null`. This is a documented Java API quirk — `optString` converts `null` to its string representation.

**Fix:** Use `opt()` (returns `Object`) and check explicitly against `JSONObject.NULL`:

```kotlin
val value = JSONObject(body).opt("PHOTO_DATA")
if (value == null || value == JSONObject.NULL || value.toString() == "null") null
else value.toString().takeIf { it.isNotBlank() }
```

> **Rule:** Never use `optString()` to check whether a JSON field is null. Use `opt()` and compare against `JSONObject.NULL`. The `optString(key, "")` overload still returns `""` for missing keys but returns `"null"` for JSON null values — a silent data corruption.

---

### Files Added / Changed in This Phase

| File | Change |
|------|--------|
| [`NewInspectionActivity.kt`](app/src/main/java/com/sap/droidx/ui/NewInspectionActivity.kt) | Camera capture, photo compress/encode, race-condition fix |
| [`InspectionDetailActivity.kt`](app/src/main/java/com/sap/droidx/ui/InspectionDetailActivity.kt) | New — detail view, async photo fetch, bottom back bar, delete with confirmation |
| [`MainActivity.kt`](app/src/main/java/com/sap/droidx/ui/MainActivity.kt) | `InspectionRow`, `InspectionCache`, `detailLauncher`/`newInspectionLauncher` for list refresh |
| [`InspectionRepository.kt`](app/src/main/java/com/sap/droidx/data/InspectionRepository.kt) | `$select` on list (no PHOTO_DATA); `getInspectionPhoto(id)`; `deleteInspection(id)` |
| [`SplashActivity.kt`](app/src/main/java/com/sap/droidx/ui/SplashActivity.kt) | `installNoOpFlowContext()`, `KEY_ONBOARDED` guard |
| [`DroidXApplication.kt`](app/src/main/java/com/sap/droidx/DroidXApplication.kt) | Removed `SDKInitializer.start()` |
| [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) | CAMERA permission, FileProvider, InspectionDetailActivity |
| [`file_paths.xml`](app/src/main/res/xml/file_paths.xml) | New — FileProvider cache-path for camera photos |
| [`activity_inspection_detail.xml`](app/src/main/res/layout/activity_inspection_detail.xml) | New — detail layout with BottomAppBar (back + delete icons) |
| [`detail_row.xml`](app/src/main/res/layout/detail_row.xml) | New — reusable label/value row |
| [`menu_inspection_detail.xml`](app/src/main/res/menu/menu_inspection_detail.xml) | New — delete menu item for BottomAppBar |
| `ForkQA/srv/catalog-service.js` | Fixed INSERT (PHOTO_DATA), Buffer→String on read, added DELETE handler |
| `ForkQA/db/src/tables/INSPECTIONS.hdbtable` | Added `PHOTO_DATA NCLOB` column |
| `ForkQA/srv/catalog-service.cds` | Added `PHOTO_DATA : LargeString` |

---

## 7. Delivery Update Feature

Full create / read / update / delete workflow for delivery records, each with multiple photos and optional barcode scanning for the delivery number.

### 7a. HANA Tables

Two new tables added under `ForkQA/db/src/tables/`:

```sql
-- DELIVERIES.hdbtable
COLUMN TABLE "DELIVERIES" (
    "DELIVERY_ID"     NVARCHAR(36)  NOT NULL DEFAULT SYSUUID,
    "DELIVERY_NUMBER" NVARCHAR(10)  NOT NULL,
    "COMMENTS"        NCLOB,
    "CREATED_BY"      NVARCHAR(100),
    "CREATED_AT"      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "UPDATED_AT"      TIMESTAMP,
    PRIMARY KEY ("DELIVERY_ID")
);

-- DELIVERY_PHOTOS.hdbtable
COLUMN TABLE "DELIVERY_PHOTOS" (
    "PHOTO_ID"    NVARCHAR(36)  NOT NULL DEFAULT SYSUUID,
    "DELIVERY_ID" NVARCHAR(36)  NOT NULL,
    "PHOTO_DATA"  NCLOB         NOT NULL,
    "SEQ"         INTEGER       DEFAULT 0,
    "CREATED_AT"  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY ("PHOTO_ID")
);
```

Photos are stored in a separate table (not as an array in DELIVERIES) so they can be fetched, added, and deleted individually without re-sending the full delivery record.

### 7b. CAP Service Extensions

Added to `catalog-service.cds`:
```cds
entity Deliveries {
    key DELIVERY_ID     : UUID;
        DELIVERY_NUMBER : String(10);
        COMMENTS        : LargeString;
        CREATED_BY      : String(100);
        CREATED_AT      : Timestamp;
        UPDATED_AT      : Timestamp;
}
entity DeliveryPhotos {
    key PHOTO_ID    : UUID;
        DELIVERY_ID : UUID;
        PHOTO_DATA  : LargeString;
        SEQ         : Integer;
        CREATED_AT  : Timestamp;
}
```

`catalog-service.js` handlers:

| Handler | Notes |
|---------|-------|
| `READ Deliveries` (list) | Returns all columns; converts COMMENTS Buffer → string (same NCLOB issue as PHOTO_DATA) |
| `READ Deliveries` (single) | `req.params[0].DELIVERY_ID` pattern; converts COMMENTS |
| `CREATE Deliveries` | Inserts with `SYSUUID` default; returns `201` |
| `UPDATE Deliveries` | PATCH — sets DELIVERY_NUMBER, COMMENTS, UPDATED_AT=NOW() |
| `DELETE Deliveries` | Cascades: deletes DELIVERY_PHOTOS first, then DELIVERIES row |
| `READ DeliveryPhotos` | Parses `$filter=DELIVERY_ID eq 'uuid'` via `req.query.SELECT.where` array scan |
| `CREATE DeliveryPhotos` | Inserts PHOTO_ID (SYSUUID), DELIVERY_ID, PHOTO_DATA, SEQ |
| `DELETE DeliveryPhotos` | Single photo by PHOTO_ID |

**NCLOB Buffer conversion for COMMENTS:**
```javascript
if (row.COMMENTS && Buffer.isBuffer(row.COMMENTS))
    row.COMMENTS = row.COMMENTS.toString('utf8');
```
Applied in both list and single-entity READ handlers — same pattern as PHOTO_DATA in Inspections.

**DeliveryPhotos $filter parsing:**
```javascript
// req.query.SELECT.where is an array like:
// [ { ref: ['DELIVERY_ID'] }, '=', { val: 'uuid...' } ]
const where = req.query?.SELECT?.where || [];
let deliveryId = null;
for (let i = 0; i < where.length - 2; i++) {
    if (where[i]?.ref?.[0] === 'DELIVERY_ID' && where[i+2]?.val) {
        deliveryId = where[i+2].val; break;
    }
}
```

### 7c. Android — DeliveryListActivity + DeliveryFormActivity

**Navigation:** "Deliveries" bottom nav item in `MainActivity` launches `DeliveryListActivity`. Both new activities use `Theme.DroidX.NoActionBar` and `BottomAppBar` for back navigation (consistent with all other screens).

**DeliveryListActivity:**
- `SwipeRefreshLayout` wrapping a `RecyclerView` for pull-to-refresh
- FAB anchored to `BottomAppBar` opens `DeliveryFormActivity` in create mode
- Tapping a row opens `DeliveryFormActivity` in edit mode (passes `EXTRA_ID`)
- Uses `ActivityResultContracts.StartActivityForResult()` — list reloads on `RESULT_OK`

**DeliveryFormActivity:**
- Create mode: no pre-filled data; BottomAppBar shows Save only
- Edit mode: loads delivery + photos on open; BottomAppBar shows Save + Delete
- Delivery number field: `InputFilter.LengthFilter(10)` + digits-only filter
- Scan button calls ZXing `ScanContract` → fills delivery number field on success
- Comments: multiline `TextInputEditText`
- Photos: `RecyclerView` of thumbnails with an X button per photo + "Add Photo" button

**DeliveryRepository.kt methods:**

```kotlin
suspend fun listDeliveries(): List<DeliveryRow>
suspend fun getDelivery(id: String): DeliveryRow
suspend fun getDeliveryPhotos(deliveryId: String): List<DeliveryPhoto>
suspend fun createDelivery(number: String, comments: String, createdBy: String): String  // returns new ID
suspend fun updateDelivery(id: String, number: String, comments: String)
suspend fun deleteDelivery(id: String)
suspend fun addPhoto(deliveryId: String, base64: String, seq: Int)
suspend fun deletePhoto(photoId: String)
```

### 7d. ZXing Barcode Scanning

**Dependency** (`app/build.gradle`):
```groovy
implementation 'com.journeyapps:zxing-android-embedded:4.3.0'
```

**Launcher registration:**
```kotlin
private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
    result.contents?.let { binding.etDeliveryNumber.setText(it) }
}
```

**Launch on button tap:**
```kotlin
binding.btnScan.setOnClickListener {
    scanLauncher.launch(ScanOptions().apply {
        setPrompt("Scan delivery barcode")
        setBeepEnabled(false)
        setOrientationLocked(false)
    })
}
```

The scanned string is placed directly into the delivery number field. The existing 10-character length filter and digit-only filter apply after the scan result is set.

### 7e. Multi-Photo Management

**Data model:**
```kotlin
data class PhotoItem(
    val photoId: String?,   // null = new/unsaved; non-null = already in DB
    val base64: String
)
```

**State in DeliveryFormActivity:**
```kotlin
private val photos = mutableListOf<PhotoItem>()        // currently displayed
private val deletedPhotoIds = mutableListOf<String>()  // removed from UI; DELETE on save
```

**On load (edit mode):** existing photos from `getDeliveryPhotos()` are added as `PhotoItem(photoId, base64)`.

**Add Photo:** camera → `compressBitmap()` → `PhotoItem(null, base64)` appended; adapter notified.

**Remove Photo (X button):**
```kotlin
fun removePhoto(position: Int) {
    val item = photos[position]
    if (item.photoId != null) deletedPhotoIds.add(item.photoId)
    photos.removeAt(position)
    notifyItemRemoved(position)
}
```

**On Save:**
```kotlin
// Edit mode
repo.updateDelivery(id, number, comments)
deletedPhotoIds.forEach { repo.deletePhoto(it) }
photos.filter { it.photoId == null }.forEachIndexed { i, p ->
    repo.addPhoto(id, p.base64, i)
}
```

No photo is deleted from the server until Save — the user can undo a removal simply by not saving.

### 7f. MTAR Deployment on Windows (mbt build Workaround)

**Problem:** `mbt build` on Windows exits with code `0xc0000135` because npm's bundled `make.exe` is a Linux ELF binary.

**Solution:** Pure Node.js MTAR builder at `C:\Temp\build-mtar.js`.

Key facts about the MTAR format:
- An MTAR is a standard ZIP file
- Inner ZIPs (`db/data.zip`, `srv/data.zip`) must also be valid ZIP files with **forward-slash paths**
- `META-INF/MANIFEST.MF` and `META-INF/mtad.yaml` are plain-text entries in the outer ZIP

**Strategy to patch an existing working MTAR:**
1. Read the existing `ForkQA_1.0.1.mtar` (known-good)
2. Extract its inner `db/data.zip`; use `appendFilesToZip()` to append new `.hdbtable` files without recompressing the original content (preserves large `node_modules` folder)
3. Rebuild `srv/data.zip` from the `srv/` directory using `buildZipFromDir()`
4. Reassemble outer MTAR with updated `mtad.yaml` version → `ForkQA_1.0.2.mtar`
5. Deploy: `cf deploy C:/Temp/ForkQA_1.0.2.mtar`

> **PowerShell `ZipFile.CreateFromDirectory` on Windows uses backslash path separators** inside ZIP entries. Cloud Foundry's staging rejects these with "Invalid zip archive" or "Could not get MTA ID from deployment descriptor". Always use a Node.js ZIP writer (which naturally produces forward-slash paths) or `System.IO.Compression.ZipArchive` with explicit `CreateEntry` calls.

### 7g. Consistent BottomAppBar Navigation Pattern

All activities in the app now use a `BottomAppBar` for back navigation instead of a top toolbar back arrow or AppCompat action bar. This ensures visual consistency and avoids the `setSupportActionBar` conflicts described in §6f.

**Requirements for each activity:**

1. `AndroidManifest.xml` — set `android:theme="@style/Theme.DroidX.NoActionBar"` on the activity. Without this, the AppCompat action bar renders on top of the layout regardless of what the layout contains.

2. Layout — wrap content in `CoordinatorLayout`; add `BottomAppBar` at the end with `android:layout_gravity="bottom"`:
   ```xml
   <com.google.android.material.bottomappbar.BottomAppBar
       android:id="@+id/bottomBar"
       android:layout_width="match_parent"
       android:layout_height="wrap_content"
       android:layout_gravity="bottom"
       app:navigationIcon="?attr/homeAsUpIndicator"
       app:navigationContentDescription="Back" />
   ```
   Add `android:paddingBottom="80dp"` and `android:clipToPadding="false"` to the `ScrollView` or `NestedScrollView` so content scrolls fully above the bar.

3. Activity code — wire the navigation click; do NOT call `setSupportActionBar`:
   ```kotlin
   binding.bottomBar.setNavigationOnClickListener { finish() }
   ```

**Activities using this pattern:**

| Activity | Back target |
|----------|-------------|
| `InspectionDetailActivity` | `MainActivity` |
| `NewInspectionActivity` | `MainActivity` |
| `QuestionnaireActivity` | Caller (only active when `fromNav = true`) |
| `DeliveryListActivity` | `MainActivity` |
| `DeliveryFormActivity` | `DeliveryListActivity` |

### Files Added / Changed in This Phase

| File | Change |
|------|--------|
| [`DeliveryListActivity.kt`](app/src/main/java/com/sap/droidx/ui/DeliveryListActivity.kt) | New — list with SwipeRefresh, RecyclerView, FAB, BottomAppBar back |
| [`DeliveryFormActivity.kt`](app/src/main/java/com/sap/droidx/ui/DeliveryFormActivity.kt) | New — create/edit form, barcode scan, multi-photo, delete with confirmation |
| [`DeliveryRepository.kt`](app/src/main/java/com/sap/droidx/data/DeliveryRepository.kt) | New — OkHttp calls for Deliveries + DeliveryPhotos CRUD |
| [`activity_delivery_list.xml`](app/src/main/res/layout/activity_delivery_list.xml) | New — CoordinatorLayout with toolbar, SwipeRefreshLayout, BottomAppBar + FAB |
| [`activity_delivery_form.xml`](app/src/main/res/layout/activity_delivery_form.xml) | New — form layout with barcode scan row, photo RecyclerView, BottomAppBar |
| [`item_delivery.xml`](app/src/main/res/layout/item_delivery.xml) | New — delivery list row card |
| [`item_photo.xml`](app/src/main/res/layout/item_photo.xml) | New — photo thumbnail card with X button |
| [`menu_delivery_form_new.xml`](app/src/main/res/menu/menu_delivery_form_new.xml) | New — Save-only BottomAppBar menu (create mode) |
| [`menu_delivery_form_edit.xml`](app/src/main/res/menu/menu_delivery_form_edit.xml) | New — Save + Delete BottomAppBar menu (edit mode) |
| [`ic_nav_delivery.xml`](app/src/main/res/drawable/ic_nav_delivery.xml) | New — truck icon for bottom nav |
| [`bottom_nav.xml`](app/src/main/res/menu/bottom_nav.xml) | Added `nav_delivery` item |
| [`activity_questionnaire.xml`](app/src/main/res/layout/activity_questionnaire.xml) | Rewritten — CoordinatorLayout + BottomAppBar; removed AppBarLayout |
| [`QuestionnaireActivity.kt`](app/src/main/java/com/sap/droidx/ui/QuestionnaireActivity.kt) | Removed `setSupportActionBar`/`onOptionsItemSelected`; added `bottomBar` back listener |
| [`activity_new_inspection.xml`](app/src/main/res/layout/activity_new_inspection.xml) | Removed AppBarLayout; added BottomAppBar |
| [`NewInspectionActivity.kt`](app/src/main/java/com/sap/droidx/ui/NewInspectionActivity.kt) | Removed `setSupportActionBar`/`onOptionsItemSelected`; added `bottomBar` back listener |
| [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) | Added `NoActionBar` theme to Questionnaire, DeliveryList, DeliveryForm activities |
| [`app/build.gradle`](app/build.gradle) | Added `zxing-android-embedded:4.3.0` |
| `ForkQA/db/src/tables/DELIVERIES.hdbtable` | New HANA table |
| `ForkQA/db/src/tables/DELIVERY_PHOTOS.hdbtable` | New HANA table |
| `ForkQA/srv/catalog-service.cds` | Added Deliveries + DeliveryPhotos entities |
| `ForkQA/srv/catalog-service.js` | Added CRUD handlers for Deliveries + DeliveryPhotos; COMMENTS Buffer→string |
| `C:\Temp\build-mtar.js` | Node.js MTAR builder — workaround for `mbt build` failure on Windows |

---

## 8. Cloud Foundry & BTP Setup Reference

This section is a copy-ready checklist for standing up the same stack in a new BTP trial subaccount.

### 8a. Account & Space Topology

| Item | This Project | Next Project (fill in) |
|------|-------------|------------------------|
| BTP Trial subdomain | `f6c0e9f2trial` | |
| CF org | `f6c0e9f2trial` | |
| CF space | `dev` | |
| CF region / API | `us10-001` / `https://api.cf.us10-001.hana.ondemand.com` | |
| CF login | `cf login -a https://api.cf.us10-001.hana.ondemand.com` | |

The CF app URL pattern is: `https://<subdomain>-<space>-<app-name>.cfapps.<region>.hana.ondemand.com`

Example: `f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001.hana.ondemand.com`

### 8b. HANA Cloud Instance

1. In BTP cockpit → **HANA Cloud** → create a HANA Cloud instance (free tier / trial plan).
2. Make sure the instance is **running** before deploying — HDI container creation fails against a stopped instance.
3. The instance does **not** need to be mapped manually. The MTA deployer creates and binds the HDI container automatically via `com.sap.xs.hdi-container` resource in `mta.yaml`.
4. HDI container service name is auto-generated (`ForkQA-hdi-container`) and visible in **CF → Services → Instances**.

> **Pitfall:** On BTP Trial, HANA Cloud auto-stops after a period of inactivity. Always check the instance is running before `cf deploy`.

### 8c. MTA Configuration (`mta.yaml`)

Minimal working `mta.yaml` for a CAP + HDI deployment with no XSUAA (trial/dummy-auth pattern):

```yaml
_schema-version: "3.1"
ID: ForkQA
version: 1.0.2             # bump on every cf deploy
description: App name

parameters:
  enable-parallel-deployments: true

modules:
  - name: ForkQA-db
    type: hdb
    path: db
    parameters:
      buildpack: nodejs_buildpack
    build-parameters:
      builder: custom
      commands:
        - npm run build      # runs @sap/hdi-deploy inside db/
    requires:
      - name: ForkQA-hdi-container

  - name: ForkQA-srv
    type: nodejs
    path: srv
    parameters:
      buildpack: nodejs_buildpack
      memory: 256M
      disk-quota: 1024M
    build-parameters:
      builder: npm           # runs npm install in srv/
    provides:
      - name: srv-api
        properties:
          srv-url: ${default-url}
    requires:
      - name: ForkQA-hdi-container

resources:
  - name: ForkQA-hdi-container
    type: com.sap.xs.hdi-container
    parameters:
      service: hana
      service-plan: hdi-shared
    properties:
      hdi-service-name: ${service-name}
```

**No `xsuaa` resource** — this project uses `dummy` auth on the backend. The Android app authenticates via Mobile Services OAuth, but the CAP service itself accepts all requests without a JWT. Add an `xsuaa` resource and binding when moving to production.

### 8d. CAP Service Setup (`srv/`)

**`srv/package.json`** — required dependencies:

```json
{
  "dependencies": {
    "@sap/cds": "^8",
    "express": "^4",
    "hdb": "^0.19.0"
  },
  "engines": { "node": ">=18" },
  "scripts": { "start": "node server.js" },
  "cds": {
    "requires": { "db": { "kind": "hana-cloud" } },
    "model": ["catalog-service"]
  }
}
```

- `hdb` (not `@sap/hana-client`) is the HANA driver — lighter and works on BTP trial without extra setup.
- `"kind": "hana-cloud"` tells CDS to use the HANA Cloud dialect (not classic HANA).

**`srv/server.js`** — critical settings:

```javascript
const cds = require('@sap/cds');
const express = require('express');

async function start() {
    cds.env.requires.auth = { kind: 'dummy' };   // no JWT validation (trial only)

    const app = express();
    app.use(express.json({ limit: '10mb' }));     // MUST come before cds.serve()
                                                  // default 100 KB limit breaks photo upload

    await cds.connect.to('db');
    await cds.serve('all').from(cds.model).in(app);

    const port = process.env.PORT || 4004;
    app.listen(port, () => console.log(`listening on ${port}`));
}

start().catch(err => { console.error(err); process.exit(1); });
```

> **`express.json({ limit: '10mb' })`** must be mounted **before** `cds.serve()`. If `cds.serve()` runs first it installs its own body-parser at the default 100 KB limit and your explicit limit has no effect.

### 8e. HDI Database Module (`db/`)

Minimum structure:
```
db/
  package.json           ← { "scripts": { "build": "npm run build" }, "dependencies": { "@sap/hdi-deploy": "^4" } }
  src/
    tables/
      INSPECTIONS.hdbtable
      DELIVERIES.hdbtable
      DELIVERY_PHOTOS.hdbtable
```

**`db/package.json`:**
```json
{
  "dependencies": { "@sap/hdi-deploy": "^4" },
  "scripts": { "build": "npx hdi-build" }
}
```

HDI picks up all `.hdbtable`, `.hdbview`, `.hdbprocedure` files under `src/` automatically. The only requirement is that **primary key columns must be listed first** in the `COLUMN TABLE` DDL.

**HANA DDL checklist for new tables:**
- Use `NVARCHAR` (not `VARCHAR`) for all string columns — HANA is Unicode-native.
- Use `NCLOB` for text longer than ~5000 chars (photos, comments). Remember to convert `Buffer → string` in CAP READ handlers.
- Use `DEFAULT SYSUUID` for UUID primary keys — no need to generate UUIDs in application code.
- Use `DEFAULT CURRENT_TIMESTAMP` for `CREATED_AT`.

### 8f. Mobile Services Configuration

In **BTP Cockpit → Mobile Services → Applications**:

| Setting | This Project | Note |
|---------|-------------|------|
| App ID | `com.sap.forkqa` | Must match `APPLICATION_ID` in `BtpConfig.kt` |
| Security | OAuth 2.0 | Use the "Security" tab |
| OAuth Client ID | `e80a0af5-cc40-471f-...` | Copy to `BtpConfig.OAUTH_CLIENT_ID` |
| OAuth Client Secret | (from Security tab) | Copy to `BtpConfig.OAUTH_CLIENT_SECRET` |
| Redirect URL | `com.sap.forkqa://oauth` | Must match `REDIRECT_URL` in BtpConfig + `AndroidManifest.xml` `<data android:scheme>` |

> **Redirect URL is case-sensitive.** `com.sap.forkqa://oauth` and `com.sap.forkqa://OAuth` are different. The scheme in `AndroidManifest.xml` must match exactly.

The Mobile Services app proxy URL pattern:
```
https://<subdomain>-<space>-<appId-dots-replaced-by-hyphens>.cfapps.<region>.hana.ondemand.com
```
Example: `f6c0e9f2trial-dev-com-sap-forkqa.cfapps.us10-001.hana.ondemand.com`

### 8g. Android `BtpConfig.kt` — Values to Update Per Project

```kotlin
object BtpConfig {
    // 1. Mobile Services app proxy URL (from Mobile Services cockpit — App detail page)
    const val SERVER_URL = "https://<subdomain>-<space>-<app-id-hyphenated>.cfapps.<region>.hana.ondemand.com"

    // 2. App ID registered in Mobile Services
    const val APPLICATION_ID = "com.sap.yourapp"

    // 3. OAuth client ID — from Mobile Services → Security tab
    const val OAUTH_CLIENT_ID = "..."

    // 4. OAuth client secret — from Mobile Services → Security tab
    const val OAUTH_CLIENT_SECRET = "..."

    // 5. XSUAA base URL — from BTP cockpit → Security → Trust Configuration
    //    Pattern: https://<subdomain>.authentication.<region>.hana.ondemand.com
    const val XSUAA_BASE_URL = "https://<subdomain>.authentication.<region>.hana.ondemand.com"

    // 6. These are derived — do not change the pattern
    const val AUTH_URL  = "$SERVER_URL/oauth2/api/v1/authorize"
    const val TOKEN_URL = "$SERVER_URL/oauth2/api/v1/token"
    const val REDIRECT_URL = "com.sap.yourapp://oauth"

    // 7. Direct CF backend URL (from: cf app ForkQA-srv | grep routes)
    //    Pattern: https://<subdomain>-<space>-<module-name>.cfapps.<region>.hana.ondemand.com
    const val FORKQA_BACKEND_URL = "https://..."

    // 8. The @path value from catalog-service.cds: service InspectionService @(path: '/api')
    const val ODATA_SERVICE_PATH = "/api"
}
```

> **Security note:** `OAUTH_CLIENT_SECRET` is embedded in the APK binary. For production, store it in Android Keystore or retrieve it from a secure endpoint after authentication. For BTP Trial development this is acceptable.

### 8h. Deployment Workflow

#### First deployment (new HANA schema):
```bash
# 1. Log in to CF
cf login -a https://api.cf.us10-001.hana.ondemand.com

# 2. Build MTAR (Linux/Mac with mbt installed)
cd ForkQA
mbt build

# 3. On Windows — use the Node.js builder instead (see §7f)
node C:/Temp/build-mtar.js

# 4. Deploy
cf deploy mta_archives/ForkQA_1.0.0.mtar

# 5. Verify
cf apps          # ForkQA-db (stopped after HDI deploy) + ForkQA-srv (running)
cf app ForkQA-srv  # shows route URL
```

#### Subsequent backend-only changes (no new HANA tables):
```bash
cd ForkQA/srv
cf push ForkQA-srv   # re-stages and restarts the Node.js app only; ~30s
```

No MTAR rebuild needed when only `.js` / `.cds` files change.

#### Adding new HANA tables:
1. Add `.hdbtable` file to `db/src/tables/`
2. Add entity to `catalog-service.cds`
3. Add handler to `catalog-service.js`
4. Bump `version` in `mta.yaml`
5. Rebuild MTAR (or use `build-mtar.js`) and `cf deploy`

> **Never run `cf push ForkQA-db`** directly — it is a one-shot HDI deployer, not a long-running app. It runs, deploys HDI artifacts, then exits with code 0. CF marks it as "stopped", which is normal.

### 8i. Verifying the Deployment

```bash
# Check app status
cf apps

# Tail logs (srv is the long-running one)
cf logs ForkQA-srv --recent

# Test an endpoint directly (no auth since dummy mode)
curl https://f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001.hana.ondemand.com/api/Inspections

# Check bound services
cf services
```

Expected `cf apps` output after successful deploy:
```
name          requested state   instances   memory   routes
ForkQA-db     stopped           0/1         256M     (no route)
ForkQA-srv    started           1/1         256M     f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001...
```

`ForkQA-db` always shows `stopped` after HDI deployment — this is normal.

### 8j. New Project Checklist

- [ ] BTP Trial subaccount created, CF space `dev` exists
- [ ] HANA Cloud instance created and **started**
- [ ] `cf login` successful, correct org and space targeted
- [ ] Mobile Services app created with correct App ID
- [ ] OAuth 2.0 security configured, client ID + secret copied to `BtpConfig.kt`
- [ ] Redirect URL `com.sap.<appid>://oauth` added in Mobile Services AND in `AndroidManifest.xml`
- [ ] `BtpConfig.kt` — all 8 values updated (SERVER_URL, APPLICATION_ID, CLIENT_ID, CLIENT_SECRET, XSUAA_BASE_URL, REDIRECT_URL, FORKQA_BACKEND_URL, ODATA_SERVICE_PATH)
- [ ] `srv/server.js` has `express.json({ limit: '10mb' })` before `cds.serve()`
- [ ] `mta.yaml` ID and module names updated to new project name
- [ ] First `cf deploy` succeeded — `cf apps` shows ForkQA-srv started
- [ ] Backend URL verified with `curl` before building APK
- [ ] Android app ID in `build.gradle` matches Mobile Services App ID scheme (`com.sap.<appid>`)
- [ ] `<data android:scheme="com.sap.<appid>" android:host="oauth" />` in `AndroidManifest.xml` matches `REDIRECT_URL`
