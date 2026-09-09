package com.medtroniclabs.microcoaching.ui.learn

/**
 * Builds the interleaved Card→Quiz sequence required by LEAP-11.
 *
 * Each [CardQuizSegment] pairs one learning card with the quiz questions that
 * test it. The CHW completes one segment (reads the card, answers its quiz)
 * before the next card is unlocked. Failing a quiz replays the same card
 * before retrying with reshuffled answer options.
 *
 * Grouping uses [QuizQuestion.primaryCardIndex] (1-based, from the sync
 * payload). Questions with a null index have no card pairing and are
 * collected into a trailing segment after all named cards — this preserves
 * backward compatibility with modules authored before the field was added.
 *
 * When no question carries a non-null [QuizQuestion.primaryCardIndex] the
 * function returns an empty list, signalling the caller to fall back to the
 * legacy flat flow (all cards then all questions).
 */
internal data class CardQuizSegment(
    // 0-based index into the module's LessonCard list
    val cardIndex: Int,
    // Questions whose primary_card_index maps to this card; empty when the card
    // has no associated questions (CHW reads it, then advances without a quiz)
    val questions: List<QuizQuestion>,
)

/**
 * @param cards Ordered lesson cards for the module.
 * @param questions All quiz questions for the module.
 * @param debugForceSequence When true (debug builds only), auto-assigns each question
 *   to the card at the same position when [QuizQuestion.primaryCardIndex] is absent.
 *   Allows end-to-end testing of the Card→Quiz flow without backend support for the
 *   `primary_card_index` field. Must never be true in release builds.
 * @return Per-card segments in card order, or empty when no question carries
 *   [QuizQuestion.primaryCardIndex] (triggers legacy flat fallback in the caller).
 */
internal fun buildCardQuizSegments(
    cards: List<LessonCard>,
    questions: List<QuizQuestion>,
    debugForceSequence: Boolean = false,
): List<CardQuizSegment> {
    // No card-index metadata → auto-assign in debug force mode, else legacy flat flow
    if (questions.none { it.primaryCardIndex != null }) {
        if (!debugForceSequence || cards.isEmpty() || questions.isEmpty()) {
            android.util.Log.d("CardQuizSequencer", "LEAP-11: legacy flat mode (cards=${cards.size} questions=${questions.size} forceSeq=$debugForceSequence)")
            return emptyList()
        }
        // Debug: assign question i → card (i % cardCount) + 1 so every question links to a card
        val forced = questions.mapIndexed { i, q -> q.copy(primaryCardIndex = (i % cards.size) + 1) }
        android.util.Log.d("CardQuizSequencer", "LEAP-11: debug forced ${forced.size} questions across ${cards.size} cards")
        return buildCardQuizSegments(cards, forced, debugForceSequence = false)
    }

    // Group questions by their 1-based card index
    val byCardOneBased: Map<Int, List<QuizQuestion>> =
        questions.filter { it.primaryCardIndex != null }
            .groupBy { it.primaryCardIndex!! }

    // Questions with no card pairing go into a trailing segment after all cards
    val unanchored = questions.filter { it.primaryCardIndex == null }

    val segments = cards.mapIndexed { idx, _ ->
        CardQuizSegment(
            cardIndex = idx,
            questions = byCardOneBased[idx + 1].orEmpty(), // convert 0-based → 1-based
        )
    }
    android.util.Log.d("CardQuizSequencer", "LEAP-11: built ${segments.size} segments, questions/card: ${segments.map { it.questions.size }}")

    // Append trailing segment only when there are unanchored questions
    return if (unanchored.isEmpty()) segments
    else segments + CardQuizSegment(cardIndex = segments.lastIndex, questions = unanchored)
}
