package com.maik.app

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.launch

data class Message(
    val text: String,
    val fromUser: Boolean,
    val isError: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val messages = mutableStateListOf<Message>()
    val busy = mutableStateOf(false)

    private val model: GenerativeModel by lazy {
        GenerativeModel(
            generationConfig = generationConfig {
                context = getApplication<Application>().applicationContext
                temperature = 0.2f
                topK = 16
                maxOutputTokens = 512
            }
        )
    }

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || busy.value) return

        messages += Message(trimmed, fromUser = true)
        busy.value = true

        viewModelScope.launch {
            val reply = try {
                // Nano keeps no conversation state, so we replay the transcript.
                val text = model.generateContent(buildPrompt()).text.orEmpty().trim()
                if (text.isEmpty()) Message("…", fromUser = false)
                else Message(text, fromUser = false)
            } catch (e: Exception) {
                Message(
                    "${e.message ?: e::class.java.simpleName}\n\n" +
                        "Gemini Nano is served by Android AICore and only exists on " +
                        "supported devices. Check that AICore and Android System " +
                        "Intelligence are up to date.",
                    fromUser = false,
                    isError = true
                )
            }
            messages += reply
            busy.value = false
        }
    }

    fun clear() {
        if (!busy.value) messages.clear()
    }

    private fun buildPrompt(): String = buildString {
        messages.forEach { m ->
            append(if (m.fromUser) "User: " else "Assistant: ")
            append(m.text)
            append("\n")
        }
        append("Assistant: ")
    }

    override fun onCleared() {
        super.onCleared()
        model.close()
    }
}
