package com.kiddolock.app.management

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * Manages app whitelist/blacklist for KiddoLock.
 * Controls which apps are allowed to run on the device.
 */
class AppManager(private val context: Context) {

    companion object {
        private const val TAG = "AppManager"
        private const val PREFS_NAME = "kiddolock_app_prefs"
        private const val KEY_BLACKLISTED_APPS = "blacklisted_apps"
        private const val KEY_APP_BLOCKING_ENABLED = "app_blocking_enabled"
        private const val LAUNCHER_CACHE_TTL = 60_000L // 1 minute
    }

    /**
     * Default blacklisted packages — social media, browsers, dating, streaming.
     * These are blocked at Strict protection level.
     */
    private val DEFAULT_BLACKLIST = setOf(
        // File managers (can browse media, install APKs, delete data)
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.sec.android.app.myfiles",          // Samsung My Files
        "com.mi.android.globalFileexplorer",    // Xiaomi File Manager
        "com.huawei.filemanager",
        "com.coloros.filemanager",              // Oppo
        "com.android.fileexplorer",             // generic
        "com.amaze.filemanager",
        "com.alphainventor.filemanager",
        "com.estrongs.android.pop",             // ES File Explorer
        "com.cxinventor.file.explorer",
        "com.simplemobiletools.filemanager.pro",
        "ru.zdevs.zarchiver",
        "com.rhmsoft.fm",

        // Social Media
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca",  // Messenger
        "com.instagram.android",
        "com.twitter.android",
        "com.zhiliaoapp.musically",  // TikTok
        "com.snapchat.android",
        "com.reddit.frontpage",
        "com.tumblr",
        "com.pinterest",

        // Browsers
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser", // Samsung Browser
        "com.opera.browser",
        "com.microsoft.emmx", // Edge
        "com.brave.browser",
        "com.duckduckgo.mobile.android",

        // App Stores & Package Managers (Critical Hardening)
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.samsung.android.settings",
        "com.samsung.android.settings.intelligence",
        "com.google.android.packageinstaller",
        "com.android.vending", // Google Play Store
        "com.sec.android.app.samsungapps", // Galaxy Store
        "com.huawei.appmarket", // Huawei AppMarket
        "com.xiaomi.mipicks", // Xiaomi GetApps

        // Communication (Default blocked to encourage manual allow)
        "com.google.android.gm",
        "com.google.android.calendar",            // Google Calendar
        "com.samsung.android.calendar",           // Samsung Calendar
        "com.android.calendar",                   // Generic Calendar
        "com.microsoft.office.outlook",
        "com.microsoft.office.onenote",           // OneNote
        "com.google.android.keep",                // Google Keep (notes)
        "com.google.android.apps.tasks",          // Google Tasks
        "com.google.android.apps.maps",           // Google Maps (location risk)
        "com.waze",                               // Waze
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",

        // 💳 Payment / Wallet apps (CRITICAL - kids must not have access to money)
        "com.google.android.apps.walletnfcrel", // Google Wallet (modern)
        "com.google.android.apps.wallet",       // Google Wallet (legacy)
        "com.google.android.gms.wallet",
        "com.android.vending.wallet",
        "com.samsung.android.spay",             // Samsung Pay
        "com.samsung.android.samsungpay.gear",
        "com.samsung.android.samsungpaygear",
        "com.paypal.android.p2pmobile",         // PayPal
        "com.bit.android.app",                  // Bit (Israeli payment)
        "com.payboxapp",                        // PayBox (Israeli)
        "com.bnhp.payments.payments",           // Hapoalim Payments
        "com.poalim.bl",                        // Hapoalim Bank
        "com.leumi.leumiwallet",                // Leumi Wallet
        "com.leumi.leumiapp",                   // Leumi Bank
        "com.discount.banking",                 // Discount Bank
        "com.fibi.fibiapp",                     // FIBI Bank
        "com.mizrahi.tefahot.banking",          // Mizrahi Bank
        "com.poalim.business",                  // Poalim Business
        "com.poaliminstitution.dbankrnd",       // Poalim institutional
        "com.android.pay",                      // Generic pay
        "com.amazon.windowshop",                // Amazon Shop
        "com.aliexpress.android",               // AliExpress
        "com.amazon.mShop.android.shopping",    // Amazon Shopping
        "com.ebay.mobile",                      // eBay
        "com.zara",                             // Zara
        "com.next.android",                     // Next
        "com.shein.shein",                      // Shein
        "com.contextlogic.wish",                // Wish
        "com.binance.dev",                      // Crypto
        "com.coinbase.android",                 // Crypto
        "com.kraken.android",                   // Crypto

        // 📹 YouTube + ALL alternative clients (kids try these to bypass parental controls)
        // NOTE: YouTube Kids (com.google.android.apps.youtube.kids) is intentionally NOT here -
        // it is age-appropriate by design and should remain available to children.
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",     // YouTube Music
        "com.google.android.apps.youtube.creator",   // YouTube Studio
        "com.vanced.android.youtube",                // YouTube Vanced (legacy)
        "com.vanced.manager",                        // Vanced Manager
        "app.revanced.android.youtube",              // ReVanced
        "app.rvx.android.youtube",                   // ReVanced eXtended (RVX)
        "app.rvx.android.youtube.music",             // RVX Music
        "org.schabi.newpipe",                        // NewPipe
        "org.schabi.newpipelegacy",                  // NewPipe Legacy
        "io.github.polymeilex.newpipe.fork",         // NewPipe forks
        "InfinityLoop1309.NewPipeEnhanced",          // PipePipe
        "com.github.libretube",                      // LibreTube
        "io.freetubeapp.freetube",                   // FreeTube
        "free.rm.skytube.oss",                       // SkyTube
        "free.rm.skytube.extra",                     // SkyTube Extra
        "org.polymorphicshade.tubular",              // Tubular
        "com.artworkivity.songtube",                 // SongTube
        "com.kapp.youtube.final",                    // SnapTube
        "com.kapp.youtube",                          // SnapTube older
        "com.touchtype.swiftkey.tubeapp",
        "com.dvtonder.chronus.youtube",
        "com.google.android.googlequicksearchbox",   // Google Search

        // 🎬 ALL major streaming services (block by default, parent unlocks if appropriate)
        "com.netflix.mediaclient",                   // Netflix
        "com.netflix.NGP.netflix",                   // Netflix Games
        "com.disney.disneyplus",                     // Disney+
        "com.disney.disneyplus.lite",                // Disney+ Lite
        "com.hbo.hbonow",                            // HBO Now
        "com.hbo.android.app",                       // HBO Go
        "com.wbd.stream",                            // Max (HBO Max)
        "com.wbd.stream.tv",
        "com.amazon.avod.thirdpartyclient",          // Prime Video
        "com.amazon.firetv.youtube",
        "com.apple.atve.androidtv.appletv",          // Apple TV
        "com.apple.android.music",                   // Apple Music
        "com.hulu.plus",                             // Hulu
        "com.peacocktv.peacockandroid",              // Peacock
        "com.cbs.app",                               // Paramount+
        "com.cbs.cbssportsapp",
        "com.discovery.discoveryplus.mobile",        // Discovery+
        "com.discovery.discoveryplus.googleplay",
        "com.crunchyroll.crunchyroid",               // Crunchyroll
        "com.funimation.FunimationNow",              // Funimation
        "com.dazn",                                  // DAZN
        "com.dazn.mobile.app.android",
        "com.fubo.firetv",                           // FuboTV
        "com.fubo.mobile",
        "com.starz.android.starzplay",               // Starz
        "com.showtime.standalone",                   // Showtime
        "com.spotify.music",                         // Spotify (kids music? optional)
        "com.deezer.android.app",                    // Deezer
        "com.soundcloud.android",                    // SoundCloud
        "com.pandora.android",                       // Pandora

        // 🇮🇱 Israeli streaming
        "com.yesplus",                               // Yes+
        "com.yes.plus",
        "com.hot.player",                            // HOT
        "com.cellcom.tv",                            // Cellcom TV
        "com.partner.tv",                            // Partner TV
        "com.sting.app",                             // Sting+
        "com.stingtv.android",                       // StingTV
        "com.idnow.idnowapp",
        "il.co.kan.android",                         // KAN (national)
        "il.co.galatz.gali.app",                     // Galatz
        "il.co.reshet.androidapp",                   // Reshet
        "il.co.keshet.kan",
        "il.co.makotv.makovod",                      // MakoVOD
        "il.co.maariv.maariv",
        "il.co.ynet.ynet",                           // Ynet (parent decides)

        // 🎥 Media players (used for streaming pirated content)
        "org.videolan.vlc",                          // VLC
        "com.mxtech.videoplayer.ad",                 // MX Player free
        "com.mxtech.videoplayer.pro",                // MX Player Pro
        "com.kmplayer",                              // KMPlayer
        "org.xbmc.kodi",                             // Kodi
        "com.plexapp.android",                       // Plex
        "com.plexapp.plex",
        "com.stremio.one",                           // Stremio
        "com.cinema.hd",                             // Cinema HD
        "com.showbox.app",                           // ShowBox
        "com.bsplayer.bspandroid.free",              // BS Player
        "com.inshot.xplayer",                        // X Player
        "video.player.videoplayer",
        "tv.danmaku.bili",                           // BiliBili
        "com.popcorntime.app",                       // Popcorn Time
        "com.megacubo.tv",                           // MegaCubo

        // 📷 Cameras (prevent kids from filling phone with 10GB of photos/videos)
        "com.google.android.GoogleCamera",         // Google Camera
        "com.android.camera",                       // Stock Camera (AOSP)
        "com.android.camera2",                      // Camera 2
        "com.sec.android.app.camera",               // Samsung Camera
        "com.samsung.android.app.camera",
        "com.huawei.camera",                        // Huawei Camera
        "com.miui.camera",                          // Xiaomi MIUI Camera
        "com.oppo.camera",                          // Oppo Camera
        "com.coloros.camera",                       // ColorOS Camera
        "com.vivo.camera",                          // Vivo Camera
        "com.oneplus.camera",                       // OnePlus Camera
        "com.realme.camera",                        // Realme Camera
        "com.tcl.camera",                           // TCL Camera
        "com.lge.camera",                           // LG Camera
        "com.motorola.camera2",                     // Motorola
        "com.motorola.camera",
        "com.htc.camera",                           // HTC Camera
        "com.android.gallery3d.camera",
        "net.sourceforge.opencamera",               // Open Camera (popular alt)
        "com.almalence.opencam",                    // Open Camera Plus
        "com.cyberlink.youcam.perfect",             // YouCam
        "com.cyberlink.you.camera",
        "com.cyberlink.youperfect",
        "com.cyberlink.actiondirector",
        "com.snowcorp.snow.android",                // SNOW
        "com.linecorp.b612.android",                // B612
        "com.lyrebirdstudio.facelab",
        "com.snapchat.android",                     // Snapchat (already blocked but camera context)
        "com.zhiliaoapp.musically",                 // TikTok (already blocked)
        "us.zoom.videomeetings",                    // Zoom (video chat)
        "com.skype.raider",                         // Skype
        "com.lithium.silnoid",
        "com.frontrow.vlog",
        "com.brutusin.android.camera",
        "com.commonsware.android.camcon",

        // 📱 Social media (additions to existing)
        "com.zhiliaoapp.musically",                  // TikTok
        "com.ss.android.ugc.trill",                  // TikTok Lite
        "com.ss.android.ugc.aweme",                  // Douyin (TikTok China)
        "com.snapchat.android",                      // Snapchat
        "com.instagram.android",                     // Instagram
        "com.instagram.barcelona",                   // Threads
        "com.facebook.katana",                       // Facebook
        "com.facebook.lite",                         // FB Lite
        "com.facebook.orca",                         // Messenger
        "com.facebook.mlite",                        // Messenger Lite
        "com.bereal.ft",                             // BeReal
        "xyz.blueskyweb.app",                        // Bluesky
        "org.joinmastodon.android",                  // Mastodon
        "com.vkontakte.android",                     // VK
        "com.tencent.mm",                            // WeChat
        "com.discord",                               // Discord
        "com.reddit.frontpage",                      // Reddit
        "com.laurencedawson.reddit_sync",            // Reddit Sync
        "ml.docilealligator.infinityforreddit",      // Infinity for Reddit
        "com.rubycell.pianisthd",
        "net.fourchan.app",                          // 4chan
        "sh.whisper",                                // Whisper
        "co.hellomonkey",                            // Monkey
        "com.omegle.app",                            // Omegle
        "com.kik.android",                           // Kik
        
        // 💬 Messengers (kid safety - blocked by default except WhatsApp which is the IL family standard)
        "org.telegram.messenger",                    // Telegram (was in older list, ensure)
        "org.telegram.plus",                         // Telegram X
        "org.thoughtcrime.securesms",                // Signal
        "com.viber.voip",                            // Viber
        "com.imo.android.imoim",                     // Imo
        "com.imo.android.imoimbeta",
        "com.skype.raider",                          // Skype (already added in cameras but ensure)
        "com.tencent.mobileqq",                      // QQ
        "kakao.talk",                                // KakaoTalk
        "jp.naver.line.android",                     // LINE
        "us.zoom.videomeetings",                     // Zoom
        "com.zhiliaoapp.musically",                  // TikTok (already there)
        "com.snapchat.android",                      // Snapchat (already there)
        "com.discord",                               // Discord (already there)
        "com.kik.android",                           // Kik (already, duplicated for safety)
        "im.bclpbkiauz.android",                     // some chat apps
        "com.signal.android",
        "com.zello.android",                         // Zello walkie-talkie
        "com.cyou.privacy.messenger",
        "com.google.android.apps.tachyon",           // Google Duo / Meet
        "com.google.android.apps.googlevoice",       // Google Voice
        // NOTE: WhatsApp (com.whatsapp) intentionally NOT blocked by default - Israeli family standard

        // 🎮 Game streaming + game stores
        "com.valvesoftware.android.steam.community", // Steam
        "com.valvesoftware.steamlink",               // Steam Link
        "com.nvidia.geforcenow",                     // GeForce Now
        "com.microsoft.xcloud",                      // Xbox Cloud Gaming
        "com.epicgames.fortnite",                    // Fortnite
        "com.epicgames.portal",                      // Epic Games
        "com.activision.callofduty.shooter",
        "com.king.candycrushsaga",                   // Candy Crush (parent decides)
        "com.roblox.client",                         // Roblox
        "com.mojang.minecraftpe",                    // Minecraft

        // 🌐 Browsers (additional - some kids try these to bypass)
        "org.torproject.torbrowser",                 // Tor
        "us.spotco.fennec_dos",                      // Mull
        "com.yandex.browser",                        // Yandex
        "com.yandex.browser.lite",
        "com.uc.browser.en",                         // UC Browser
        "com.uc.browser.us",
        "com.dolphin.browser.express.web",           // Dolphin
        "com.cloudmosa.puffinFree",                  // Puffin
        "org.adblockplus.browser",                   // ABP Browser
        "io.kiwibrowser.browser",                    // Kiwi (Chrome variant with extensions)

        // 🛠️ Sideload / mod tools (THE kids' bypass arsenal)
        "org.fdroid.fdroid",                         // F-Droid
        "com.aurora.store",                          // Aurora Store
        "com.apkmirror.helper",                      // APKMirror
        "com.apkpure.aegon",                         // APKPure
        "moe.shizuku.privileged.api",                // Shizuku
        "com.aefyr.sai",                             // Split APKs Installer
        "com.mp4parser.muxer",
        "com.androidplus.app",
        "com.uptodown",                              // Uptodown
        "com.mobilesecurity.android.installer",
        "com.android.vending.expansion.downloader",  // OBB downloaders

        // 🔞 Adult / inappropriate content
        "com.ph.app",                                // Pornhub
        "com.xhamster.app",                          // xHamster
        "com.youporn.android",
        "com.brazzers.android",
        "com.pornhub.android",

        // Original entry kept for historical reference
        "com.google.android.googlequicksearchbox",   // (duplicate ok)

        // Media & Files
        "com.google.android.apps.photos",
        "com.sec.android.gallery3d",
        "com.android.gallery3d",
        "com.android.gallery",
        "com.miui.gallery",
        "com.huawei.photos",
        "com.coloros.gallery",
        "com.oneplus.gallery",
        "com.google.android.apps.docs",
        "com.microsoft.skydrive", // OneDrive

        // Dating
        "com.tinder",
        "com.bumble.app",
        "com.hinge.app",
        "com.match.android.matchmobile",
        "com.okcupid.okcupid",
        "com.badoo.mobile",
        "com.grindr.android",

        // Video Streaming (non-educational)
        "tv.twitch.android.app",

        // More Social & Messaging
        "com.linkedin.android",
        "org.telegram.messenger",
        "video.likee",
        "com.kwai.video",

        // Content
        "com.imgur.mobile",
        "com.ninegag.android.app"
    )

    // System apps that must NEVER be blocked (even by the parent)
    val CORE_SYSTEM_WHITELIST = setOf(
        "com.android.systemui",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.kiddolock.app",
        "android",
        "com.google.android.apps.wellbeing",
        "com.google.android.permissioncontroller",
        "com.android.settings.intelligence"
    )

    private val launcherPackages = HashSet<String>()
    private var lastLauncherUpdate = 0L

    // TIER 1 - ALWAYS ALLOWED apps. Even during bedtime and after daily limit.
    // These are safety-critical (emergency calls, family contact, app itself).
    val ESSENTIAL_APPS_WHITELIST = setOf(
        // Dialer (essential for emergency calls)
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.phone",
        // Contacts
        "com.android.contacts",
        "com.google.android.contacts",
        "com.samsung.android.app.contacts",
        // SMS / Messages (built-in)
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        // WhatsApp - the Israeli family standard
        "com.whatsapp",
        // KiddoLock itself (cannot block ourselves)
        "com.kiddolock.app"
    )

    // TIER 2 - KID-FRIENDLY apps. Allowed in Kids Mode (not on blacklist), but STILL respect
    // bedtime and daily limit. So YouTube Kids works during the day, but bedtime locks it.
    val KIDS_FRIENDLY_WHITELIST = setOf(
        "com.google.android.apps.youtube.kids",
        "com.google.android.apps.kids.familylink"
    )

    // Active blacklist (user-configurable)
    private val blacklistedApps = HashSet<String>()
    private var blockingEnabled = false

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val isBlacklisted: Boolean,
        val isSystemProtected: Boolean
    )

    /**
     * Initialize the app manager.
     */
    fun initialize() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        blockingEnabled = true // Always enabled now that master switch is removed

        // Migration: V5 (Expand blacklist with stores, YouTube, and Gallery)
        val blacklistVersion = prefs.getInt("blacklist_version", 0)
        if (blacklistVersion < 5) {
            // Explicitly add new defaults for any user upgrading to V5
            blacklistedApps.add("com.android.vending")
            blacklistedApps.add("com.sec.android.app.samsungapps")
            blacklistedApps.add("com.huawei.appmarket")
            blacklistedApps.add("com.xiaomi.mipicks")
            blacklistedApps.add("com.google.android.googlequicksearchbox")
            blacklistedApps.add("com.google.android.youtube")
            // YouTube Kids removed - it's safe for children by design
            blacklistedApps.add("com.google.android.apps.photos")
            blacklistedApps.add("com.sec.android.gallery3d")
            blacklistedApps.add("com.google.android.apps.docs")
            saveBlacklist()

            prefs.edit().putInt("blacklist_version", 5).apply()
        }

        // Migration: V6 (Expand blacklist with all browsers and settings intelligence)
        if (blacklistVersion < 6) {
            blacklistedApps.add("org.mozilla.firefox")
            blacklistedApps.add("com.sec.android.app.sbrowser")
            blacklistedApps.add("com.opera.browser")
            blacklistedApps.add("com.microsoft.emmx")
            blacklistedApps.add("com.brave.browser")
            blacklistedApps.add("com.duckduckgo.mobile.android")
            blacklistedApps.add("com.android.settings") 
            blacklistedApps.add("com.samsung.android.settings") 
            blacklistedApps.add("com.android.settings.intelligence")
            blacklistedApps.add("com.samsung.android.settings.intelligence")
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 6).apply()
        }

        // Migration: V7 (Forced Hardening - Correcting previous version oversights)
        if (blacklistVersion < 7) {
            val critical = listOf(
                "com.android.settings",
                "com.samsung.android.settings",
                "com.android.settings.intelligence",
                "com.samsung.android.settings.intelligence",
                "com.android.chrome",
                "org.mozilla.firefox"
            )
            critical.forEach { blacklistedApps.add(it) }
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 7).apply()
            Log.i(TAG, "Migration V7: Hardened critical app list")
        }

        // Migration: V8 (Gmail & Uninstallation Hardening)
        if (blacklistVersion < 8) {
            val critical = listOf(
                "com.google.android.gm",
                "com.google.android.packageinstaller",
                "com.android.packageinstaller",
                "com.samsung.android.packageinstaller",
                "com.android.settings",
                "com.samsung.android.settings"
            )
            critical.forEach { blacklistedApps.add(it) }
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 8).apply()
            Log.i(TAG, "Migration V8: Hardened Gmail and Uninstallation guards")
        }

        // Migration: V9 (OneDrive Hardening)
        if (blacklistVersion < 9) {
            blacklistedApps.add("com.microsoft.skydrive")
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 9).apply()
            Log.i(TAG, "Migration V9: Hardened OneDrive")
        }
        // Migration: V10 (Comprehensive Gallery & Media Hardening)
        if (blacklistVersion < 10) {
            val galleries = listOf(
                "com.google.android.apps.photos",
                "com.sec.android.gallery3d",
                "com.android.gallery3d",
                "com.android.gallery",
                "com.miui.gallery",
                "com.huawei.photos",
                "com.coloros.gallery",
                "com.oneplus.gallery",
                "com.google.android.gm",
                "com.microsoft.skydrive"
            )
            galleries.forEach { blacklistedApps.add(it) }
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 10).apply()
            Log.i(TAG, "Migration V10: Performed comprehensive Gallery & Media hardening")
        }

        // Migration: V11 (Wallet, Calendar, RVX YouTube alts, Streaming, Social media, Sideload tools)
        if (blacklistVersion < 11) {
            val before = blacklistedApps.size
            blacklistedApps.addAll(DEFAULT_BLACKLIST)
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 11).apply()
            Log.i(TAG, "Migration V11: Added ${blacklistedApps.size - before} new defaults")
        }

        // Migration: V12 (Cameras - prevent kids from filling phone with photos/videos)
        if (blacklistVersion < 12) {
            val before = blacklistedApps.size
            blacklistedApps.addAll(DEFAULT_BLACKLIST)
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 12).apply()
            Log.i(TAG, "Migration V12: Camera apps added (delta=${blacklistedApps.size - before})")
        }

        // Migration: V13 (Messengers - except WhatsApp)
        if (blacklistVersion < 13) {
            val before = blacklistedApps.size
            blacklistedApps.addAll(DEFAULT_BLACKLIST)
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 13).apply()
            Log.i(TAG, "Migration V13: Messengers added (delta=${blacklistedApps.size - before})")
        }

        // Migration: V14 (File managers - prevent child from browsing media, sideloading APKs)
        if (blacklistVersion < 14) {
            val before = blacklistedApps.size
            blacklistedApps.addAll(DEFAULT_BLACKLIST)
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 14).apply()
            Log.i(TAG, "Migration V14: File managers added (delta=${blacklistedApps.size - before})")
        }

        // Migration: V15 - Remove YouTube Kids from blacklist (was wrongly added in earlier versions).
        // YouTube Kids is designed for children with parental controls built in - safe by default.
        if (blacklistVersion < 15) {
            val removed = blacklistedApps.remove("com.google.android.apps.youtube.kids")
            blacklistedApps.remove("co.uk.youtube.kids.client")  // alternate kids client - also safe
            if (removed) saveBlacklist()
            prefs.edit().putInt("blacklist_version", 15).apply()
            Log.i(TAG, "Migration V15: YouTube Kids unblocked (removed=$removed)")
        }

        // Load saved blacklist BEFORE V16 so the migration can re-add missing critical apps
        val savedBlacklist = prefs.getStringSet(KEY_BLACKLISTED_APPS, null)
        blacklistedApps.clear()
        if (savedBlacklist != null) {
            blacklistedApps.addAll(savedBlacklist)
        } else {
            // First run or post-migration: use defaults
            blacklistedApps.addAll(DEFAULT_BLACKLIST)
            saveBlacklist()
        }

        // Migration: V16 - FORCE re-add critical messaging / social apps even if the parent
        // previously removed them. These are apps where children can talk to strangers, receive
        // inappropriate content, leak personal info, or contact estranged parents without supervision.
        // Per user request - these must be blocked by default on every install / upgrade.
        // WhatsApp intentionally excluded - it's the Israeli family standard for parent-child communication.
        if (blacklistVersion < 16) {
            val mandatoryBlocks = setOf(
                // Messengers - high-risk for kids talking to strangers
                "org.telegram.messenger",
                "org.telegram.messenger.web",   // Telegram Web/X variant (multi-account)
                "org.telegram.messenger.beta",  // Telegram Beta
                "org.telegram.plus",
                "ru.telegram.alpha",            // Plus messenger alt
                "uz.unnarsx.cherrygram",        // CherryGram (Telegram mod)
                "org.thunderdog.challegram",    // Telegram X (alternative client)
                "ir.ilmili.telegraph",          // Telegraph (Telegram mod)
                "org.telegrampro.messenger",    // Telegram Pro
                "org.thoughtcrime.securesms",  // Signal
                "com.discord",
                "com.viber.voip",
                "com.imo.android.imoim",
                "com.imo.android.imoimbeta",
                "com.skype.raider",
                "com.tencent.mobileqq",
                "jp.naver.line.android",  // LINE
                "kakao.talk",
                "com.google.android.apps.tachyon",  // Google Meet/Duo
                "com.kik.android",
                "sh.whisper",
                "co.hellomonkey",
                "com.omegle.app",
                // Social - direct messages with strangers
                "com.snapchat.android",
                "com.instagram.android",
                "com.instagram.barcelona",  // Threads
                "com.zhiliaoapp.musically",  // TikTok
                "com.facebook.orca",  // Messenger
                "com.facebook.mlite",
                "com.twitter.android",
                "com.reddit.frontpage",
                "com.linkedin.android",
                // Dating - never appropriate for kids
                "com.tinder",
                "com.bumble.app",
                "com.hinge.app",
                "com.grindr.android",
                "com.badoo.mobile"
            )
            val before = blacklistedApps.size
            blacklistedApps.addAll(mandatoryBlocks)
            val added = blacklistedApps.size - before
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 16).apply()
            Log.i(TAG, "Migration V16: Force re-added ${added} critical messaging/social apps (Telegram et al.)")
        }

        // Migration: V17 - Telegram variants discovered in field (e.g. org.telegram.messenger.web
        // installed via APK / 3rd-party stores). These were missed by V16 because the user already
        // had blacklist_version >= 16. Force-add them and any other newly-discovered messaging variants.
        if (blacklistVersion < 17) {
            val newVariants = setOf(
                "org.telegram.messenger.web",     // Telegram Web/X (multi-account, user reported)
                "org.telegram.messenger.beta",    // Telegram Beta
                "ru.telegram.alpha",              // Plus alt
                "uz.unnarsx.cherrygram",          // CherryGram mod
                "org.thunderdog.challegram",      // Telegram X
                "ir.ilmili.telegraph",            // Telegraph mod
                "org.telegrampro.messenger",      // Telegram Pro
                "com.nicegram.app",               // Nicegram (Telegram client with extra features)
                "com.iMe.android"                 // iMe Messenger (Telegram-based)
            )
            val before = blacklistedApps.size
            blacklistedApps.addAll(newVariants)
            val added = blacklistedApps.size - before
            saveBlacklist()
            prefs.edit().putInt("blacklist_version", 17).apply()
            Log.i(TAG, "Migration V17: Added ${added} Telegram variants (Web/X/mods)")
        }

        Log.i(TAG, "App Manager initialized: ${blacklistedApps.size} apps blacklisted, blocking=${blockingEnabled}")
    }

    /**
     * Check if a package is a browser.
     */
    fun isBrowser(packageName: String): Boolean {
        try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://www.google.com"))
            val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                pm.queryIntentActivities(intent, flags)
            }
            return resolveInfos.any { it.activityInfo.packageName == packageName }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Check if a package is a launcher (home screen).
     */
    fun isLauncher(packageName: String): Boolean {
        if (CORE_SYSTEM_WHITELIST.contains(packageName)) return true
        
        val now = System.currentTimeMillis()
        if (launcherPackages.isEmpty() || now - lastLauncherUpdate > LAUNCHER_CACHE_TTL) {
            updateLauncherCache()
        }
        
        return launcherPackages.contains(packageName)
    }

    private fun updateLauncherCache() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.addCategory(android.content.Intent.CATEGORY_HOME)
            val pm = context.packageManager
            val flags = PackageManager.MATCH_DEFAULT_ONLY
            val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                pm.queryIntentActivities(intent, flags)
            }
            
            launcherPackages.clear()
            resolveInfos.forEach { 
                val pkg = it.activityInfo.packageName
                // PRECISION FILTER: A launcher should NOT be a critical blocked app (like Settings)
                // We check basic patterns here to avoid circular dependencies with AppBlockManager
                val isCriticalPattern = pkg.contains("settings", ignoreCase = true) || 
                                      pkg.contains("packageinstaller", ignoreCase = true) ||
                                      pkg.contains("chrome", ignoreCase = true) ||
                                      pkg.contains("browser", ignoreCase = true)
                
                if (!isCriticalPattern) {
                    launcherPackages.add(pkg)
                }
            }
            lastLauncherUpdate = System.currentTimeMillis()
            Log.d(TAG, "Launcher cache updated (filtered): $launcherPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating launcher cache", e)
        }
    }

    /**
     * Check if a package is blacklisted and should be blocked.
     */
    fun isBlacklisted(packageName: String): Boolean {
        if (CORE_SYSTEM_WHITELIST.contains(packageName)) return false
        return blacklistedApps.contains(packageName)
    }

    /**
     * Check if a package is a system-protected app that cannot be toggled.
     */
    fun isSystemProtected(packageName: String): Boolean {
        return CORE_SYSTEM_WHITELIST.contains(packageName)
    }

    /**
     * Add an app to the blacklist.
     */
    fun blacklistApp(packageName: String) {
        if (CORE_SYSTEM_WHITELIST.contains(packageName)) {
            Log.w(TAG, "Cannot blacklist CORE system app: $packageName")
            return
 