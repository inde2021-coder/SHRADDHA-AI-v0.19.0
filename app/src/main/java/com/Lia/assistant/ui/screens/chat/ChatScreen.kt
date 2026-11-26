package com.Lia.assistant.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.forge.ForgeController
import com.Lia.assistant.forge.ForgeIntent
import com.Lia.assistant.ui.components.ChatMessage
import com.Lia.assistant.ui.components.NovaMessageBubble
import com.Lia.assistant.ui.components.NovaTextField
import com.Lia.assistant.ui.components.NovaTypingDots
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.depthSurface
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.ChatPromptBuilder
import com.Lia.assistant.voice.ShraddhaSpeechClient
import com.Lia.assistant.voice.LanguageDetector
import `in`.shraddha.ai.brain.PersistentChatMemory
import `in`.shraddha.ai.brain.LocalKnowledgeBase
import `in`.shraddha.ai.brain.LocalKnowledgeDocumentStore
import `in`.shraddha.ai.brain.LocalKnowledgeAnswer
import `in`.shraddha.ai.brain.TrainingCurriculumInstaller
import `in`.shraddha.ai.brain.LocalOfflineLlm
import com.Lia.assistant.voice.ChatReplyResult
import com.Lia.assistant.voice.ChatTurn
import com.Lia.assistant.voice.NovaOrbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                text = "Hello! I am ${appState.assistantName}. How can I assist you today?",
                isUser = false
            )
        )
    }

    var inputText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }

    // Auto-scroll on new message
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        messages.add(ChatMessage(text = trimmed, isUser = true))
        inputText = ""

        // Check Website Forge intent
        val forgePrompt = ForgeIntent.extract(trimmed)
        if (forgePrompt != null) {
            messages.add(
                ChatMessage(
                    text = "Opening the Website Forge for: \"$forgePrompt\"...",
                    isUser = false
                )
            )
            ForgeController.start(context, forgePrompt)
            navController.navigate("forge")
            return
        }

        // Snapshot Compose state on the main thread; never read the mutable UI list
        // from the IO dispatcher. Keep a bounded context window for every provider.
        val historySnapshot = messages.takeLast(40)
            .map { ChatTurn(isUser = it.isUser, text = it.text.take(8_000)) }

        // Call the selected AI provider
        isTyping = true
        scope.launch(Dispatchers.IO) {
            // Persistent memory is opt-in and encrypted locally. If disabled, no recap is read or saved.
            PersistentChatMemory.record(context, "User", trimmed)
            val systemInstruction = ChatPromptBuilder.build(
                personality = appState.personality,
                language = appState.language,
                assistantName = appState.assistantName
            )
            val memoryRecap = PersistentChatMemory.recap(context)
            val relevantMemory = memoryRecap?.let { recap ->
                LocalKnowledgeBase.search(recap, trimmed, limit = 4).map { it.text }
            }.orEmpty()
            val memoryInstruction = if (relevantMemory.isNotEmpty()) {
                "\n\nRelevant saved conversation memories (user-approved, encrypted on this device; may be incomplete):\n" +
                    relevantMemory.joinToString("\n")
            } else ""
            val boundedInstruction = (systemInstruction + memoryInstruction).take(8_000)

            // Install the authored Hindi/English/Sanskrit/Marathi/Pawari lesson packs into
            // the existing encrypted local knowledge store before the first retrieval.
            // This is bundled, offline content: no paid API, network fetch or model download.
            TrainingCurriculumInstaller.install(context.applicationContext)

            // Imported documents are searched locally first. A confident extractive match is
            // answered on-device and is never copied into a cloud prompt without separate consent.
            val localKnowledgeHits = runCatching {
                LocalKnowledgeDocumentStore(context.applicationContext).search(trimmed, limit = 8).getOrThrow()
            }.getOrDefault(emptyList())
            val localKnowledgeAnswer = LocalKnowledgeAnswer.build(trimmed, localKnowledgeHits)
            // Only app-bundled curriculum excerpts may enter an enabled cloud request.
            // User-imported/private documents remain local and are never silently uploaded.
            val bundledCurriculumContext = localKnowledgeHits
                .filter { it.sourceLabel.startsWith("Shraddha AI built-in curriculum") }
                .take(4)
                .joinToString("\n\n") { hit ->
                    "[" + hit.title + " • " + hit.citation + "]\n" + hit.excerpt.take(900)
                }
            val trainedSystemInstruction = if (bundledCurriculumContext.isNotBlank()) {
                (boundedInstruction.take(4_500) +
                    "\n\nRelevant bundled Shraddha curriculum (source-labelled excerpts):\n" +
                    bundledCurriculumContext.take(3_000)).take(8_000)
            } else boundedInstruction
            val offlineModelPath = context.getSharedPreferences(
                "shraddha_offline_model",
                Context.MODE_PRIVATE
            ).getString("active_model_path", "").orEmpty().trim()

            // Routing order: encrypted document knowledge -> explicitly imported on-device
            // model -> configured provider. A selected local model failure is surfaced locally;
            // it is never silently sent to a cloud provider with the same private prompt.
            val result = when {
                localKnowledgeAnswer != null -> ChatReplyResult.Success(localKnowledgeAnswer.text)
                offlineModelPath.isNotBlank() -> runCatching {
                    val localPrompt = buildString {
                        append(trainedSystemInstruction)
                        append("\n\nRecent conversation:\n")
                        historySnapshot.takeLast(8).forEach { turn ->
                            append(if (turn.isUser) "User: " else "Assistant: ")
                            append(turn.text.take(700))
                            append('\n')
                        }
                    }.takeLast(6_000)
                    ChatReplyResult.Success(
                        LocalOfflineLlm.generate(context, offlineModelPath, localPrompt)
                    )
                }.getOrElse { error ->
                    ChatReplyResult.Error(
                        "Local offline model could not answer. Your message was not sent to a cloud provider. " +
                            (error.localizedMessage ?: "Check the imported model and device memory.")
                    )
                }
                else -> {
                    // Use the central consent policy; never read a parallel preference
                    // that could drift from Settings or other cloud entry points.
                    val onlineAiAllowed = `in`.shraddha.ai.future.OnlineAiConsent.isAllowed(context)
                    if (onlineAiAllowed) {
                        `in`.shraddha.ai.future.MainBrainProviderGateway.reply(
                            context, appState.aiProvider, trainedSystemInstruction, historySnapshot
                        )
                    } else {
                        ChatReplyResult.Error(
                            "Online AI is turned off. Your message stayed on this device. " +
                                "Enable Allow Online AI in Settings > Connections only when you want to use your selected provider."
                        )
                    }
                }
            }

            if (result is ChatReplyResult.Success) {
                PersistentChatMemory.record(context, "Assistant", result.text)
            }
            withContext(Dispatchers.Main) {
                isTyping = false
                when (result) {
                    is ChatReplyResult.Success -> {
                        // Word-by-word reveal (20ms per word)
                        val fullReply = result.text
                        val words = fullReply.split(" ")
                        val placeholderId = java.util.UUID.randomUUID().toString()
                        val msg = ChatMessage(id = placeholderId, text = "", isUser = false, isStreaming = true)
                        messages.add(msg)

                        scope.launch {
                            val builder = StringBuilder()
                            for (w in words) {
                                if (builder.isNotEmpty()) builder.append(" ")
                                builder.append(w)
                                val idx = messages.indexOfFirst { it.id == placeholderId }
                                if (idx != -1) {
                                    messages[idx] = messages[idx].copy(text = builder.toString())
                                }
                                delay(20)
                            }
                            val finalIdx = messages.indexOfFirst { it.id == placeholderId }
                            if (finalIdx != -1) {
                                messages[finalIdx] = messages[finalIdx].copy(isStreaming = false)
                            }
                        }
                    }
                    is ChatReplyResult.Error -> {
                        messages.add(
                            ChatMessage(
                                text = "Error: ${result.message}",
                                isUser = false,
                                isError = true
                            )
                        )
                    }
                }
            }
        }
    }

    // Receive text entered on the home screen's "Ask me anything" field.
    LaunchedEffect(Unit) {
        val pendingPrompt = navController.previousBackStackEntry
            ?.savedStateHandle
            ?.remove<String>("home_prompt")
        if (!pendingPrompt.isNullOrBlank()) sendMessage(pendingPrompt)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .imePadding()
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }

            // Small 40dp 3D orb in header
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { navController.navigate("voice") },
                contentAlignment = Alignment.Center
            ) {
                LiaOrb3D(
                    state = if (isTyping) NovaOrbState.THINKING else NovaOrbState.IDLE,
                    size = 40.dp,
                    amplitude = if (isTyping) 0.3f else 0f,
                    assistantName = appState.assistantName
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appState.assistantName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colors.textPrimary
                )
                Text(
                    text = if (isTyping) "GENERATING RESPONSE" else "${appState.aiProvider}  •  READY",
                    fontSize = 9.sp,
                    color = colors.textTertiary
                )
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                NovaMessageBubble(
                    message = msg,
                    onSpeakClick = {
                        ShraddhaSpeechClient.speak(context, msg.text, LanguageDetector.detect(msg.text)) { result ->
                            result.exceptionOrNull()?.let { error ->
                                android.widget.Toast.makeText(context, error.localizedMessage ?: "Shraddha voice unavailable", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onCopyClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clip?.setPrimaryClip(ClipData.newPlainText(appState.assistantName, msg.text))
                    },
                    onRetryClick = {
                        sendMessage(messages.lastOrNull { it.isUser }?.text ?: "")
                    }
                )
            }

            if (isTyping) {
                item {
                    NovaTypingDots(modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                }
            }
        }

        // Floating Glass Input Bar
        val inputShape = RoundedCornerShape(28.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .depthSurface(shape = inputShape, elevation = 4.dp)
                .clip(inputShape)
                .background(colors.surfaceGlass)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NovaTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = "Message ${appState.assistantName}...",
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary)))
                    .clickable(enabled = inputText.isNotBlank() && !isTyping) { sendMessage(inputText) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = colors.onAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
