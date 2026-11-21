# Strafunk

Aplikasi Android buat jalan, lari, dan sepeda. Ditulis native (Kotlin + Jetpack Compose).

- **Offline**: GPS + sensor langkah, peta 3D, notif live, tetap merekam saat layar mati
- **Langkah harian** dari Health Connect (Samsung Health), akurat per hari
- **Online**: akun, sinkron, Feed, Ranking & Duel, peta OpenStreetMap
- **Live run**: lari Public/Private, gabung & battle live

## Download
Halaman **Releases** → rilis bertanda **Latest** → `Strafunk.apk`.
Update cukup di-install di atas versi lama (kunci tanda tangan tetap, dicek otomatis tiap build).

## Struktur
```
app/                      kode aplikasi
signing/debug.keystore    kunci tanda tangan tetap
tools/apkcert.py          cek tanda tangan APK di CI
.github/workflows/        build APK otomatis tiap push
```
Versi web lama (Capacitor) diarsipkan di cabang `web-lama`.
