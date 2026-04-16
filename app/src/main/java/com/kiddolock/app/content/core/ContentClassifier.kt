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
        DARK_THEMES(0.85f, "נושאים אפלים"),
        DANGEROUS_ACTIVITIES(0.95f, "פעילויות מסוכנות");
    }

    // Threshold can be adjusted by parent (strict=0.3, balanced=0.5, relaxed=0.7)
    var blockThreshold: Float = 0.5f

    // Custom keywords added by parents
    private val customBlacklist = mutableSetOf<String>()

    // Built-in keywords the parent has explicitly marked "allow". Matches
    // against any of these are skipped so the parent can un-block a default
    // that was producing false positives (e.g. "battle" breaking Pokémon
    // content). Lowercase + trimmed on insert.
    private val allowedOverrides = mutableSetOf<String>()

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
            "חונק", "מרביץ", "אלימות", "אלים", "דמים", "פציעה",
            "מתאגרף", "אגרוף", "בעיטה", "סטירה", "הרוג", "רצח",
            "פיצוץ", "מפוצץ", "להרוג", "מלחמות", "קרבות", "שפיכת דם", "פצוע", "גופה",
            "אלימות בחברה", "קטטה", "דקירות", "דקירה", "מכות רצח", "מרביצים",
            "בועטים", "חונקים", "שורפים", "שריפה", "פיגוע", "טרור",
            // Explicit conjugation forms — covers infinitive, present,
            // past and plural for each violent verb so substring matching
            // catches all common forms without sofit-normalization.
            "להרביץ", "הרביץ", "הרביצו", "ירביץ", "מרביצה",    // beat
            "לדקור", "דקר", "דוקרים", "דקרו",                   // stab
            "לחנוק", "חנק", "חונקת", "חנקו",                    // choke
            "לבעוט", "בעט", "בועטת", "בעטו",                    // kick
            "לתקוף", "תקף", "תוקפת", "תוקפים", "תקפו",        // attack
            "לרצוח", "רצח", "רוצחת", "רוצחים", "רצחו",         // murder
            "להרוג", "הרגו", "הורגת", "הורגים",                 // kill
            "לירות", "ירו", "יורים", "יורה",                     // shoot
            "לשרוף", "שורפת", "שרפו", "שורפים",                 // burn
            "להכות", "הכה", "הכו", "מכים",                       // hit
            "להילחם", "נלחמת", "נלחמו", "ילחמו",                // fight
            "לפוצץ", "פוצצו", "מפוצצת", "פיצוצים",             // explode
            // Arabic
            "قتال", "ضرب", "عنف", "حرب",
            // Russian
            "драка", "удар", "бить", "побои", "нападение", "убить",
            "убийство", "стрелять", "ножевое", "душить", "бой",
            "война", "кровь", "насилие", "жестокость", "ранение",
            "взрыв", "террор", "пинок", "избиение", "мордобой"
        ),

        Category.VIOLENCE_VERBAL to listOf(
            // English
            "stupid", "idiot", "hate", "shut up", "dumb", "loser",
            "ugly", "fat", "bully", "bullying", "mean", "curse",
            // Hebrew
            "טיפש", "מטומטם", "שונא", "שתוק", "מכוער", "שמן",
            "בוזז", "בריונות", "בריון", "קללה", "מקלל", "אידיוט",
            "טמבל", "דביל", "חמור",
            "לקלל", "קיללו", "מקללים", "מקללת",  // curse — all forms
            "בריונים", "להציק", "מציק", "מציקים", "הציקו",  // bully/harass
            // Russian
            "тупой", "идиот", "ненависть", "заткнись", "дурак",
            "лузер", "уродливый", "толстый", "буллинг", "травля",
            "обзывать", "ругательство", "дебил"
        ),

        Category.HORROR_KIDS to listOf(
            // English
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
            // Hebrew
            "האגי ואגי", "פופי פלייטיים", "אמא רגליים ארוכות",
            "סקיבידי", "סקיבידי טוילט",
            "ראש סירנה", "חתול מצויר",
            "באלדי", "גראני", "גראני משחק",
            "באנבאן", "גארטן אוף באנבאן",
            "פרדי", "חמש לילות אצל פרדי",
            "ג'אמפסקר", "סלנדרמן",
            // Russian
            "хагги вагги", "попи плейтайм", "мамочка длинные ноги",
            "скибиди", "скибиди туалет",
            "сиреноголовый", "мультяшный кот",
            "балди", "гренни", "бан бан",
            "фредди", "пять ночей с фредди",
            "джампскейр", "крипипаста", "слендермен"
        ),

        Category.ELSAGATE to listOf(
            // English
            "elsa injection", "elsa pregnant", "spiderman pregnant",
            "pregnant elsa", "bad baby", "bad babies",
            "joker prank", "joker vs", "evil elsa",
            "frozen prank", "spiderman poop", "poop challenge",
            "injection challenge", "doctor injection",
            "finger family gone wrong", "wrong heads",
            "learn colors gone wrong", "surprise egg horror",
            "johny johny scary", "cocomelon scary",
            // Hebrew
            "אלזה הזרקה", "ספיידרמן בהריון", "תינוק רע",
            "ג'וקר נגד", "אלזה רעה", "אתגר קקי",
            // Russian
            "эльза укол", "эльза беременна", "спайдермен беременный",
            "плохой ребёнок", "джокер пранк", "злая эльза",
            "какашки челлендж"
        ),

        Category.WEAPONS to listOf(
            // English
            "gun", "rifle", "pistol", "sword", "knife", "weapon",
            "bomb", "grenade", "missile", "sniper", "shotgun",
            "machine gun", "ak47", "ak-47",
            // Hebrew
            "אקדח", "רובה", "חרב", "סכין", "נשק", "פצצה",
            "רימון", "טיל", "צלף", "מקלע",
            // Russian
            "пистолет", "ружьё", "винтовка", "автомат", "меч",
            "нож", "оружие", "бомба", "граната", "ракета",
            "снайпер", "дробовик", "пулемёт"
        ),

        Category.DARK_THEMES to listOf(
            // English
            "death", "dead", "die", "dying", "funeral", "grave",
            "ghost", "demon", "devil", "hell", "torture",
            "kidnap", "kidnapping", "suicide", "poison",
            "nightmare", "scared", "terrified", "horror",
            "huggy wuggy", "skibidi toilet", "momo", "blue monster",
            "jumpscare", "scary compilation", "creepy pasta",
            // Hebrew
            "מוות", "מתים", "מתה", "למות", "גוסס", "לוויה", "קבר", "רוח רפאים",
            "שד", "שטן", "גיהנם", "עינויים", "חטיפה",
            "התאבדות", "רעל", "סיוט", "אימה", "פחד",
            "האגי וואגי", "סקיבידי טואלט", "מפחיד מאוד", "קריפי",
            // Russian
            "смерть", "мёртвый", "умереть", "похороны", "могила",
            "призрак", "демон", "дьявол", "ад", "пытки",
            "похищение", "суицид", "яд", "кошмар", "ужас",
            "страшно", "жуткий", "крипи"
        ),

        Category.DANGEROUS_ACTIVITIES to listOf(
            // English — videos teaching kids to do dangerous stunts
            "jump off roof", "jump from roof", "rooftop jump",
            "set fire", "play with fire", "fire challenge",
            "lighter trick", "matches trick", "burn challenge",
            "tide pod challenge", "bleach challenge", "drink bleach",
            "choking game", "blackout challenge", "pass out challenge",
            "train surfing", "subway surfing", "car surfing",
            "dangerous challenge", "deadly challenge", "extreme dare",
            "how to make a bomb", "how to make poison",
            "how to pick a lock", "how to steal",
            "balcony jump", "parkour fail", "parkour gone wrong",
            "electric shock", "outlet challenge",
            "how to start a fire", "how to break in",
            "how to run away", "run away from home",
            "how to shoplift", "how to fight",
            "playing with lighter", "playing with matches",
            "roof challenge", "rooftop challenge",
            // Hebrew
            "לקפוץ מהגג", "קפיצה מגג", "קפיצה מהגג",
            "קופצים מהגג", "קפצו מהגג", "קופץ מגג",
            "להצית אש", "לשחק באש", "משחקים באש",
            "משחק באש", "שיחק באש", "הצית אש", "מצית אש",
            "אתגר אש", "אתגר מסוכן", "אתגר מטורף",
            "אתגר חנק", "אתגר התעלפות", "משחק מחנק",
            "גלישת רכבות", "גלישה על רכבת",
            "איך לגנוב", "איך לפרוץ", "איך לשרוף",
            "איך להצית", "איך לעשות פצצה", "איך לעשות רעל",
            "איך להכין פצצה", "איך להכין נשק",
            "קפיצה ממרפסת", "קפיצה מגובה", "קפיצה מבניין",
            "הלם חשמלי", "לגעת בחשמל", "לשחק עם חשמל",
            "מצת", "גפרורים", "שריפה בבית",
            "סכנת חיים", "פרקור נכשל",
            "לכבות שריפה", "להדליק אש", "מדליק אש",
            "לברוח מהבית", "לברוח מהשוטרים",
            "הצתה", "מצית",
            // Russian
            "прыжок с крыши", "прыгнуть с крыши", "прыжок с балкона",
            "поджог", "играть с огнём", "огненный челлендж",
            "зажигалка трюк", "спички трюк",
            "опасный челлендж", "смертельный челлендж",
            "удушение игра", "игра в обморок",
            "зацепер", "сёрфинг на поезде", "сёрфинг на крыше",
            "как сделать бомбу", "как сделать яд",
            "как украсть", "как взломать",
            "паркур неудача", "паркур провал",
            "удар током", "электрошок"
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
        "מכות", "קרב", "אלימות", "דמים", "סירנה",
        // Russian
        "черепашки ниндзя бой", "черепашки ниндзя драка",
        "наруто бой", "наруто драка", "наруто против",
        "драгон болл бой", "человек паук бой", "человек паук против",
        "бэтмен бой", "бэтмен против", "мстители бой",
        "халк крушит", "халк злится", "мортал комбат",
        "хагги вагги", "скибиди туалет",
        "драка", "бой", "насилие", "кровь"
    )

    fun classify(text: String): ContentScore {
        val normalizedText = text.lowercase().trim()
        val categoryMatches = mutableListOf<CategoryMatch>()

        for ((category, keywords) in keywordDatabase) {
            val matched = keywords.filter { keyword ->
                val lower = keyword.lowercase()
                lower !in allowedOverrides && normalizedText.contains(lower)
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
            val lower = show.lowercase()
            lower !in allowedOverrides && normalizedText.contains(lower)
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

    fun updateAllowedOverrides(words: Set<String>) {
        allowedOverrides.clear()
        allowedOverrides.addAll(words.map { it.lowercase().trim() })
    }

    fun setSensitivity(level: SensitivityLevel) {
        blockThreshold = level.threshold
    }

    /**
     * Read-only view of the built-in keyword database grouped by category.
     * Used by the parent-facing content-filter UI to show which default
     * keywords are in force and let the parent mark any of them as
     * "allow" (override) via [updateAllowedOverrides].
     */
    fun defaultKeywords(): Map<Category, List<String>> = keywordDatabase

    /** Read-only view of the built-in "violent shows" phrase list. */
    fun defaultViolentShows(): List<String> = violentShows

    enum class SensitivityLevel(val threshold: Float, val labelHe: String) {
        STRICT(0.3f, "קפדני"),
        BALANCED(0.5f, "מאוזן"),
        RELAXED(0.7f, "מרוכך")
    }
}
