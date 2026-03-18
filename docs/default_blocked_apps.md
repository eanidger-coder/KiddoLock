# KiddoLock: Default Blocked Apps (Source of Truth)

This document lists all applications that are blocked by default in **KiddoLock** when "Kids Mode" is active. These apps are either considered "Critical" (security risks) or "Restricted" (social media, entertainment, etc.).

## 🔐 Critical & System (Hardened)
*These apps are blocked with zero-latency to prevent tampering or uninstallation.*

| App Name | Package ID | Reason |
| :--- | :--- | :--- |
| **Settings** | `com.android.settings` | Prevents disabling the service or removing Device Admin. |
| **Galaxy Settings** | `com.samsung.android.settings` | Specific to Samsung devices. |
| **Package Installer** | `com.google.android.packageinstaller` | Prevents app uninstallation attempts. |
| **Play Store** | `com.android.vending` | Prevents downloading unauthorized apps. |
| **Chrome** | `com.android.chrome` | Default web access (Unfiltered content). |
| **Firefox/Edge/Opera** | `org.mozilla.firefox`, `com.sec.android.app.sbrowser`, etc. | Alternative browsers. |

## 🖼️ Media & Gallery (New in V10)
*Blocked to protect private photos and videos.*

| App Name | Package ID |
| :--- | :--- |
| **Google Photos** | `com.google.android.apps.photos` |
| **Samsung Gallery** | `com.sec.android.gallery3d` |
| **Stock Gallery** | `com.android.gallery3d`, `com.android.gallery` |
| **Xiaomi/Mi Gallery** | `com.miui.gallery` |
| **Huawei/OnePlus/Oppo** | `com.huawei.photos`, `com.oneplus.gallery`, `com.coloros.gallery` |

## 📧 Communication & Storage
*Blocked by default to ensure privacy of emails and documents.*

| App Name | Package ID |
| :--- | :--- |
| **Gmail** | `com.google.android.gm` |
| **Outlook** | `com.microsoft.office.outlook` |
| **OneDrive** | `com.microsoft.skydrive` |
| **Google Drive** | `com.google.android.apps.docs` |

## 📱 Social Media & Entertainment
*Standard distractions blocked at "Strict" level.*

| Category | Typical Packages |
| :--- | :--- |
| **Social** | Facebook, Instagram, TikTok, Snapchat, Reddit, Twitter/X |
| **Video** | YouTube, YouTube Kids, Twitch, Netflix, Disney+ |
| **Dating** | Tinder, Bumble, Hinge, etc. |

---
> [!IMPORTANT]
> This list is enforced during the first activation of Kids Mode and via automatic migrations (Current Version: **V10**). Parents can manually unblock specific apps via the **Admin Dashboard** if needed.
