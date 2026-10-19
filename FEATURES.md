# Daftar Fitur WatchLink

Cakupan fitur WatchLink untuk Huawei Watch Fit 4 dan jam Huawei/Honor lain yang lebih baru.

> **Status:** semua fitur di bawah sudah ada di kode, tetapi **belum di-build dan belum diuji di jam asli**.
> Fitur bertanda *tergantung jam* hanya muncul kalau jam melaporkan dukungannya saat tersambung.

**Keterangan status:**

| Status | Arti |
|---|---|
| **Termasuk** | Aktif di WatchLink |
| **Tergantung jam** | Aktif kalau jam mendukung |
| **Dimatikan** | Ada di kode tapi sengaja dimatikan (bukan MVP); bisa diaktifkan lagi lewat satu flag di `WatchLinkScope.kt` |
| **Belum dibuat** | Direncanakan, belum dikerjakan |
| **Tidak tersedia** | Tidak didukung untuk jam Huawei di aplikasi ini |
| **Tidak mungkin** | Diblokir oleh Huawei atau di luar kendali aplikasi |

---

## 1. Fitur yang termasuk

### Koneksi & perangkat

| Fitur | Status | Catatan |
|---|---|---|
| Pairing jam Huawei/Honor | Termasuk | Lewat Bluetooth Classic; tidak perlu aplikasi Huawei Health |
| Pairing tanpa reset pabrik | Termasuk | Masukkan Huawei account ID (`huid`) saat pairing |
| Info perangkat & baterai | Termasuk | |
| Sinkron waktu | Termasuk | |
| Hanya jam Huawei/Honor yang dikenali | Termasuk | Perangkat merek lain dan earbuds (FreeBuds) diabaikan saat scan |

### Notifikasi

| Fitur | Status | Catatan |
|---|---|---|
| Teruskan notifikasi HP ke jam | Termasuk | |
| Balasan cepat (canned replies) | Tergantung jam | |
| Hapus notifikasi otomatis di jam | Tergantung jam | |
| Gambar di notifikasi | Tergantung jam | |
| Jangan Ganggu (DND) | Tergantung jam | |
| Peringatan saat Bluetooth terputus | Tergantung jam | |

### Musik

| Fitur | Status | Catatan |
|---|---|---|
| Kontrol musik HP dari jam | Tergantung jam | |

### Monitoring kesehatan (dengan grafik)

| Monitoring | Status | Catatan |
|---|---|---|
| Aktivitas & tidur (ringkasan harian) | Termasuk | |
| Langkah | Termasuk | Harian, mingguan, bulanan |
| Kalori aktif | Termasuk | |
| Jarak & speed zones | Termasuk | |
| VO2 max | Termasuk | |
| Tidur (durasi) | Termasuk | |
| Fase tidur REM/terjaga & skor tidur | Tergantung jam | Perlu TruSleep versi baru (HarmonyOS 5+) |
| Detak jantung | Tergantung jam | |
| SpO2 (oksigen darah) | Tergantung jam | |
| Stres | Tergantung jam | |
| HRV | Tergantung jam | |
| Suhu kulit | Tergantung jam | Perlu sensor suhu |

### Pengukuran otomatis di jam (Device → Settings → Health)

| Pengaturan | Status | Catatan |
|---|---|---|
| Detak jantung otomatis/kontinu, mode real-time | Tergantung jam | |
| Peringatan detak jantung tinggi/rendah | Tergantung jam | |
| SpO2 otomatis & peringatan SpO2 rendah | Tergantung jam | |
| Stres otomatis | Tergantung jam | |
| TruSleep, napas saat tidur, sleep apnea | Tergantung jam | |
| Suhu kulit otomatis | Tergantung jam | |
| Pengingat tidak aktif & activity reminders | Tergantung jam | |
| Aritmia, EKG, kekakuan arteri | Tergantung jam | Juga tergantung negara |

### Olahraga (workout)

| Fitur | Status | Catatan |
|---|---|---|
| Daftar & detail workout | Termasuk | Rute GPS, pace, zona detak jantung |
| Export GPX/FIT manual | Termasuk | |
| Auto-export GPX/FIT ke folder | Termasuk | GPX hanya kalau workout punya titik GPS |
| Upload ke Strava (otomatis & manual) | Termasuk | Butuh Strava API app milik sendiri; belum diuji ke Strava |
| **Video share rute** (MP4 9:16, animasi rute 12 detik + summary 3,7 detik) | Termasuk | Menu ⋮ → Bagikan hasil. Latar peta offline atau pastel; dirender di HP |
| **Kartu share** (PNG) | Termasuk | Latar peta, pastel, atau **transparan** (teks terang/gelap) |
| Sembunyikan 200 m awal & akhir rute saat share | Termasuk | Default aktif, untuk privasi lokasi rumah |
| Kirim rute GPX ke jam | Tergantung jam | |
| Kirim GPS HP ke jam saat olahraga | Tergantung jam | |
| Pengaturan zona detak jantung | Tergantung jam | |
| Mode kursi roda | Tergantung jam | |

### Watch face

| Fitur | Status | Catatan |
|---|---|---|
| Kelola watch face terpasang | Tergantung jam | |
| Instal file `.hwt` dari file picker | Termasuk | **Hanya berhasil di firmware lama** (lihat bagian 4) |

### Dashboard & tampilan

| Fitur | Status | Catatan |
|---|---|---|
| Desain "Soft" (pastel, Poppins, kartu membulat) | Termasuk | Palet baru hanya untuk tema terang |
| Semua line chart berbentuk spline | Termasuk | |
| Baris sparkline: detak jantung, langkah, tidur | Termasuk | Widget dashboard baru |
| Widget gauge: langkah, jarak, waktu aktif, kalori, tidur, skor tidur, detak jantung, SpO2, stres, HRV, VO2 max | Termasuk | |
| Cincin target (Goals) & jam aktivitas 24 jam (Today) | Termasuk | |
| Aksen warna Sage (default) | Termasuk | Aksen lain tetap bisa dipilih |

---

## 2. Fitur yang sengaja dimatikan

Bukan bagian MVP. Kode tetap ada; aktifkan lagi dengan mengubah flag di
[`WatchLinkScope.kt`](app/src/main/java/nodomain/freeyourgadget/gadgetbridge/watchlink/WatchLinkScope.kt).

| Fitur | Flag |
|---|---|
| Cuaca di jam | `WEATHER` |
| Sinkron kalender | `CALENDAR` |
| Kontak | `CONTACTS` |
| Alarm (termasuk smart alarm) | `ALARMS` |
| Navigasi belokan dari Google Maps/OsmAnd | `NAVIGATION` |
| Instal aplikasi jam | `WATCH_APPS` |
| Cari perangkat (find device) | `FIND_DEVICE` |
| Perangkat non-Huawei dan earbuds | Filter di `WatchLinkScope.isSupported` |

---

## 3. Belum dibuat (rencana)

| Fitur | Catatan |
|---|---|
| Watch face foto (Gallery) dari file picker | Cara realistis memasang watch face kustom di firmware terbaru. Butuh satu log Bluetooth dari Huawei Health sebagai acuan. |
| Peringatan firmware & status instal `.hwt` yang jujur | Saat ini progres bisa 100% padahal jam menolak file |
| Tampilan data emosi & sleep apnea | Data sudah disinkron ke database, tapi belum ada grafiknya |
| Strip tanggal mingguan di header dashboard | Seperti desain referensi |
| Palet pastel untuk tema gelap | |
| Sembunyikan sisa menu non-Huawei | Pengaturan merek lain dan integrasi yang tidak relevan |

---

## 4. Tidak tersedia / tidak mungkin

### Tidak didukung untuk jam Huawei di aplikasi ini

Tekanan darah, berat badan & komposisi tubuh, hidrasi, laju napas, body energy, PAI, training load,
training readiness, race prediction, live activity (real-time), dan data sepeda. Widget untuk beberapa
di antaranya ada di daftar widget, tapi akan kosong.

### Tidak mungkin

| Fitur | Alasan |
|---|---|
| Instal watch face `.hwt` sembarang di firmware 6.x / HarmonyOS 6.1 | Jam meminta tanda tangan RSA (`contentSign`) dari server Huawei untuk tiap watch face. Tanpa kunci privat Huawei, tanda tangan itu tidak bisa dibuat. Lihat [Gadgetbridge #6005](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/6005). |
| Watch Face Store Huawei | Butuh akun dan server Huawei |

### Fitur Huawei Health yang tidak dibawa

Sinkron cloud & akun Huawei, dompet/NFC & pembayaran, peta offline (AMap), kursus/program latihan,
iklan, fitur AI Huawei, dan konten berbayar (VIP).
