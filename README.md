# Sanepo Unit Check (v1.1.0)

Offline Android unit diagnostic, hardware verification, and COD inspection tool (`com.sanepo.unitcheck`) targeting `minSdk = 23` (Android 6.0) through `targetSdk = 36` / `compileSdk = 37` (Android 16/17).

## Build & Output
```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest
```

## Key Capabilities & Hardening Notes (v1.1.0)
1. **SystemProperties Hidden API & Pipe Safety:** `SystemPropReader` attempts reflection on `android.os.SystemProperties.get(String, String)` and falls back to `getprop <key>` with background stream reading (preventing pipe-buffer deadlocks) and API 23-25 polling timeouts.
2. **Commercial Name, Multi-Cluster SoC & Anti-Fake Spec Check:** Detects OEM marketing names across Samsung/Xiaomi/OPPO/realme/OnePlus/vivo/iQOO/Infinix/Tecno/itel/HONOR/Huawei/Sony, captures all CPU core clusters from `/proc/cpuinfo`, runs cold-read + `RandomAccessFile("rwd")` sync-write storage benchmarks, and flags spec tampering, bootloader unlock, root, Knox Warranty `0x1`, Knox Guard/KG locks, Finance/PayJoy packages, MDM admins, and Widevine L3 downgrades.
3. **45-Second Voltage Sag Battery Stress Test & Unit Quirk Normalization:** `BatteryHardwareReader` normalizes `µA` vs `mA` safely across fast-charging devices (`|raw| > 25_000`) and enforces charging/discharging sign parity. `BatteryDetailActivity` runs an interactive 45s 4-thread CPU + Rear Torch load test to catch failing cells (voltage sag `<3450mV` above 30% or `>3%` drop).
4. **Display, Touch, Audio Stereo, Multi-Lens Zoom & Bidirectional Sensors:**
   - Display forces 100% brightness, adds 10% Dark Grey (`#1A1A1A`) green-tint and RGB+Greyscale gradient banding steps, measures real FPS via `Choreographer`, and gates PASS on >= 4 checked patterns.
   - Touch preserves progress across Grid/Multi/Ghost modes, offers collapsible/movable HUD controls for 100% grid access, and gates PASS on `>=95%` grid + clean 5s Ghost monitor.
   - Audio uses `AtomicLong` generation guards against routing races, phase-continuous sine sweeps with 20ms cosine fade-in/out, API 31+ `setCommunicationDevice`, and Stereo Left/Right separation.
   - Camera exposes Zoom Ratio buttons (`0.6x` to `5x`) for logical multi-camera arrays and gates PASS on Back+Front checks; Sensors require bidirectional `FAR + NEAR` proximity transitions.
5. **Mode COD Cepat (10-Step Wizard), Pre-Buy Safety, Price Calculator & Anti-Tamper Proof:** Persists active inspection state across process death, checks cellular `ServiceState` (`IN_SERVICE` vs `OUT_OF_SERVICE`), includes COD Pre-Buy Checklist + Max-Safe-Buy Calculator, and exports TXT, JSON, PDF, SHA-256 QR verification modal, and shareable Card PNG reports via `FileProvider`.
