package com.kiddolock.app.content.core

/**
 * ContentClassifier — Layer 1 of SafeLock content detection engine.
 *
 * Scans video titles, descriptions, and channel names for violent or
 * inappropriate content using weighted keyword matching in multiple languages.
 * Ported from SafeKids (com.safekids.core) as part of the SafeLock merger.
 */
class ContentClassifier {

    data class ContentScore(
        val totalScore: Float,
        val categories: List<CategoryMatch>,
        val isBlocked: Boolean
    )

    data class CategoryMatch(
        val category: Category,
        val score: Float,
        val matchedTerms: List<String>
    )

    enum class Category(val weight: Float, val labelHe: String) {
        VIOLENCE_PHYSICAL(0.9f, "אלימות פיזית"),
        VIOLENCE_VERBAL(0.5f, "אלימות מילולית"),
        HORROR_KIDS(1.0f, "אימה לילדים"),
        ELSAGATE(1.0f, "תוכן מטעה"),
        WEAPONS(0.7f, "נשק"),
        DARK_THEMES(0.85f, "נושאים אפלים");
    }

    // Threshold can be adjusted by parent (strict=0.3, balanced=0.5, relaxed=0.7)
    var blockThreshold: Float = 0.5f

    // Custom keywords added by parents
    private val customBlacklist = mutableSetOf<String>()

    private val keywordDatabase: Map<Category, List<String>> = mapOf(
        Category.VIOLENCE_PHYSICAL to listOf(
            // English
            "fight", "punch", "kick", "hit", "beat", "attack", "smash",
            "destroy", "kill", "murder", "slap", "shoot", "stab", "choke",
            "strangle", "combat", "battle", "war", "wrestle", "knockout",
            "brutal", "violent", "blood", "bleeding", "injury",
            // Hebrew
            "מכות", "הכאה", "נלחם", "נלחמים", "קרב", "מלחמה", "תקיפה",
            "תוקף", "בועט", "מכה", "הורג", "רוצח", "יורה", "דוקר",
            "חונק", "מרביץ", "אלימות", "אלים", "דם", "פציעה",
            "מתאגרף", "אגרוף", "בעיטה", "סטירה", "הרוג", "רצח",
            "פיצוץ", "מפוצץ", "להרוג", "מלחמות", "קרבות", "דם", "פצוע", "גופה", "מת",
            "אלימות בחברה", "קטטה", "דקירות", "דקירה", "מכות רצח", "מרביצים",
            "בועטים", "חונקים", "שורפים", "שריפה", "פיגוע", "טרור",
            // Arabic
            "قتال", "ضرب", "عنف", "حرب"
        ),

        Category.VIOLENCE_VERBAL to listOf(
            "stupid", "idiot", "hate", "shut up", "dumb", "loser",
            "ugly", "fat", "bully", "bullying", "mean", "curse",
            "טיפש", "מטומטם", "שונא", "שתוק", "מכוער", "שמן",
            "בוזז", "בריונות", "בריון", "קללה", "מקלל", "אידיוט",
            "טמבל", "דביל", "חמור"
        ),

        Category.HORROR_KIDS to listOf(
            "huggy wuggy", "poppy playtime", "mommy long legs",
            "catnap", "dogday", "smiling critters",
            "skibidi", "skibidi toilet",
            "siren head", "cartoon cat", "cartoon dog",
            "baldi", "baldi basics",
            "granny", "granny game", "granny horror",
            "garten of banban", "banban",
            "fnaf", "five nights at freddy", "freddy fazbear",
            "jumpscare", "creepypasta", "slenderman",
            "trevor henderson", "backrooms",
            "האגי ואגי", "פופי פלייטיים", "אמא רגליים ארוכות",
            "סקיבידי", "סקיבידי טוילט",
            "ראש סירנה", "חתול מצויר",
            "באלדי", "גראני", "גראני משחק",
            "באנבאן", "גארטן אוף באנבאן",
            "פרדי", "חמש לילות אצל פרדי",
            "ג'אמפסקר", "סלנדרמן"
        ),

        Category.ELSAGATE to listOf(
            "elsa injection", "elsa pregnant", "spiderman pregnant",
            "pregnant elsa", "bad baby", "bad babies",
            "joker prank", "joker vs", "evil elsa",
            "frozen prank", "spiderman poop", "poop challenge",
            "injection challenge", "doctor injection",
            "finger family gone wrong", "wrong heads",
            "learn colors gone wrong", "surprise egg horror",
            "johny johny scary", "cocomelon scary",
            "אלזה הזרקה", "ספיידרמן בהריון", "תינוק רע",
            "ג'וקר נגד", "אלזה רעה", "אתגר קקי"
        ),

        Category.WEAPONS to listOf(
            "gun", "rifle", "pistol", "sword", "knife", "weapon",
            "bomb", "grenade", "missile", "sniper", "shotgun",
            "machine gun", "ak47", "ak-47",
            "אקדח", "רובה", "חרב", "סכין", "נשק", "פצצה",
            "רימון", "טיל", "צלף", "מקלע"
        ),

        Category.DARK_THEMES to listOf(
            "death", "dead", "die", "dying", "funeral", "grave",
            "ghost", "demon", "devil", "hell", "torture",
            "kidnap", "kidnapping", "suicide", "poison",
            "nightmare", "scared", "terrified", "horror",
            "huggy wuggy", "skibidi toilet", "momo", "blue monster",
            "jumpscare", "scary compilation", "creepy pasta",
            "מוות", "מת", "גוסס", "לוויה", "קבר", "רוח רפאים",
            "שד", "שטן", "גיהנם", "עינויים", "חטיפה",
            "התאבדות", "רעל", "סיוט", "אימה", "פחד",
            "האגי וואגי", "סקיבידי טואלט", "מפחיד מאוד", "קריפי"
        )
    )

    private val violentShows = listOf(
        "ninja turtles fight", "tmnt battle", "tmnt combat",
        "power rangers fight", "power rangers battle",
        "dragon ball fight", "dragon ball z fight",
        "naruto fight", "naruto battle", "naruto vs",
        "one piece fight", "attack on titan",
        "mortal kombat", "street fighter",
        "ninjago battle", "ninjago fight",
        "transformers battle", "transformers war",
        "ben 10 fight", "ben 10 battle",
        "spiderman fight", "spiderman vs", "spider-man fight",
        "batman fight", "batman vs",
        "avengers fight", "avengers battle",
        "hulk smash", "hulk angry", "hulk fight",
        "מרגיז", "מעצבן", "מכות", "אלימות", "מפחיד",
        "צבי הנינג'ה נלחמים", "צבי נינגה קרב",
        "פאוור ריינג'רס קרב", "פאוור ריינג'רס נלחמים",
        "דרגון בול קרב", "נארוטו נלחם", "נארוטו נגד",
        "ספיידרמן נלחם", "ספיידרמן נגד", "ספיידרמן קרב",
        "באטמן נלחם", "באטמן נגד",
        "הנוקמים קרב", "האלק מנפץ", "האלק כועס",
        "בן 10 נלחם", "נינג'גו קרב", "יובל המבולבל",
        "huggy wuggy", "האגי וואגי", "skibidi toilet", "סקיבידי טואלט",
        "מכות", "קרב", "אלימות", "דם", "סירנה"
    )

    fun classify(text: String): ContentScore {
        val normalizedText = text.lowercase().trim()
        val categoryMatches = mutableListOf<CategoryMatch>()

        for ((category, keywords) in keywordDatabase) {
            val matched = keywords.filter { keyword ->
                normalizedText.contains(keyword.lowercase())
            }
            if (matched.isNotEmpty()) {
                val baseScore = if (category.weight >= 0.9f) 0.8f else category.weight
                val extraBonus = (matched.size.coerceAtMost(5).toFloat() / 5f) * 0.2f

                categoryMatches.add(
                    CategoryMatch(
                        category = category,
                        score = (baseScore + extraBonus).coerceAtMost(1.0f),
                        matchedTerms = matched
                    )
                )
            }
        }

        val showMatches = violentShows.filter { show ->
            normalizedText.contains(show.lowercase())
        }
        if (showMatches.isNotEmpty()) {
            categoryMatches.add(
                CategoryMatch(
                    category = Category.VIOLENCE_PHYSICAL,
                    score = 0.95f,
                    matchedTerms = showMatches
                )
            )
        }

        val customMatches = customBlacklist.filter { custom ->
            normalizedText.contains(custom.lowercase())
        }
        if (customMatches.isNotEmpty()) {
            categoryMatches.add(
                CategoryMatch(
                    category = Category.ELSAGATE,
                    score = 2.0f,
                    matchedTerms = customMatches.toList()
                )
            )
        }

        val totalScore = if (categoryMatches.isEmpty()) 0f
        else categoryMatches.maxOf { it.score }

        return ContentScore(
            totalScore = totalScore,
            categories = categoryMatches,
            isBlocked = totalScore >= blockThreshold || totalScore > 1.0f
        )
    }

    fun updateCustomBlacklist(keywords: List<String>) {
        customBlacklist.clear()
        customBlacklist.addAll(keywords.map { it.lowercase() })
    }

    fun setSensitivity(level: SensitivityLevel) {
        blockThreshold = level.threshold
    }

    enum class SensitivityLevel(val threshold: Float, val labelHe: String) {
        STRICT(0.3f, "קפדני"),
        BALANCED(0.5f, "מאוזן"),
        RELAXED(0.7f, "מרוכך")
    }
}
