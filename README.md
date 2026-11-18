# Strafunk

Pelacak jalan, lari, dan sepeda. Langkah, jarak, dan rute tetap kerekam walau offline.
Peta, akun, feed, ranking, dan duel jalan saat online.

## Isi repo
```
.github/workflows/build.yml   ← build APK otomatis di GitHub Actions
assets/logo.png               ← ikon aplikasi
native/MainActivity.java      ← daftarin plugin langkah
native/StepCounterPlugin.java ← baca sensor langkah HP (offline)
www/index.html                ← seluruh aplikasi (tampilan + logika)
capacitor.config.json
package.json
```

## Build APK
1. Upload semua file di atas ke repo GitHub baru (path folder harus sama persis).
2. Buka tab **Actions** → workflow "Build Strafunk APK" jalan otomatis (±5–8 menit).
3. Selesai → klik run-nya → bagian **Artifacts** → download **Strafunk-APK** → ekstrak zip → install `Strafunk.apk`.

## Aktifkan akun & fitur sosial (opsional)
1. https://console.firebase.google.com → buat project baru.
2. **Build › Authentication** → Get started → aktifkan **Email/Password**.
3. **Build › Firestore Database** → Create database (mode production).
4. Tab **Rules** di Firestore, ganti isinya dengan ini lalu **Publish**:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && request.auth.uid == uid;
    }
    match /activities/{id} {
      allow read: if request.auth != null;
      allow create: if request.auth != null && request.resource.data.uid == request.auth.uid;
      allow delete: if request.auth != null && resource.data.uid == request.auth.uid;
      allow update: if request.auth != null && (resource.data.uid == request.auth.uid
        || request.resource.data.diff(resource.data).affectedKeys().hasOnly(['kudos']));
    }
    match /daily/{id} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && request.resource.data.uid == request.auth.uid;
    }
  }
}
```
5. **Project settings** (ikon gear) → Your apps → tambah app **Web** (`</>`) → copy nilai `firebaseConfig`.
6. Tempel ke bagian `window.FB_CONFIG` di paling atas `www/index.html`, commit → APK baru otomatis ter-build.

## Cara kerja offline
- **Langkah**: sensor step counter HP. Jalan terus tanpa internet.
- **Jarak & rute**: GPS (nggak butuh internet). Kalau GPS lemah (dalam ruangan/treadmill),
  jarak jalan/lari diperkirakan dari langkah × panjang langkah (atur di Profil).
- Semua aktivitas disimpan di HP, lalu otomatis disinkron ke cloud begitu online & login.
- Tombol back HP: tutup sheet → mundur satu layar → ke Beranda → tekan 2× untuk keluar.
  Saat merekam, back membuka pilihan lanjut/simpan/kecilkan/buang.
