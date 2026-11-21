# Strafunk

Pelacak jalan, lari & sepeda untuk Android (Kotlin + Jetpack Compose).

**Download APK terbaru:** [Releases › Latest](https://github.com/XFORBREAKFAST/strafunk/releases/latest)
App juga ngecek update sendiri. Update cukup install di atas versi lama, nggak perlu uninstall.

## Fitur
- Rekam jalan/lari/sepeda: GPS (gabungan GPS + WiFi + seluler), tetap jalan saat layar mati
- Peta 3D dengan garis rute animasi + peta asli (OpenStreetMap gelap)
- Notifikasi live (km, waktu, pace) + pop-up tiap kilometer
- Langkah harian dari Health Connect (Samsung Health), riwayat per hari
- Akun, sinkron cloud, Feed teman, kudos, Ranking, Duel langkah mingguan
- Live run: lari Public/Private, gabung lari orang sekitar, battle live

## Struktur
- `app/` — source app Android
- `signing/debug.keystore` — kunci tanda tangan tetap (jangan diganti, biar update nggak perlu uninstall)
- `tools/apkcert.py` — cek tanda tangan APK di CI
- `.github/workflows/build.yml` — build otomatis: push ke `main` → rilis "Latest"

Versi web lama (Capacitor) diarsipkan di branch `web-lama`.
