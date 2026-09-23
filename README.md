# PartisiDumper ⚡

Utility Android modern berbasis Root (`libsu`) dan Jetpack Compose (Material 3) untuk mengekstrak (*dump*) partisi storage *raw block* secara aman dengan verifikasi integritas hardware bit-by-bit.

## ✨ Fitur Utama
- **Read-Only Safety:** Eksekusi murni membaca blok hardware (`/dev/block`), tanpa risiko menulis ulang sistem.
- **Hardware-Level SHA-256 Validation:** Otomatis membandingkan hash blok fisik dengan hash file output `.img`.
- **Batch Backup Partisi Kritis:** Ekstrak semua partisi penting (`boot`, `init_boot`, `vbmeta`, `dtbo`, dll.) di slot aktif dalam sekali klik.
- **Real-Time Progress Tracking:** Pantau proses penyalinan partisi secara akurat.
- **Background WakeLock:** Eksekusi aman di background tanpa risiko terhenti di tengah jalan.
- **Modern Material 3 UI:** Tampilan responsif dengan dukungan edge-to-edge.

## 🛠️ Persyaratan
- Android 11+ (SDK 30+)
- Akses Root (KernelSU, APatch, atau Magisk)

## 📥 Download
Unduh file APK siap pakai di tab [Releases](https://github.com/kimal404/PartisiDumper-/releases).
