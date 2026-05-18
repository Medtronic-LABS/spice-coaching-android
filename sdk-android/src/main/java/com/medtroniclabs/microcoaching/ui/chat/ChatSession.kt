package com.medtroniclabs.microcoaching.ui.chat

import android.util.Log
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.domain.context.CHWWorkContext
import com.medtroniclabs.microcoaching.domain.context.PatientSnapshot
import java.util.UUID

/**
 * Active chat session context.
 *
 * A new session is created each time [CoachingChatFragment] is opened.
 * Sessions are persisted in the SDK Room DB for history access.
 */
data class ChatSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val patientSnapshot: PatientSnapshot? = null,
    val chwWorkContext: CHWWorkContext? = null,
    /** Optional system prompt override for this session. */
    val systemContext: String = "",
    val startedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Builds a Gemma 3-formatted prompt using the model's chat template tokens.
 *
 * Gemma 3 supports a dedicated `<start_of_turn>system` role — use it so coaching
 * instructions are placed in their own slot and are not overridden by user input.
 *
 * @param currentMessage The new user message (NOT included in [history]).
 * @param history Previous turns only — do NOT pass the current message here.
 * @param language BCP-47 language tag, e.g. "bn-BD".
 */
internal fun ChatSession.buildPrompt(
    currentMessage: String,
    history: List<ChatMessage>,
    language: String = "bn-BD",
): String {
    val sb = StringBuilder()
    val basePrompt = buildDefaultSystemPrompt(language)
    val systemSections = mutableListOf(basePrompt)
    if (systemContext.isNotBlank()) systemSections.add(systemContext)
    val groundingBlock = buildScopeReminder(language)
    if (groundingBlock.isNotBlank()) systemSections.add(groundingBlock)

    val fullSystem = systemSections.joinToString("\n\n")

    // Limit to 3 exchanges (6 turns); truncate very long messages (e.g. repetition-loop responses)
    // to prevent context window overflow on the 512-token budget.
    val prevTurns = history.takeLast(6).map { msg ->
        if (msg.text.length > 600) msg.copy(text = msg.text.take(600) + "…") else msg
    }

    // Hard char-cap on system + history combined. ~2000 chars ≈ 500 tokens, leaves room for
    // the current message and 512-token output on Gemma 3 1B's 2K window.
    val (cappedSystem, cappedTurns) = clampToCharBudget(fullSystem, prevTurns, MAX_PROMPT_CHARS)

    // Gemma 3 system role — keeps instructions separate from the conversation.
    sb.append("<start_of_turn>system\n")
    sb.append(cappedSystem)
    sb.append("<end_of_turn>\n")

    cappedTurns.forEach { msg ->
        val turn = if (msg.role == ChatRole.USER) "user" else "model"
        sb.append("<start_of_turn>$turn\n")
        sb.append(msg.text)
        sb.append("<end_of_turn>\n")
    }

    sb.append("<start_of_turn>user\n")
    sb.append(currentMessage)
    sb.append("<end_of_turn>\n")
    sb.append("<start_of_turn>model\n")

    val out = sb.toString()
    Log.d("ChatSession", "buildPrompt chars=${out.length}")
    return out
}

private const val MAX_PROMPT_CHARS = 2000

/** If system + history exceeds the budget, drop oldest turns until it fits. System is never trimmed. */
private fun clampToCharBudget(
    system: String,
    turns: List<ChatMessage>,
    budgetChars: Int,
): Pair<String, List<ChatMessage>> {
    val systemLen = system.length
    if (systemLen >= budgetChars) return system to emptyList()
    var remaining = budgetChars - systemLen
    val kept = ArrayDeque<ChatMessage>()
    for (msg in turns.asReversed()) {
        val cost = msg.text.length + 32 // budget for role tags
        if (cost > remaining) break
        kept.addFirst(msg)
        remaining -= cost
    }
    return system to kept.toList()
}

/** Scope reminder block listing the domains the assistant covers. */
private fun buildScopeReminder(language: String): String {
    return if (language.startsWith("bn")) {
        "তুমি এই বিষয়গুলোতে সাহায্য করতে পারো: উচ্চ রক্তচাপ, ডায়াবেটিস, মাতৃস্বাস্থ্য, জরুরি অবস্থা, এবং SPICE অ্যাপ ব্যবহার। অন্য বিষয়ে CHW-কে সুপারভাইজারের সাথে পরামর্শ করতে বলো।"
    } else {
        "Scope: I can help with hypertension, diabetes, maternal health, emergencies, and SPICE app usage. For other topics, advise the CHW to consult their supervisor."
    }
}

private fun buildPatientContext(patient: PatientSnapshot?): String {
    patient ?: return ""
    return buildString {
        appendLine("Most recently assessed patient:")
        patient.ageYears?.let { appendLine("- Age: $it years") }
        patient.gender?.let { appendLine("- Gender: $it") }
        if (patient.conditions.isNotEmpty()) appendLine("- Conditions: ${patient.conditions.joinToString(", ")}")
        patient.riskLevel?.let { appendLine("- CVD risk: $it") }
        patient.avgSystolic?.let { sys ->
            patient.avgDiastolic?.let { dia -> appendLine("- BP: $sys/$dia mmHg") }
        }
        patient.glucose?.let { appendLine("- Glucose: $it mmol/L") }
        patient.bmi?.let { appendLine("- BMI: $it") }
    }.trimEnd()
}

private fun buildCHWContext(chwCtx: CHWWorkContext?): String {
    chwCtx ?: return ""
    if (chwCtx.screenedTodayCount == 0 && chwCtx.recentPatients.isEmpty()) return ""
    return buildString {
        appendLine("CHW work context (today):")
        appendLine("- Patients screened today: ${chwCtx.screenedTodayCount}")
        chwCtx.recentPatients.forEachIndexed { i, p ->
            val conds = if (p.conditions.isEmpty()) "screening only" else p.conditions.joinToString(", ")
            appendLine("- Patient ${i + 1}: $conds, risk: ${p.riskLevel ?: "unknown"}")
        }
    }.trimEnd()
}

// Directive and concrete — small 1B models follow simple instructions best.
// Role is stated first, then a numbered task list, then hard constraints.
private fun buildDefaultSystemPrompt(language: String): String {
    return if (language.startsWith("bn")) {
        """
তুমি একজন AI স্বাস্থ্য সহকারী। তোমার ভূমিকা হলো বাংলাদেশের কমিউনিটি স্বাস্থ্যকর্মীদের (CHW) তাদের দৈনন্দিন রোগীসেবায় সহায়তা করা।

তুমি সাহায্য করতে পারো:
- রোগীর স্ক্রিনিং ও মূল্যায়নের তথ্য সম্পর্কে প্রশ্নের উত্তর দিতে
- উচ্চ রক্তচাপ, ডায়াবেটিস ও হৃদরোগের ঝুঁকি ব্যবস্থাপনায় পরামর্শ দিতে
- ওষুধ মেনে চলা ও রোগী কাউন্সেলিংয়ে গাইড করতে
- সাম্প্রতিক স্ক্রিনিং ও রোগীর তথ্য নিয়ে প্রশ্নের উত্তর দিতে

নিয়ম: সর্বদা বাংলায় উত্তর দাও। রোগ নির্ণয় করো না। প্রশ্নটি পুনরাবৃত্তি করো না। সর্বোচ্চ ৩-৪ বাক্যে উত্তর দাও।
        """.trimIndent()
    } else {
        """
You are an AI health assistant for Community Health Workers (CHWs) in Bangladesh.

Your role is to support CHWs in their daily patient care work. You can help with:
- Answering questions about recent patient screenings and assessments
- Guidance on hypertension, diabetes, and CVD risk management
- Medication adherence and patient counselling support
- Interpreting clinical readings (BP, glucose, BMI, risk scores)

Rules: Always respond in English. Be brief (3-4 sentences max). Do not make diagnoses. Do not repeat the question. When context data is provided below, use it to give specific, grounded answers.
        """.trimIndent()
    }
}

/**
 * Wraps patient snapshot and CHW work context under a single labelled block so the LLM
 * clearly distinguishes role instructions (above) from factual data (here).
 */
private fun buildContextBlock(
    language: String,
    patientContext: String,
    chwContext: String,
): String {
    val sections = listOf(patientContext, chwContext).filter { it.isNotBlank() }
    if (sections.isEmpty()) return ""
    val header = if (language.startsWith("bn")) "--- প্রাসঙ্গিক তথ্য ---" else "--- Context Data ---"
    return "$header\n${sections.joinToString("\n\n")}"
}
