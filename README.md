# 🛡️ VaultX: Next-Gen AI Secure Vault

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple) ![Compose](https://img.shields.io/badge/Jetpack-Compose-green) ![ML Kit](https://img.shields.io/badge/Google-ML_Kit-orange) ![Security](https://img.shields.io/badge/Security-AES256-red)

**VaultX** is not just a gallery lock—it is an intelligent, offline fortress for your digital life. Built entirely with **Kotlin** and **Jetpack Compose**, it leverages on-device AI to organize your secured content without a single byte of data ever leaving your phone.

> **"Privacy is not an option, it's the default."**

---

## 🚀 Key Features

### 🧠 On-Device AI Intelligence
* **Smart Search:** Search for "Green Saree", "Cat", or "ID Card" even if you never named the files. The local neural network scans and tags your encrypted content.
* **Offline Privacy:** All machine learning happens on your device's NPU. No internet required. Zero data leaks.

### ⚡ Turbo Performance
* **Dual-Thumbnail Engine:**
    * *Internal Cache:* Unencrypted thumbnails stored in secure app-private storage for instant 120Hz scrolling.
    * *Encrypted Backup:* Full AES-encrypted thumbnails stored physically for portability.
* **Zero-Lag Grid:** Custom-built lazy loading architecture using Coil and Coroutines.

### 🔒 Military-Grade Security
* **AES-256 Encryption:** Every byte of your photos, videos, and documents is encrypted securely.
* **Screenshot Blocker:** The app renders itself invisible to screen recorders and screenshots.
* **Biometric Unlock:** Seamless Fingerprint and Face ID integration.

### 🛠️ Power Tools
* **Private Browser:** Built-in web browser with AdBlock and a direct-to-vault downloader.
* **Duplicate Finder:** Uses MD5 hashing to find and clean exact duplicates to save space.
* **Recycle Bin:** Safety net for accidental deletions.
* **Universal Media Player:** Built-in support for video playback, audio streaming, and secure PDF rendering.

---

## 🛠️ Tech Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose (Material3)
* **Image Loading:** Coil (Custom Fetchers for Encrypted Content)
* **AI/ML:** Google ML Kit (Image Labeling)
* **Video:** AndroidX Media3 (ExoPlayer)
* **Database:** SQLite (Custom Implementation)
* **Cryptography:** `javax.crypto` (AES/CBC/PKCS5Padding)

---

## 📸 Screenshots

| Secure Grid | AI Search | Private Browser |
|:-----------:|:---------:|:---------------:|
| *(Add screenshots here)* | *(Add screenshots here)* | *(Add screenshots here)* |

---

## 🔧 Installation

1.  Clone the repo:
    ```bash
    git clone [https://github.com/Midxv/VaultX.git](https://github.com/Midxv/VaultX.git)
    ```
2.  Open in **Android Studio**.
3.  Sync Gradle and Run on an Emulator or Real Device (Android 8.0+).

---

## 🤝 Contributing

Contributions are welcome! Please fork the repository and create a pull request with your features or fixes.

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

---

*Made with ❤️ and ☕ by Midxv*