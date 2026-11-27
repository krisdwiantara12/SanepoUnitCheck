# Sanepo Unit Check (v1.2.0)

Aplikasi diagnostik hardware, inspeksi unit HP bekas / COD, dan verifikasi sistem Android (`com.sanepo.unitcheck`) dengan dukungan `minSdk = 23` (Android 6.0) hingga `targetSdk = 36` / `compileSdk = 37` (Android 16/17).

- **Developer Utama:** Kris Dwi Antara
- **Tim Pengembang:** Sanepo Team
- **Asal:** Dibuat di Ponorogo, Jawa Timur Indonesia ❤️

## Build & Verifikasi
```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest
```

## Kemampuan Utama (v1.2.0)
1. **Menu About, Settingan Cek Update & Direct In-App Update (`AppUpdateManager` / `AboutActivity`):**
   - Mengecek rilis terbaru langsung dari repositori GitHub resmi (`krisdwiantara12/SanepoUnitCheck`), mengunduh asset `.apk` via HTTPS redirect, memverifikasi header APK (`PK`) & checksum `SHA-256` (bila tersedia di Release), serta meluncurkan installer Android via `FileProvider` + pengecekan `canRequestPackageInstalls()`.
   - Dilengkapi pengaturan **Auto-Cek Update saat Buka Aplikasi** (opsional/opt-in, throttle 12 jam), **Sertakan Pre-release / Beta**, **Fingerprint Sertifikat Signing SHA-256**, ukuran & pembersih cache APK/laporan, serta acuan ambang batas inspeksi lapangan.
2. **Privasi Diagnostik Offline-First:** Seluruh 11 modul diagnostik hardware berjalan offline penuh tanpa mengunggah data perangkat; akses internet (`INTERNET`) hanya dipakai untuk fitur Cek Update di menu About.
3. **Deteksi Identitas OEM, SoC Multi-Cluster & Anti-Fake Spec:** Membaca nama pemasaran OEM, seluruh cluster CPU, benchmark tulis sinkron `RandomAccessFile("rwd")` + cold read, Knox Guard (`kgclient`), paket kredit/PayJoy/MDM, Root, Bootloader, Widevine L1/L3, dan anomali spek.
4. **Suite Uji Hardware Lengkap & Mode COD Cepat (10-Step Wizard):** Display 100% brightness + Gradien RGB + Measured FPS, Touch full-grid + 5s Ghost monitor, 45s Battery Voltage Sag Stress Test, Audio Stereo L/R + Dual-Mic + Haptics, Multi-Lens Camera 0.6x-5x Zoom, Sensor Proximity 2-arah, Cellular `ServiceState` (`IN_SERVICE`), Keys/OTG, Checklist COD Pre-Buy & Kalkulator Harga Nego, serta ekspor TXT, JSON, PDF, QR Verifikasi & Kartu PNG.
