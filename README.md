# VaultX 🔒

**VaultX** is a secure, offline-first media vault for Android designed to protect your photos and videos with military-grade encryption. It features a modern, "Photos-style" gallery interface, biometric security, and zero-knowledge privacy.

## ✨ Features

* **🛡️ Military-Grade Encryption:** Files are encrypted using AES-256 and filenames are obfuscated.
* **📶 100% Offline:** No internet permissions required. Your data never leaves your device.
* **👆 Biometric Unlock:** Secure access with Fingerprint or Face Unlock.
* **🖼️ Smart Gallery UI:**
    * Pinch-to-zoom navigation (Tiny, Grid, List views).
    * Date-based grouping (Today, Yesterday, etc.).
    * Smooth swiping media viewer with zoom support.
* **🗑️ Persistent Recycle Bin:** Deleted items are moved to a secure trash folder before permanent deletion.
* **📂 Album Management:** Create custom albums and organize your media.
* **🚫 Screenshot Blocker:** Prevents screen recording and screenshots within the app for maximum privacy.
* **🚀 Efficient Performance:** Handles large imports (1000+ files) without crashing using stream-based encryption.

## 🛠️ Tech Stack

* **Language:** Kotlin
* **UI Toolkit:** Jetpack Compose (Material3)
* **Architecture:** Offline-First, Coroutine-based
* **Libraries:**
    * `androidx.security:security-crypto` (Data Encryption)
    * `io.coil-kt:coil` (Image Loading)
    * `androidx.media3:media3-exoplayer` (Video Playback)
    * `androidx.work:work-runtime-ktx` (Background Import/Export)
    * `org.osmdroid` (Map Visualization)

## 📂 Storage Location

Encrypted files are stored securely in your device's internal storage:
`/storage/emulated/0/DCIM/Vaultx/`

*Note: Files in this folder are encrypted (`.enc`) and cannot be opened by gallery apps or file managers without VaultX.*

## 🚀 Installation

1.  Clone the repository.
2.  Open in **Android Studio**.
3.  Sync Gradle and Run on an Android device (Min SDK 26).

## 🤝 Contributing

Contributions are welcome! Please fork the repository and submit a pull request.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.