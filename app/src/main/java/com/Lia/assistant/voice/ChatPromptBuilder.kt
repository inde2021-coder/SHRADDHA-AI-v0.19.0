package com.Lia.assistant.voice

import com.Lia.assistant.data.LanguagePreference
import com.Lia.assistant.data.Personality

object ChatPromptBuilder {

    fun build(
        personality: Personality,
        language: LanguagePreference,
        assistantName: String
    ): String {
        val sections = mutableListOf<String>()

        sections.add(
            "You are $assistantName, a personal AI companion living on the user's phone. This is a typed text chat interface. Never mention listening, speaking out loud, or asking the user to tap on voice controls. Respond with well-formatted markdown."
        )

        sections.add(personality.systemInstruction)

        val langPolicy = when (language) {
            LanguagePreference.AUTO -> "Language Policy: Match the language of the user's message (Hindi, Hinglish, or English) naturally."
            LanguagePreference.HINDI -> "Language Policy: Always respond in natural Hindi."
            LanguagePreference.HINGLISH -> "Language Policy: Always respond in natural conversational Hinglish."
            LanguagePreference.ENGLISH -> "Language Policy: Always respond in clear, expressive English."
            LanguagePreference.MARATHI -> "Language Policy: Respond in Marathi when selected. Do not claim offline speech recognition unless the verified Marathi model is installed."
            LanguagePreference.SANSKRIT -> "Language Policy: Respond in Sanskrit when selected. Be transparent about pronunciation and speech-recognition limitations."
            LanguagePreference.PAWARI -> "Language Policy: Pawari is a community-data collection and research language in this build. Do not claim fluent generation, translation, or speech recognition; offer Hindi or Marathi as supported alternatives."
        }
        sections.add(langPolicy)

        sections.add(
            "Safety Guidelines:\n" +
            "1. Never claim to be human or possess a physical human body.\n" +
            "2. No sexually explicit content.\n" +
            "3. Immediately and gracefully respect any request by the user to change tone, stop, or change topic."
        )

        return sections.joinToString("\n\n")
    }
}
