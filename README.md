PartisiDumper ⚡
An advanced, modern Android utility built with Kotlin and Jetpack Compose (Material Design 3 Expressive). It provides a safe, standalone solution to dump live hardware storage partitions and extract official OTA firmware packages directly on your Android device without needing a PC.
💡 What is PartisiDumper?
When modding, rooting, or restoring an Android device, you frequently need raw partition images (such as boot.img for patching Magisk or KernelSU, init_boot.img, vbmeta.img, or vendor_boot.img).
Traditionally, obtaining these files required:
 * Connecting your phone to a computer and running terminal commands, or
 * Downloading multi-gigabyte PC tools like payload-dumper-go to unpack firmware archives.
PartisiDumper brings this entire workflow directly onto your phone with two versatile engines:
 * Hardware Mode (Root): Reads raw flash memory chips (/dev/block) directly from your device to back up your running partitions.
 * OTA Extractor Mode (100% Non-Root): Lets you select any full OTA ROM .zip or payload.bin and extract specific partition images in seconds using random-access file streaming, without extracting the whole multi-gigabyte package or needing root privileges.
✨ Key Features
 * Hybrid Engine (Root & Non-Root): Seamlessly switch between physical hardware block dumping and non-root OTA archive extraction.
 * Zero Storage Permission Hassle: Built in compliance with modern Scoped Storage standards—no intrusive MANAGE_EXTERNAL_STORAGE permission required.
 * Bit-by-Bit Hardware SHA-256 Validation: Automatically computes and matches hardware-level checksums against dumped images to ensure zero data corruption.
 * Smart Partition Filtering: Automatically detects active A/B boot slots and lets you back up all critical boot partitions in a single tap while ignoring raw disk blocks.
 * Parallel Random-Access OTA Decompression: Streams and decompresses XZ/Bzip2 payload operations concurrently using multi-worker coroutines.
 * Background WakeLock & Foreground Service: Extraction runs safely inside an Android dataSync foreground service, preventing system sleep or process killing when the screen is turned off.
 * Material Design 3 Expressive (MD3E) UI: Sleek, fluid bottom navigation bar, pill segmented chips, responsive layout, and real-time per-card extraction status.
📖 How to Use
1. Dumping Live Hardware Partitions (Root Required)
 * Launch PartisiDumper and grant Superuser access (KernelSU, APatch, or Magisk).
 * In the Partitions tab, view your automatically detected active slot (_a or _b).
 * Choose your backup mode:
   * Backup Critical: Instantly backs up vital boot chain partitions (boot, init_boot, vbmeta, etc.).
   * Single Partition: Tap the download icon on any specific partition card.
   * Select All / Multi-Select: Tap individual cards or choose Select All to perform custom batch backups.
 * Output .img and checksum .sha256 files will be saved directly to Download/PartisiDumper/Dump/.
2. Extracting OTA Firmware (No Root Required)
 * Switch to the OTA Payload tab from the bottom navigation bar.
 * Tap Select File and pick an official OTA update file (payload.bin or full ROM .zip) via the system document picker.
 * PartisiDumper will parse the package structure and display the available partitions with their actual uncompressed file sizes.
 * Select the partitions you want (e.g., boot, init_boot, or recovery) and tap Extract Partitions.
 * Extracted images and their checksums are saved into dedicated subfolders under Download/PartisiDumper/<FirmwareName>/.
3. Verification & History
 * Navigate to the History tab at any time.
 * Switch between Partition Dump and OTA Extract categories.
 * Tap Verify on any file card to validate the integrity of the image against its SHA-256 hash record.
🛠️ System Requirements
 * Android Version: Android 11+ (API 30 to Android 15+).
 * Root Solution (Hardware Dump Only): KernelSU, APatch, or Magisk.
 * Architecture: ARM64 / AArch64.
