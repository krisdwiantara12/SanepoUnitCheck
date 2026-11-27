### Pembaruan Utama v1.3.2 (Auto-OTA & Polish)
- **Otomatisasi Update Tanpa Upload Browser:** Push ke `main` via GitHub Desktop sekarang otomatis menerbitkan GitHub Release lewat GitHub Actions (`.github/workflows/auto-release.yml`) lengkap dengan SHA-256 checksum.
- **Fallback Manifest OTA (`releases/latest.json`):** Aplikasi versi `v1.3.2+` otomatis membaca manifest langsung jika API GitHub Releases belum siap atau terkena limit.
- **Auto-Cek Update Aktif Default (6 Jam):** Pengecekan update otomatis saat aplikasi dibuka aktif secara bawaan agar info versi terbaru langsung muncul.

### UI & Ikonografi (v1.3.1)
- Ikon vektor geometris khusus pada dashboard, daftar modul inspeksi, header perangkat, tombol laporan, dan menu Tentang/Update agar lebih ringkas dan rapi.

### Perbaikan Bug & Fitur Inspeksi COD (v1.3.0)
- **Audio & Mic Test:** Perbaikan jalur rekaman & pemutaran loopback mic yang sebelumnya tidak bersuara, lengkap dengan equalizer/level meter real-time.
- **Cek IMEI Per-Merk & Barcode Scanner:** Kode dialer USSD, menu service QC per OEM (Samsung, Xiaomi/Redmi/POCO, OPPO/realme/OnePlus, vivo/iQOO, Infinix/TECNO/itel, dll.), validasi Luhn, serta scan barcode IMEI dus lewat kamera.
- **Alur COD 90 Detik & Anti-Tipu COD:** Mengganti kalkulator harga manual dengan Checklist Anti-Tipu COD dan Express COD Wizard 90 detik yang menyesuaikan fitur perangkat & versi Android.
- **Layar KW & Sensor Barometer IP68:** Uji multi-touch & sampling rate Hz untuk deteksi panel pengganti/KW, plus uji barometer segel IP68 (hanya muncul bila perangkat memiliki sensor barometer).
- **Kamera & Dual-SIM Data Ping:** Uji autofokus, spot/jamur lensa kamera, detail slot Dual-SIM, dan verifikasi konektivitas data seluler nyata.
