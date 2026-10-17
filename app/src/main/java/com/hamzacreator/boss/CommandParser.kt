package com.hamzacreator.boss

/**
 * Parses the user's ORIGINAL spoken/typed query (Urdu or English) into a
 * phone-control action. Unlike Gemini's draft (which parsed Gemini's own
 * reply and had empty execute lambdas), every action here is WIRED to
 * BossAccessibilityService.getInstance() and really performs the tap /
 * type / launch / scroll / back / home.
 */
enum class ActionType { NONE, OPEN_APP, TAP, TYPE, SCROLL, BACK, HOME }

data class BossAction(
    val type: ActionType,
    val value: String,
    val spokenReply: String
) {
    /** Executes against the running accessibility service. */
    fun executeOn(svc: BossAccessibilityService): Boolean {
        return try {
            when (type) {
                ActionType.OPEN_APP -> svc.launchApp(value)
                ActionType.TAP -> {
                    val node = svc.findNodeByText(value)
                    node != null && svc.tapNode(node)
                }
                ActionType.TYPE -> {
                    val node = svc.findEditableNode()
                    node != null && svc.typeText(node, value)
                }
                ActionType.SCROLL -> svc.scrollForward()
                ActionType.BACK -> svc.goBack()
                ActionType.HOME -> svc.goHome()
                ActionType.NONE -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}

object CommandParser {

    /** App name (Urdu/English) -> package name. */
    private val appPackages = mapOf(
        "whatsapp" to "com.whatsapp",
        "watsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "utube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "gmail" to "com.google.android.gm",
        "mail" to "com.google.android.gm",
        "camera" to "com.android.camera",
        "camra" to "com.android.camera",
        "phone" to "com.android.dialer",
        "dialer" to "com.android.dialer",
        "call" to "com.android.dialer",
        "settings" to "com.android.settings",
        "setting" to "com.android.settings",
        "maps" to "com.google.android.apps.maps",
        "map" to "com.google.android.apps.maps",
        "tiktok" to "com.zhiliaoapp.musically",
        "tik tok" to "com.zhiliaoapp.musically",
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "gallery" to "com.google.android.apps.photos",
        "photos" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "playstore" to "com.android.vending",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "files" to "com.google.android.apps.nbu.files",
        "spotify" to "com.spotify.music",
        "telegram" to "org.telegram.messenger",
        "capcut" to "com.lemon.lvoverseas",
        "alight" to "com.alightcreative.motion"
    )

    private val openWords = listOf("open", "khol", "kholo", "khol do", "launch", "chalao", "chala do")
    private val tapWords = listOf("tap", "click", "press", "dabao", "daba do", "daba", "touch")
    private val typeWords = listOf("type", "likho", "likh do", "likh", "enter")

    /**
     * Parses the raw user query. Returns ActionType.NONE when the query is
     * NOT a phone command — the caller then treats it as a conversational
     * question for Gemini.
     */
    fun parse(rawQuery: String): BossAction {
        val q = rawQuery.lowercase().trim()

        // BACK / HOME / SCROLL first (single-word commands)
        if (q.contains("peeche") || q == "back" || q.contains("go back") || q.contains("wapas")) {
            return BossAction(ActionType.BACK, "", "Peeche ja raha hoon.")
        }
        if (q == "home" || q.contains("go home") || q.contains("home jao")) {
            return BossAction(ActionType.HOME, "", "Home par ja raha hoon.")
        }
        if (q.contains("scroll") || q.contains("neeche") || q.contains("upar karo") ||
            q.contains("swipe up") || q.contains("neeche karo")
        ) {
            return BossAction(ActionType.SCROLL, "", "Scroll kar raha hoon.")
        }

        // OPEN_APP: "open whatsapp" / "whatsapp kholo"
        if (openWords.any { q.contains(it) }) {
            for ((name, pkg) in appPackages) {
                if (q.contains(name)) {
                    return BossAction(ActionType.OPEN_APP, pkg, "$name khol raha hoon.")
                }
            }
            // "khol do" with no recognized app — still not conversational
            val rest = stripWords(q, openWords)
            if (rest.isNotEmpty()) {
                return BossAction(ActionType.OPEN_APP, "", "Ye app nahi mil saki: $rest")
            }
        }

        // TAP: "tap search" / "search dabao"
        if (tapWords.any { q.contains(it) }) {
            val target = stripWords(q, tapWords).trim()
            if (target.isNotEmpty()) {
                return BossAction(ActionType.TAP, target, "$target daba raha hoon.")
            }
        }

        // TYPE: "type hello" / "hello likho"
        if (typeWords.any { q.contains(it) }) {
            val text = stripWords(q, typeWords).trim()
            if (text.isNotEmpty()) {
                return BossAction(ActionType.TYPE, text, "Likh raha hoon: $text")
            }
        }

        return BossAction(ActionType.NONE, "", "")
    }

    private fun stripWords(q: String, words: List<String>): String {
        var out = " $q "
        // Longest first so "khol do" strips before "khol".
        for (w in words.sortedByDescending { it.length }) {
            out = out.replace(" $w ", " ")
        }
        return out.trim()
    }
}
