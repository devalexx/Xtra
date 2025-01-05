package com.github.andreyasadchy.xtra.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Badge
import com.github.andreyasadchy.xtra.model.chat.ChannelPointReward
import com.github.andreyasadchy.xtra.model.chat.ChannelPoll
import com.github.andreyasadchy.xtra.model.chat.ChannelPrediction
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Chatter
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.Raid
import com.github.andreyasadchy.xtra.model.chat.RecentEmote
import com.github.andreyasadchy.xtra.model.chat.RoomState
import com.github.andreyasadchy.xtra.model.chat.StvBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.repository.ApiRepository
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.ChatReadIRC
import com.github.andreyasadchy.xtra.util.chat.ChatReadWebSocket
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.util.chat.ChatWriteIRC
import com.github.andreyasadchy.xtra.util.chat.ChatWriteWebSocket
import com.github.andreyasadchy.xtra.util.chat.EventSubUtils
import com.github.andreyasadchy.xtra.util.chat.EventSubWebSocket
import com.github.andreyasadchy.xtra.util.chat.PubSubUtils
import com.github.andreyasadchy.xtra.util.chat.PubSubWebSocket
import com.github.andreyasadchy.xtra.util.chat.RecentMessageUtils
import com.github.andreyasadchy.xtra.util.chat.StvEventApiWebSocket
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.collections.set
import kotlin.text.equals


@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val repository: ApiRepository,
    private val graphQLRepository: GraphQLRepository,
    private val playerRepository: PlayerRepository,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    val integrity = MutableStateFlow<String?>(null)

    private var chatReadIRC: ChatReadIRC? = null
    private var chatWriteIRC: ChatWriteIRC? = null
    private var chatReadWebSocket: ChatReadWebSocket? = null
    private var chatWriteWebSocket: ChatWriteWebSocket? = null
    private var eventSub: EventSubWebSocket? = null
    private var pubSub: PubSubWebSocket? = null
    private var stvEventApi: StvEventApiWebSocket? = null
    private var stvUserId: String? = null
    private var stvLastPresenceUpdate: Long? = null
    private val allEmotes = mutableListOf<Emote>()
    private var usedRaidId: String? = null

    private var chatReplayManager: ChatReplayManager? = null
    private var chatReplayManagerLocal: ChatReplayManagerLocal? = null

    val recentEmotes by lazy { playerRepository.loadRecentEmotesFlow() }
    val hasRecentEmotes = MutableStateFlow(false)
    private val _userEmotes = MutableStateFlow<List<Emote>?>(null)
    val userEmotes: StateFlow<List<Emote>?> = _userEmotes
    private var loadedUserEmotes = false
    private val _userPersonalEmoteSet = MutableStateFlow<Pair<String, List<Emote>>?>(null)
    val userPersonalEmoteSet: StateFlow<Pair<String, List<Emote>>?> = _userPersonalEmoteSet
    private val _localTwitchEmotes = MutableStateFlow<List<TwitchEmote>?>(null)
    val localTwitchEmotes: StateFlow<List<TwitchEmote>?> = _localTwitchEmotes
    private val _globalStvEmotes = MutableStateFlow<List<Emote>?>(null)
    val globalStvEmotes: StateFlow<List<Emote>?> = _globalStvEmotes
    private val _channelStvEmotes = MutableStateFlow<List<Emote>?>(null)
    val channelStvEmotes: StateFlow<List<Emote>?> = _channelStvEmotes
    private val _globalBttvEmotes = MutableStateFlow<List<Emote>?>(null)
    val globalBttvEmotes: StateFlow<List<Emote>?> = _globalBttvEmotes
    private val _channelBttvEmotes = MutableStateFlow<List<Emote>?>(null)
    val channelBttvEmotes: StateFlow<List<Emote>?> = _channelBttvEmotes
    private val _globalFfzEmotes = MutableStateFlow<List<Emote>?>(null)
    val globalFfzEmotes: StateFlow<List<Emote>?> = _globalFfzEmotes
    private val _channelFfzEmotes = MutableStateFlow<List<Emote>?>(null)
    val channelFfzEmotes: StateFlow<List<Emote>?> = _channelFfzEmotes
    private val _globalBadges = MutableStateFlow<List<TwitchBadge>?>(null)
    val globalBadges: StateFlow<List<TwitchBadge>?> = _globalBadges
    private val _channelBadges = MutableStateFlow<List<TwitchBadge>?>(null)
    val channelBadges: StateFlow<List<TwitchBadge>?> = _channelBadges
    private val _cheerEmotes = MutableStateFlow<List<CheerEmote>?>(null)
    val cheerEmotes: StateFlow<List<CheerEmote>?> = _cheerEmotes

    val roomState = MutableStateFlow<RoomState?>(null)
    val raid = MutableStateFlow<Raid?>(null)
    val raidClicked = MutableStateFlow<Raid?>(null)
    var raidClosed = false
    val poll = MutableStateFlow<ChannelPoll?>(null)
    var closedPollId: String? = null
    val prediction = MutableStateFlow<ChannelPrediction?>(null)
    var closedPredictionId: String? = null
    private val _streamInfo = MutableStateFlow<PubSubUtils.StreamInfo?>(null)
    val streamInfo: StateFlow<PubSubUtils.StreamInfo?> = _streamInfo
    private val _playbackMessage = MutableStateFlow<PubSubUtils.PlaybackMessage?>(null)
    val playbackMessage: StateFlow<PubSubUtils.PlaybackMessage?> = _playbackMessage
    var streamId: String? = null
    private val rewardList = mutableListOf<ChatMessage>()
    val namePaints = mutableListOf<NamePaint>()
    val newPaint = MutableStateFlow<NamePaint?>(null)
    val paintUsers = mutableMapOf<String, String>()
    val newPaintUser = MutableStateFlow<Pair<String, String>?>(null)
    val stvBadges = mutableListOf<StvBadge>()
    val newStvBadge = MutableStateFlow<StvBadge?>(null)
    val stvBadgeUsers = mutableMapOf<String, String>()
    val newStvBadgeUser = MutableStateFlow<Pair<String, String>?>(null)
    val personalEmoteSets = mutableMapOf<String, List<Emote>>()
    val newPersonalEmoteSet = MutableStateFlow<Pair<String, List<Emote>>?>(null)
    val personalEmoteSetUsers = mutableMapOf<String, String>()
    val newPersonalEmoteSetUser = MutableStateFlow<Pair<String, String>?>(null)
    var channelStvEmoteSetId: String? = null

    val reloadMessages = MutableStateFlow(false)
    val scrollDown = MutableStateFlow(false)
    val hideRaid = MutableStateFlow(false)

    private var messageLimit = 600
    private val _chatMessages = MutableStateFlow<MutableList<ChatMessage>>(Collections.synchronizedList(ArrayList(messageLimit + 1)))
    val chatMessages: StateFlow<MutableList<ChatMessage>> = _chatMessages
    val newMessage = MutableStateFlow<ChatMessage?>(null)
    val chatters = ConcurrentHashMap<String?, Chatter>()
    val newChatter = MutableStateFlow<Chatter?>(null)

    fun startLive(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?) {
        if (chatReadIRC == null && chatReadWebSocket == null && eventSub == null && channelLogin != null) {
            messageLimit = applicationContext.prefs().getInt(C.CHAT_LIMIT, 600)
            this.streamId = streamId
            startLiveChat(channelId, channelLogin)
            addChatter(channelName)
            loadEmotes(channelId, channelLogin)
            if (applicationContext.prefs().getBoolean(C.CHAT_RECENT, true)) {
                loadRecentMessages(channelLogin)
            }
            val isLoggedIn = !applicationContext.tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                    (!TwitchApiHelper.getGQLHeaders(applicationContext, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                            !TwitchApiHelper.getHelixHeaders(applicationContext)[C.HEADER_TOKEN].isNullOrBlank())
            if (isLoggedIn) {
                loadUserEmotes(channelId)
            }
        }
    }

    fun startReplay(channelId: String?, channelLogin: String?, chatUrl: String? = null, videoId: String? = null, startTime: Int = 0, getCurrentPosition: () -> Long?, getCurrentSpeed: () -> Float?) {
        if (chatReplayManager == null && chatReplayManagerLocal == null) {
            messageLimit = applicationContext.prefs().getInt(C.CHAT_LIMIT, 600)
            startReplayChat(videoId, startTime, chatUrl, getCurrentPosition, getCurrentSpeed, channelId, channelLogin)
            if (videoId != null) {
                loadEmotes(channelId, channelLogin)
            }
        }
    }

    fun resumeLive(channelId: String?, channelLogin: String?) {
        if ((chatReadIRC != null || chatReadWebSocket != null || eventSub != null) && channelLogin != null) {
            startLiveChat(channelId, channelLogin)
        }
    }

    fun resumeReplay(channelId: String?, channelLogin: String?, chatUrl: String?, videoId: String?, startTime: Int, getCurrentPosition: () -> Long?, getCurrentSpeed: () -> Float?) {
        if (chatReplayManager != null || chatReplayManagerLocal != null) {
            startReplayChat(videoId, startTime, chatUrl, getCurrentPosition, getCurrentSpeed, channelId, channelLogin)
        }
    }

    override fun onCleared() {
        stopLiveChat()
        stopReplayChat()
        super.onCleared()
    }

    private fun addEmotes(list: List<Emote>) {
        allEmotes.addAll(list.filter { it !in allEmotes })
    }

    private fun loadEmotes(channelId: String?, channelLogin: String?) {
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val emoteQuality = applicationContext.prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animateGifs = applicationContext.prefs().getBoolean(C.ANIMATED_EMOTES, true)
        val checkIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                applicationContext.prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
        savedGlobalBadges.also { saved ->
            if (!saved.isNullOrEmpty()) {
                _globalBadges.value = saved
                if (!reloadMessages.value) {
                    reloadMessages.value = true
                }
            } else {
                viewModelScope.launch {
                    try {
                        repository.loadGlobalBadges(helixHeaders, gqlHeaders, emoteQuality, checkIntegrity).let { badges ->
                            if (badges.isNotEmpty()) {
                                savedGlobalBadges = badges
                                _globalBadges.value = badges
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global badges", e)
                        if (e.message == "failed integrity check" && integrity.value == null) {
                            integrity.value = "refresh"
                        }
                    }
                }
            }
        }
        if (applicationContext.prefs().getBoolean(C.CHAT_ENABLE_STV, true)) {
            savedGlobalStvEmotes.also { saved ->
                if (!saved.isNullOrEmpty()) {
                    addEmotes(saved)
                    _globalStvEmotes.value = saved
                    if (!reloadMessages.value) {
                        reloadMessages.value = true
                    }
                } else {
                    viewModelScope.launch {
                        try {
                            playerRepository.loadGlobalStvEmotes().let { emotes ->
                                if (emotes.isNotEmpty()) {
                                    savedGlobalStvEmotes = emotes
                                    addEmotes(emotes)
                                    _globalStvEmotes.value = emotes
                                    if (!reloadMessages.value) {
                                        reloadMessages.value = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load global 7tv emotes", e)
                        }
                    }
                }
            }
            if (!channelId.isNullOrBlank()) {
                viewModelScope.launch {
                    try {
                        playerRepository.loadStvEmotes(channelId).let {
                            if (it.second.isNotEmpty()) {
                                channelStvEmoteSetId = it.first
                                addEmotes(it.second)
                                _channelStvEmotes.value = it.second
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load 7tv emotes for channel $channelId", e)
                    }
                }
            }
        }
        if (applicationContext.prefs().getBoolean(C.CHAT_ENABLE_BTTV, true)) {
            savedGlobalBttvEmotes.also { saved ->
                if (!saved.isNullOrEmpty()) {
                    addEmotes(saved)
                    _globalBttvEmotes.value = saved
                    if (!reloadMessages.value) {
                        reloadMessages.value = true
                    }
                } else {
                    viewModelScope.launch {
                        try {
                            playerRepository.loadGlobalBttvEmotes().let { emotes ->
                                if (emotes.isNotEmpty()) {
                                    savedGlobalBttvEmotes = emotes
                                    addEmotes(emotes)
                                    _globalBttvEmotes.value = emotes
                                    if (!reloadMessages.value) {
                                        reloadMessages.value = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load global BTTV emotes", e)
                        }
                    }
                }
            }
            if (!channelId.isNullOrBlank()) {
                viewModelScope.launch {
                    try {
                        playerRepository.loadBttvEmotes(channelId).let {
                            if (it.isNotEmpty()) {
                                addEmotes(it)
                                _channelBttvEmotes.value = it
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load BTTV emotes for channel $channelId", e)
                    }
                }
            }
        }
        if (applicationContext.prefs().getBoolean(C.CHAT_ENABLE_FFZ, true)) {
            savedGlobalFfzEmotes.also { saved ->
                if (!saved.isNullOrEmpty()) {
                    addEmotes(saved)
                    _globalFfzEmotes.value = saved
                    if (!reloadMessages.value) {
                        reloadMessages.value = true
                    }
                } else {
                    viewModelScope.launch {
                        try {
                            playerRepository.loadGlobalFfzEmotes().let { emotes ->
                                if (emotes.isNotEmpty()) {
                                    savedGlobalFfzEmotes = emotes
                                    addEmotes(emotes)
                                    _globalFfzEmotes.value = emotes
                                    if (!reloadMessages.value) {
                                        reloadMessages.value = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load global FFZ emotes", e)
                        }
                    }
                }
            }
            if (!channelId.isNullOrBlank()) {
                viewModelScope.launch {
                    try {
                        playerRepository.loadFfzEmotes(channelId).let {
                            if (it.isNotEmpty()) {
                                addEmotes(it)
                                _channelFfzEmotes.value = it
                                if (!reloadMessages.value) {
                                    reloadMessages.value = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load FFZ emotes for channel $channelId", e)
                    }
                }
            }
        }
        if (!channelId.isNullOrBlank() || !channelLogin.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    repository.loadChannelBadges(helixHeaders, gqlHeaders, channelId, channelLogin, emoteQuality, checkIntegrity).let {
                        if (it.isNotEmpty()) {
                            _channelBadges.value = it
                            if (!reloadMessages.value) {
                                reloadMessages.value = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load badges for channel $channelId", e)
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refresh"
                    }
                }
            }
            viewModelScope.launch {
                try {
                    repository.loadCheerEmotes(helixHeaders, gqlHeaders, channelId, channelLogin, animateGifs, checkIntegrity).let {
                        if (it.isNotEmpty()) {
                            _cheerEmotes.value = it
                            if (!reloadMessages.value) {
                                reloadMessages.value = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cheermotes for channel $channelId", e)
                    if (e.message == "failed integrity check" && integrity.value == null) {
                        integrity.value = "refresh"
                    }
                }
            }
        }
    }

    private fun loadUserEmotes(channelId: String?) {
        savedUserEmotes.also { saved ->
            if (!saved.isNullOrEmpty()) {
                addEmotes(
                    saved.map {
                        Emote(
                            name = it.name,
                            url1x = it.url1x,
                            url2x = it.url2x,
                            url3x = it.url3x,
                            url4x = it.url4x,
                            format = it.format
                        )
                    }
                )
                _userEmotes.value = saved.sortedByDescending { it.ownerId == channelId }.map {
                    Emote(
                        name = it.name,
                        url1x = it.url1x,
                        url2x = it.url2x,
                        url3x = it.url3x,
                        url4x = it.url4x,
                        format = it.format
                    )
                }
            } else {
                val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
                val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
                if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        try {
                            val accountId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
                            val animateGifs =  applicationContext.prefs().getBoolean(C.ANIMATED_EMOTES, true)
                            val checkIntegrity = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false) &&
                                    applicationContext.prefs().getBoolean(C.USE_WEBVIEW_INTEGRITY, true)
                            repository.loadUserEmotes(helixHeaders, gqlHeaders, channelId, accountId, animateGifs, checkIntegrity).let { emotes ->
                                if (emotes.isNotEmpty()) {
                                    val sorted = emotes.sortedByDescending { it.setId }
                                    addEmotes(
                                        sorted.map {
                                            Emote(
                                                name = it.name,
                                                url1x = it.url1x,
                                                url2x = it.url2x,
                                                url3x = it.url3x,
                                                url4x = it.url4x,
                                                format = it.format
                                            )
                                        }
                                    )
                                    _userEmotes.value = sorted.sortedByDescending { it.ownerId == channelId }.map {
                                        Emote(
                                            name = it.name,
                                            url1x = it.url1x,
                                            url2x = it.url2x,
                                            url3x = it.url3x,
                                            url4x = it.url4x,
                                            format = it.format
                                        )
                                    }
                                    loadedUserEmotes = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load user emotes", e)
                            if (e.message == "failed integrity check" && integrity.value == null) {
                                integrity.value = "refresh"
                            }
                        }
                    }
                }
            }
        }
    }

    fun loadRecentEmotes() {
        viewModelScope.launch {
            hasRecentEmotes.value = playerRepository.loadRecentEmotes().isNotEmpty()
        }
    }

    fun getEmoteBytes(chatUrl: String, localData: Pair<Long, Int>): ByteArray? {
        return if (chatUrl.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
            applicationContext.contentResolver.openInputStream(chatUrl.toUri())?.bufferedReader()
        } else {
            FileInputStream(File(chatUrl)).bufferedReader()
        }?.use { fileReader ->
            val buffer = CharArray(localData.second)
            fileReader.skip(localData.first)
            fileReader.read(buffer, 0, localData.second)
            Base64.decode(buffer.concatToString(), Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }

    fun reloadEmotes(channelId: String?, channelLogin: String?) {
        savedGlobalBadges = null
        savedGlobalStvEmotes = null
        savedGlobalBttvEmotes = null
        savedGlobalFfzEmotes = null
        loadEmotes(channelId, channelLogin)
    }

    fun loadRecentMessages(channelLogin: String) {
        viewModelScope.launch {
            try {
                val list = mutableListOf<ChatMessage>()
                playerRepository.loadRecentMessages(channelLogin, applicationContext.prefs().getInt(C.CHAT_RECENT_LIMIT, 100).toString()).messages.forEach { message ->
                    when {
                        message.contains("PRIVMSG") -> RecentMessageUtils.parseChatMessage(message, false)
                        message.contains("USERNOTICE") -> {
                            if (applicationContext.prefs().getBoolean(C.CHAT_SHOW_USERNOTICE, true)) {
                                RecentMessageUtils.parseChatMessage(message, true)
                            } else null
                        }
                        message.contains("CLEARMSG") -> {
                            if (applicationContext.prefs().getBoolean(C.CHAT_SHOW_CLEARMSG, true)) {
                                val pair = RecentMessageUtils.parseClearMessage(message)
                                val deletedMessage = pair.second?.let { targetId -> list.find { it.id == targetId } }
                                getClearMessage(pair.first, deletedMessage, applicationContext.prefs().getString(C.UI_NAME_DISPLAY, "0"))
                            } else null
                        }
                        message.contains("CLEARCHAT") -> {
                            if (applicationContext.prefs().getBoolean(C.CHAT_SHOW_CLEARCHAT, true)) {
                                RecentMessageUtils.parseClearChat(applicationContext, message)
                            } else null
                        }
                        message.contains("NOTICE") -> RecentMessageUtils.parseNotice(applicationContext, message)
                        else -> null
                    }?.let { list.add(it) }
                }
                if (list.isNotEmpty()) {
                    _chatMessages.value = mutableListOf<ChatMessage>().apply {
                        addAll(list)
                        addAll(_chatMessages.value)
                    }
                    if (!scrollDown.value) {
                        scrollDown.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load recent messages for channel $channelLogin", e)
            }
        }
    }

    private fun getClearMessage(chatMessage: ChatMessage, deletedMessage: ChatMessage?, nameDisplay: String?): ChatMessage {
        val login = deletedMessage?.userLogin ?: chatMessage.userLogin
        val userName = if (deletedMessage?.userName != null && login != null && !login.equals(deletedMessage.userName, true)) {
            when (nameDisplay) {
                "0" -> "${deletedMessage.userName}(${login})"
                "1" -> deletedMessage.userName
                else -> login
            }
        } else {
            deletedMessage?.userName ?: login
        }
        val message = ContextCompat.getString(applicationContext, R.string.chat_clearmsg).format(userName, deletedMessage?.message ?: chatMessage.message)
        val messageIndex = message.indexOf(": ") + 2
        return ChatMessage(
            userId = deletedMessage?.userId,
            userLogin = login,
            userName = deletedMessage?.userName,
            systemMsg = message,
            emotes = deletedMessage?.emotes?.map {
                TwitchEmote(
                    id = it.id,
                    begin = it.begin + messageIndex,
                    end = it.end + messageIndex
                )
            },
            timestamp = chatMessage.timestamp,
            fullMsg = chatMessage.fullMsg
        )
    }

    private fun onMessage(message: ChatMessage) {
        _chatMessages.value.add(message)
        newMessage.value = message
    }

    fun startLiveChat(channelId: String?, channelLogin: String) {
        stopLiveChat()
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true)
        val accountId = applicationContext.tokenPrefs().getString(C.USER_ID, null)
        val accountLogin = applicationContext.tokenPrefs().getString(C.USERNAME, null)
        val isLoggedIn = !accountLogin.isNullOrBlank() && (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank())
        val usePubSub = applicationContext.prefs().getBoolean(C.CHAT_PUBSUB_ENABLED, true)
        val showUserNotice = applicationContext.prefs().getBoolean(C.CHAT_SHOW_USERNOTICE, true)
        val showClearMsg = applicationContext.prefs().getBoolean(C.CHAT_SHOW_CLEARMSG, true)
        val showClearChat = applicationContext.prefs().getBoolean(C.CHAT_SHOW_CLEARCHAT, true)
        val nameDisplay = applicationContext.prefs().getString(C.UI_NAME_DISPLAY, "0")
        val useApiChatMessages = applicationContext.prefs().getBoolean(C.DEBUG_API_CHAT_MESSAGES, false)
        if (applicationContext.prefs().getBoolean(C.DEBUG_EVENTSUB_CHAT, false) && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            eventSub = EventSubWebSocket(
                client = okHttpClient,
                coroutineScope = viewModelScope,
                onConnect = { onConnect(channelLogin) },
                onWelcomeMessage = { sessionId ->
                    listOf(
                        "channel.chat.clear",
                        "channel.chat.message",
                        "channel.chat.notification",
                        "channel.chat_settings.update",
                    ).forEach {
                        viewModelScope.launch {
                            repository.createChatEventSubSubscription(helixHeaders, accountId, channelId, it, sessionId)?.let {
                                onMessage(ChatMessage(systemMsg = it))
                            }
                        }
                    }
                },
                onChatMessage = { json, timestamp ->
                    val chatMessage = EventSubUtils.parseChatMessage(json, timestamp)
                    if (usePubSub && chatMessage.reward != null && !chatMessage.reward.id.isNullOrBlank()) {
                        onRewardMessage(chatMessage, isLoggedIn, accountId, channelId)
                    } else {
                        onChatMessage(chatMessage, isLoggedIn, accountId, channelId)
                    }
                },
                onUserNotice = { json, timestamp ->
                    if (showUserNotice) {
                        onChatMessage(EventSubUtils.parseUserNotice(json, timestamp), isLoggedIn, accountId, channelId)
                    }
                },
                onClearChat = { json, timestamp ->
                    if (showClearChat) {
                        onMessage(EventSubUtils.parseClearChat(applicationContext, json, timestamp))
                    }
                },
                onRoomState = { json, timestamp ->
                    roomState.value = EventSubUtils.parseRoomState(json)
                },
            ).apply { connect() }
        } else {
            val gqlToken = gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth ")
            val helixToken = helixHeaders[C.HEADER_TOKEN]?.removePrefix("Bearer ")
            if (applicationContext.prefs().getBoolean(C.CHAT_USE_WEBSOCKET, false)) {
                chatReadWebSocket = ChatReadWebSocket(
                    loggedIn = isLoggedIn,
                    channelName = channelLogin,
                    client = okHttpClient,
                    coroutineScope = viewModelScope,
                    onConnect = { onConnect(channelLogin) },
                    onDisconnect = { message, fullMsg -> onDisconnect(channelLogin, message, fullMsg) },
                    onChatMessage = { message, fullMsg -> onChatMessage(message, fullMsg, showUserNotice, usePubSub, isLoggedIn, accountId, channelId) },
                    onClearMessage = { if (showClearMsg) { onClearMessage(it, nameDisplay) } },
                    onClearChat = { if (showClearChat) { onClearChat(it) } },
                    onNotice = { onNotice(it) },
                    onRoomState = { onRoomState(it) }
                ).apply { connect() }
                if (isLoggedIn && (!gqlToken.isNullOrBlank() || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank() && !useApiChatMessages)) {
                    chatWriteWebSocket = ChatWriteWebSocket(
                        userLogin = accountLogin,
                        userToken = gqlToken?.takeIf { it.isNotBlank() } ?: helixToken,
                        channelName = channelLogin,
                        client = okHttpClient,
                        coroutineScope = viewModelScope,
                        onNotice = { onNotice(it) },
                        onUserState = { onUserState(it, channelId) }
                    ).apply { connect() }
                }
            } else {
                val useSSL = applicationContext.prefs().getBoolean(C.CHAT_USE_SSL, true)
                chatReadIRC = ChatReadIRC(
                    useSSL = useSSL,
                    loggedIn = isLoggedIn,
                    channelName = channelLogin,
                    onConnect = { onConnect(channelLogin) },
                    onDisconnect = { message, fullMsg -> onDisconnect(channelLogin, message, fullMsg) },
                    onChatMessage = { message, fullMsg -> onChatMessage(message, fullMsg, showUserNotice, usePubSub, isLoggedIn, accountId, channelId) },
                    onClearMessage = { if (showClearMsg) { onClearMessage(it, nameDisplay) } },
                    onClearChat = { if (showClearChat) { onClearChat(it) } },
                    onNotice = { onNotice(it) },
                    onRoomState = { onRoomState(it) }
                ).apply { start() }
                if (isLoggedIn && (!gqlToken.isNullOrBlank() || !helixHeaders[C.HEADER_TOKEN].isNullOrBlank() && !useApiChatMessages)) {
                    chatWriteIRC = ChatWriteIRC(
                        useSSL = useSSL,
                        userLogin = accountLogin,
                        userToken = gqlToken?.takeIf { it.isNotBlank() } ?: helixToken,
                        channelName = channelLogin,
                        onSendMessageError = { message, fullMsg -> onSendMessageError(message, fullMsg) },
                        onNotice = { onNotice(it) },
                        onUserState = { onUserState(it, channelId) }
                    ).apply { start() }
                }
            }
        }
        if (usePubSub && !channelId.isNullOrBlank()) {
            val collectPoints = applicationContext.prefs().getBoolean(C.CHAT_POINTS_COLLECT, true)
            pubSub = PubSubWebSocket(
                channelId = channelId,
                userId = accountId,
                gqlToken = gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth "),
                collectPoints = collectPoints,
                notifyPoints = applicationContext.prefs().getBoolean(C.CHAT_POINTS_NOTIFY, false),
                showRaids = applicationContext.prefs().getBoolean(C.CHAT_RAIDS_SHOW, true),
                showPolls = applicationContext.prefs().getBoolean(C.CHAT_POLLS_SHOW, true),
                showPredictions = applicationContext.prefs().getBoolean(C.CHAT_PREDICTIONS_SHOW, true),
                client = okHttpClient,
                coroutineScope = viewModelScope,
                onPlaybackMessage = { message ->
                    val playbackMessage = PubSubUtils.parsePlaybackMessage(message)
                    if (playbackMessage != null) {
                        playbackMessage.live?.let {
                            if (it) {
                                onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.stream_live).format(channelLogin)))
                            } else {
                                onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.stream_offline).format(channelLogin)))
                            }
                        }
                        _playbackMessage.value = playbackMessage
                    }
                },
                onStreamInfo = { message ->
                    _streamInfo.value = PubSubUtils.parseStreamInfo(message)
                },
                onRewardMessage = { message ->
                    val chatMessage = PubSubUtils.parseRewardMessage(message)
                    if (!chatMessage.message.isNullOrBlank()) {
                        onRewardMessage(chatMessage, isLoggedIn, accountId, channelId)
                    } else {
                        onChatMessage(chatMessage, isLoggedIn, accountId, channelId)
                    }
                },
                onPointsEarned = { message ->
                    val points = PubSubUtils.parsePointsEarned(message)
                    onMessage(ChatMessage(
                        systemMsg = ContextCompat.getString(applicationContext, R.string.points_earned).format(points.pointsGained),
                        timestamp = points.timestamp,
                        fullMsg = points.fullMsg
                    ))
                },
                onClaimAvailable = {
                    if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        viewModelScope.launch {
                            try {
                                val response = graphQLRepository.loadChannelPointsContext(gqlHeaders, channelLogin)
                                response.errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
                                response.data?.community?.channel?.self?.communityPoints?.availableClaim?.id?.let { claimId ->
                                    graphQLRepository.loadClaimPoints(gqlHeaders, channelId, claimId).errors?.find { it.message == "failed integrity check" }?.let { throw Exception(it.message) }
                                }
                            } catch (e: Exception) {
                                if (e.message == "failed integrity check" && integrity.value == null) {
                                    integrity.value = "refresh"
                                }
                            }
                        }
                    }
                },
                onMinuteWatched = {
                    if (!streamId.isNullOrBlank()) {
                        viewModelScope.launch {
                            repository.loadMinuteWatched(accountId, streamId, channelId, channelLogin)
                        }
                    }
                },
                onRaidUpdate = { message, openStream ->
                    PubSubUtils.onRaidUpdate(message, openStream)?.let {
                        if (it.raidId != usedRaidId) {
                            usedRaidId = it.raidId
                            raidClosed = false
                            if (collectPoints && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                                viewModelScope.launch {
                                    try {
                                        repository.loadJoinRaid(gqlHeaders, it.raidId)
                                    } catch (e: Exception) {
                                        if (e.message == "failed integrity check" && integrity.value == null) {
                                            integrity.value = "refresh"
                                        }
                                    }
                                }
                            }
                        }
                        raid.value = it
                    }
                },
                onPollUpdate = { message ->
                    PubSubUtils.onPollUpdate(message)?.let { poll.value = it }
                },
                onPredictionUpdate = { message ->
                    PubSubUtils.onPredictionUpdate(message)?.let { prediction.value = it }
                },
            ).apply { connect() }
        }
        val showNamePaints = applicationContext.prefs().getBoolean(C.CHAT_SHOW_PAINTS, true)
        val showStvBadges = applicationContext.prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true)
        val showPersonalEmotes = applicationContext.prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true)
        val stvLiveUpdates = applicationContext.prefs().getBoolean(C.CHAT_STV_LIVE_UPDATES, true)
        if ((showNamePaints || showStvBadges || showPersonalEmotes || stvLiveUpdates) && !channelId.isNullOrBlank()) {
            stvEventApi = StvEventApiWebSocket(
                channelId = channelId,
                client = okHttpClient,
                coroutineScope = viewModelScope,
                onPaint = { paint ->
                    if (showNamePaints) {
                        namePaints.find { it.id == paint.id }?.let { namePaints.remove(it) }
                        namePaints.add(paint)
                        newPaint.value = paint
                    }
                },
                onBadge = { badge ->
                    if (showStvBadges) {
                        stvBadges.find { it.id == badge.id }?.let { stvBadges.remove(it) }
                        stvBadges.add(badge)
                        newStvBadge.value = badge
                    }
                },
                onEmoteSet = { setId, added, removed, updated ->
                    if (setId == channelStvEmoteSetId) {
                        if (stvLiveUpdates) {
                            val removedEmotes = (removed + updated.map { it.first }).map { it.name }
                            val newEmotes = added + updated.map { it.second }
                            allEmotes.removeAll { it.name in removedEmotes }
                            allEmotes.addAll(newEmotes.filter { it !in allEmotes })
                            val existingSet = channelStvEmotes.value?.filter { it.name !in removedEmotes } ?: emptyList()
                            _channelStvEmotes.value = existingSet + newEmotes
                            if (!reloadMessages.value) {
                                reloadMessages.value = true
                            }
                        }
                    } else {
                        if (showPersonalEmotes) {
                            val removedEmotes = (removed + updated.map { it.first }).map { it.name }
                            val existingSet = personalEmoteSets[setId]?.filter { it.name !in removedEmotes } ?: emptyList()
                            personalEmoteSets.remove(setId)
                            val set = existingSet + added + updated.map { it.second }
                            personalEmoteSets.put(setId, set)
                            newPersonalEmoteSet.value = Pair(setId, set)
                            if (isLoggedIn && !accountId.isNullOrBlank() && setId == _userPersonalEmoteSet.value?.first) {
                                _userPersonalEmoteSet.value = Pair(setId, set)
                            }
                        }
                    }
                },
                onPaintUser = { userId, paintId ->
                    if (showNamePaints) {
                        val item = paintUsers.entries.find { it.key == userId }
                        if (item == null || item.value != paintId) {
                            item?.let { paintUsers.remove(it.key) }
                            paintUsers.put(userId, paintId)
                            newPaintUser.value = Pair(userId, paintId)
                        }
                    }
                },
                onBadgeUser = { userId, badgeId ->
                    if (showStvBadges) {
                        val item = stvBadgeUsers.entries.find { it.key == userId }
                        if (item == null || item.value != badgeId) {
                            item?.let { stvBadgeUsers.remove(it.key) }
                            stvBadgeUsers.put(userId, badgeId)
                            newStvBadgeUser.value = Pair(userId, badgeId)
                        }
                    }
                },
                onEmoteSetUser = { userId, setId ->
                    if (showPersonalEmotes) {
                        val item = personalEmoteSetUsers.entries.find { it.key == userId }
                        if (item == null || item.value != setId) {
                            item?.let { personalEmoteSetUsers.remove(it.key) }
                            personalEmoteSetUsers.put(userId, setId)
                            newPersonalEmoteSetUser.value = Pair(userId, setId)
                            if (isLoggedIn && !accountId.isNullOrBlank() && userId == accountId) {
                                _userPersonalEmoteSet.value = Pair(setId, personalEmoteSets[setId] ?: emptyList())
                            }
                        }
                    }
                },
                onUpdatePresence = { sessionId ->
                    onUpdatePresence(sessionId, channelId, true)
                }
            ).apply { connect() }
            if (isLoggedIn && !accountId.isNullOrBlank()) {
                viewModelScope.launch {
                    try {
                        stvUserId = playerRepository.getStvUser(accountId).takeIf { !it.isNullOrBlank() }
                    } catch (e: Exception) {

                    }
                }
            }
        }
    }

    fun stopLiveChat() {
        chatReadIRC?.disconnect() ?: chatReadWebSocket?.disconnect() ?: eventSub?.disconnect()
        chatWriteIRC?.disconnect() ?: chatWriteWebSocket?.disconnect()
        pubSub?.disconnect()
        stvEventApi?.disconnect()
    }

    fun isActive(): Boolean? {
        return chatReadIRC?.isActive ?: chatReadWebSocket?.isActive ?: eventSub?.isActive
    }

    fun disconnect() {
        if (isActive() == true) {
            stopLiveChat()
            usedRaidId = null
            raidClosed = true
            _chatMessages.value = arrayListOf(
                ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.disconnected))
            )
            if (!hideRaid.value) {
                hideRaid.value = true
            }
            roomState.value = RoomState("0", "-1", "0", "0", "0")
        }
    }

    private fun onConnect(channelLogin: String?) {
        onMessage(ChatMessage(systemMsg = ContextCompat.getString(applicationContext, R.string.chat_join).format(channelLogin)))
    }

    private fun onDisconnect(channelLogin: String?, message: String, fullMsg: String) {
        onMessage(ChatMessage(
            systemMsg = ContextCompat.getString(applicationContext, R.string.chat_disconnect).format(channelLogin, message),
            fullMsg = fullMsg
        ))
    }

    private fun onSendMessageError(message: String, fullMsg: String) {
        onMessage(ChatMessage(
            systemMsg = ContextCompat.getString(applicationContext, R.string.chat_send_msg_error).format(message),
            fullMsg = fullMsg
        ))
    }

    private fun onChatMessage(message: String, userNotice: Boolean, showUserNotice: Boolean, usePubSub: Boolean, isLoggedIn: Boolean, accountId: String?, channelId: String?) {
        if (!userNotice || showUserNotice) {
            val chatMessage = ChatUtils.parseChatMessage(message, userNotice)
            if (usePubSub && chatMessage.reward != null && !chatMessage.reward.id.isNullOrBlank()) {
                onRewardMessage(chatMessage, isLoggedIn, accountId, channelId)
            } else {
                onChatMessage(chatMessage, isLoggedIn, accountId, channelId)
            }
        }
    }

    private fun onClearMessage(message: String, nameDisplay: String?) {
        val pair = ChatUtils.parseClearMessage(message)
        val deletedMessage = pair.second?.let { targetId -> _chatMessages.value.find { it.id == targetId } }
        onMessage(getClearMessage(pair.first, deletedMessage, nameDisplay))
    }

    private fun onClearChat(message: String) {
        onMessage(ChatUtils.parseClearChat(applicationContext, message))
    }

    private fun onNotice(message: String) {
        val result = ChatUtils.parseNotice(applicationContext, message)
        onMessage(result.first)
        if (result.second) {
            if (!hideRaid.value) {
                hideRaid.value = true
            }
        }
    }

    private fun onRoomState(message: String) {
        roomState.value = ChatUtils.parseRoomState(message)
    }

    private fun onUserState(message: String, channelId: String?) {
        val emoteSets = ChatUtils.parseEmoteSets(message)
        if (emoteSets != null && savedEmoteSets != emoteSets) {
            savedEmoteSets = emoteSets
            if (!loadedUserEmotes) {
                loadEmoteSets(channelId)
            }
        }
    }

    private fun onChatMessage(message: ChatMessage, isLoggedIn: Boolean, accountId: String?, channelId: String?) {
        onMessage(message)
        addChatter(message.userName)
        if (isLoggedIn && !accountId.isNullOrBlank() && message.userId == accountId) {
            onUpdatePresence(null, channelId, false)
        }
    }

    private fun addChatter(displayName: String?) {
        if (displayName != null && !chatters.containsKey(displayName)) {
            val chatter = Chatter(displayName)
            chatters[displayName] = chatter
            newChatter.value = chatter
        }
    }

    private fun onUpdatePresence(sessionId: String?, channelId: String?, self: Boolean) {
        stvUserId?.let { stvUserId ->
            if (stvUserId.isNotBlank() && !channelId.isNullOrBlank() && (self && !sessionId.isNullOrBlank() || !self) &&
                stvLastPresenceUpdate?.let { (System.currentTimeMillis() - it) > 10000 } != false) {
                stvLastPresenceUpdate = System.currentTimeMillis()
                viewModelScope.launch {
                    try {
                        playerRepository.sendStvPresence(stvUserId, channelId, sessionId, self)
                    } catch (e: Exception) {

                    }
                }
            }
        }
    }

    private fun onRewardMessage(message: ChatMessage, isLoggedIn: Boolean, accountId: String?, channelId: String?) {
        if (message.reward?.id != null) {
            val item = rewardList.find { it.reward?.id == message.reward.id && it.userId == message.userId }
            if (item != null) {
                rewardList.remove(item)
                onChatMessage(ChatMessage(
                    id = message.id ?: item.id,
                    userId = message.userId ?: item.userId,
                    userLogin = message.userLogin ?: item.userLogin,
                    userName = message.userName ?: item.userName,
                    message = message.message ?: item.message,
                    color = message.color ?: item.color,
                    emotes = message.emotes ?: item.emotes,
                    badges = message.badges ?: item.badges,
                    isAction = message.isAction || item.isAction,
                    isFirst = message.isFirst || item.isFirst,
                    bits = message.bits ?: item.bits,
                    systemMsg = message.systemMsg ?: item.systemMsg,
                    msgId = message.msgId ?: item.msgId,
                    reward = ChannelPointReward(
                        id = message.reward.id,
                        title = message.reward.title ?: item.reward?.title,
                        cost = message.reward.cost ?: item.reward?.cost,
                        url1x = message.reward.url1x ?: item.reward?.url1x,
                        url2x = message.reward.url2x ?: item.reward?.url2x,
                        url4x = message.reward.url4x ?: item.reward?.url4x,
                    ),
                    timestamp = message.timestamp ?: item.timestamp,
                    fullMsg = message.fullMsg ?: item.fullMsg,
                ), isLoggedIn, accountId, channelId)
            } else {
                rewardList.add(message)
            }
        } else {
            onChatMessage(message, isLoggedIn, accountId, channelId)
        }
    }

    private fun loadEmoteSets(channelId: String?) {
        val helixHeaders = TwitchApiHelper.getHelixHeaders(applicationContext)
        if (!savedEmoteSets.isNullOrEmpty() && !helixHeaders[C.HEADER_CLIENT_ID].isNullOrBlank() && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    val animateGifs =  applicationContext.prefs().getBoolean(C.ANIMATED_EMOTES, true)
                    val emotes = mutableListOf<TwitchEmote>()
                    savedEmoteSets?.chunked(25)?.forEach { list ->
                        repository.loadEmotesFromSet(helixHeaders, list, animateGifs).let { emotes.addAll(it) }
                    }
                    if (emotes.isNotEmpty()) {
                        val sorted = emotes.sortedByDescending { it.setId }
                        savedUserEmotes = sorted
                        addEmotes(
                            sorted.map {
                                Emote(
                                    name = it.name,
                                    url1x = it.url1x,
                                    url2x = it.url2x,
                                    url3x = it.url3x,
                                    url4x = it.url4x,
                                    format = it.format
                                )
                            }
                        )
                        _userEmotes.value = sorted.sortedByDescending { it.ownerId == channelId }.map {
                            Emote(
                                name = it.name,
                                url1x = it.url1x,
                                url2x = it.url2x,
                                url3x = it.url3x,
                                url4x = it.url4x,
                                format = it.format
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load emote sets", e)
                }
            }
        }
    }

    fun send(message: CharSequence, replyId: String?, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, accountId: String?, channelId: String?, channelLogin: String?, useApiCommands: Boolean, useApiChatMessages: Boolean, checkIntegrity: Boolean) {
        if (replyId != null) {
            sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages, replyId)
        } else {
            if (useApiCommands) {
                if (message.toString().startsWith("/")) {
                    try {
                        sendCommand(message, helixHeaders, gqlHeaders, accountId, channelId, channelLogin, useApiChatMessages, checkIntegrity)
                    } catch (e: Exception) {
                        if (e.message == "failed integrity check" && integrity.value == null) {
                            integrity.value = "refresh"
                        }
                    }
                } else {
                    sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
                }
            } else {
                if (message.toString() == "/dc" || message.toString() == "/disconnect") {
                    if ((chatReadIRC?.isActive ?: chatReadWebSocket?.isActive ?: eventSub?.isActive) == true) {
                        disconnect()
                    }
                } else {
                    sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
                }
            }
        }
    }

    private fun sendMessage(message: CharSequence, helixHeaders: Map<String, String>, accountId: String?, channelId: String?, useApiChatMessages: Boolean, replyId: String? = null) {
        if (useApiChatMessages && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            viewModelScope.launch {
                repository.sendMessage(helixHeaders, accountId, channelId, message.toString(), replyId)?.let {
                    onMessage(ChatMessage(systemMsg = it))
                }
            }
        } else {
            chatWriteIRC?.send(message, replyId) ?: chatWriteWebSocket?.send(message, replyId)
        }
        val usedEmotes = hashSetOf<RecentEmote>()
        val currentTime = System.currentTimeMillis()
        message.split(' ').forEach { word ->
            allEmotes.find { it.name == word }?.let { usedEmotes.add(RecentEmote(word, currentTime)) }
        }
        if (usedEmotes.isNotEmpty()) {
            viewModelScope.launch {
                playerRepository.insertRecentEmotes(usedEmotes)
            }
        }
    }

    private fun sendCommand(message: CharSequence, helixHeaders: Map<String, String>, gqlHeaders: Map<String, String>, accountId: String?, channelId: String?, channelLogin: String?, useApiChatMessages: Boolean, checkIntegrity: Boolean) {
        val command = message.toString().substringBefore(" ")
        when {
            command.startsWith("/announce", true) -> {
                val splits = message.split(" ", limit = 2)
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.sendAnnouncement(
                            helixHeaders = helixHeaders,
                            userId = accountId,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            message = splits[1],
                            color = splits[0].substringAfter("/announce", "").ifBlank { null }
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/ban", true) -> {
                val splits = message.split(" ", limit = 3)
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.banUser(
                            helixHeaders = helixHeaders,
                            userId = accountId,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1],
                            reason = if (splits.size >= 3) splits[2] else null
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/unban", true) -> {
                val splits = message.split(" ")
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.unbanUser(
                            helixHeaders = helixHeaders,
                            userId = accountId,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1]
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/clear", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.deleteMessages(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/color", true) -> {
                val splits = message.split(" ")
                viewModelScope.launch {
                    if (splits.size >= 2) {
                        repository.updateChatColor(
                            helixHeaders = helixHeaders,
                            userId = accountId,
                            gqlHeaders = gqlHeaders,
                            color = splits[1]
                        )
                    } else {
                        repository.getChatColor(
                            helixHeaders = helixHeaders,
                            userId = accountId
                        )
                    }?.let { onMessage(ChatMessage(systemMsg = it)) }
                }
            }
            command.equals("/commercial", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.startCommercial(
                                helixHeaders = helixHeaders,
                                channelId = channelId,
                                length = splits[1]
                            )?.let { onMessage(ChatMessage(systemMsg = it)) }
                        }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/delete", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val splits = message.split(" ")
                    if (splits.size >= 2) {
                        viewModelScope.launch {
                            repository.deleteMessages(
                                helixHeaders = helixHeaders,
                                channelId = channelId,
                                userId = accountId,
                                messageId = splits[1]
                            )?.let { onMessage(ChatMessage(systemMsg = it)) }
                        }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/disconnect", true) -> disconnect()
            command.equals("/emoteonly", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            emote = true
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/emoteonlyoff", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            emote = false
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/followers", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val splits = message.split(" ")
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            followers = true,
                            followersDuration = if (splits.size >= 2) splits[1] else null
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/followersoff", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            followers = false
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/marker", true) -> {
                val splits = message.split(" ", limit = 2)
                viewModelScope.launch {
                    repository.createStreamMarker(
                        helixHeaders = helixHeaders,
                        channelId = channelId,
                        gqlHeaders = gqlHeaders,
                        channelLogin = channelLogin,
                        description = if (splits.size >= 2) splits[1] else null
                    )?.let { onMessage(ChatMessage(systemMsg = it)) }
                }
            }
            command.equals("/mod", true) -> {
                val splits = message.split(" ")
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.addModerator(
                            helixHeaders = helixHeaders,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1]
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/unmod", true) -> {
                val splits = message.split(" ")
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.removeModerator(
                            helixHeaders = helixHeaders,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1]
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/mods", true) -> {
                viewModelScope.launch {
                    repository.getModerators(
                        gqlHeaders = gqlHeaders,
                        channelLogin = channelLogin,
                    )?.let { onMessage(ChatMessage(systemMsg = it)) }
                }
            }
            command.equals("/raid", true) -> {
                val splits = message.split(" ")
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.startRaid(
                            helixHeaders = helixHeaders,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1],
                            checkIntegrity = checkIntegrity
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/unraid", true) -> {
                viewModelScope.launch {
                    repository.cancelRaid(
                        helixHeaders = helixHeaders,
                        gqlHeaders = gqlHeaders,
                        channelId = channelId
                    )?.let { onMessage(ChatMessage(systemMsg = it)) }
                }
            }
            command.equals("/slow", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    val splits = message.split(" ")
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            slow = true,
                            slowDuration = if (splits.size >= 2) splits[1].toIntOrNull() else null
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/slowoff", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            slow = false
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/subscribers", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            subs = true,
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/subscribersoff", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            subs = false,
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/timeout", true) -> {
                val splits = message.split(" ", limit = 4)
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.banUser(
                            helixHeaders = helixHeaders,
                            userId = accountId,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1],
                            duration = if (splits.size >= 3) splits[2] else null ?: if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) "10m" else "600",
                            reason = if (splits.size >= 4) splits[3] else null,
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/untimeout", true) -> {
                val splits = message.split(" ")
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.unbanUser(
                            helixHeaders = helixHeaders,
                            userId = accountId,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1]
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/uniquechat", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            unique = true,
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/uniquechatoff", true) -> {
                if (!helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                    viewModelScope.launch {
                        repository.updateChatSettings(
                            helixHeaders = helixHeaders,
                            channelId = channelId,
                            userId = accountId,
                            unique = false,
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                } else sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
            }
            command.equals("/vip", true) -> {
                val splits = message.split(" ")
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.addVip(
                            helixHeaders = helixHeaders,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1]
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/unvip", true) -> {
                val splits = message.split(" ")
                if (splits.size >= 2) {
                    viewModelScope.launch {
                        repository.removeVip(
                            helixHeaders = helixHeaders,
                            gqlHeaders = gqlHeaders,
                            channelId = channelId,
                            targetLogin = splits[1]
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            command.equals("/vips", true) -> {
                viewModelScope.launch {
                    repository.getVips(
                        gqlHeaders = gqlHeaders,
                        channelLogin = channelLogin,
                    )?.let { onMessage(ChatMessage(systemMsg = it)) }
                }
            }
            command.equals("/w", true) -> {
                val splits = message.split(" ", limit = 3)
                if (splits.size >= 3) {
                    viewModelScope.launch {
                        repository.sendWhisper(
                            helixHeaders = helixHeaders,
                            userId = accountId,
                            targetLogin = splits[1],
                            message = splits[2]
                        )?.let { onMessage(ChatMessage(systemMsg = it)) }
                    }
                }
            }
            else -> sendMessage(message, helixHeaders, accountId, channelId, useApiChatMessages)
        }
    }

    fun startReplayChat(videoId: String?, startTime: Int, chatUrl: String?, getCurrentPosition: () -> Long?, getCurrentSpeed: () -> Float?, channelId: String?, channelLogin: String?) {
        stopReplayChat()
        if (!chatUrl.isNullOrBlank()) {
            chatReplayManagerLocal = ChatReplayManagerLocal(
                getCurrentPosition = getCurrentPosition,
                getCurrentSpeed = getCurrentSpeed,
                onMessage = { onMessage(it) },
                clearMessages = { _chatMessages.value = ArrayList() },
                coroutineScope = viewModelScope
            )
            readChatFile(chatUrl, channelId, channelLogin)
        } else {
            if (!videoId.isNullOrBlank()) {
                chatReplayManager = ChatReplayManager(
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
                    repository = repository,
                    videoId = videoId,
                    startTimeSeconds = startTime,
                    getCurrentPosition = getCurrentPosition,
                    getCurrentSpeed = getCurrentSpeed,
                    onMessage = { onMessage(it) },
                    clearMessages = { _chatMessages.value = ArrayList() },
                    getIntegrityToken = { if (integrity.value == null) { integrity.value = "refresh" } },
                    coroutineScope = viewModelScope
                ).apply { start() }
            }
        }
    }

    fun stopReplayChat() {
        chatReplayManager?.stop() ?: chatReplayManagerLocal?.stop()
    }

    fun updatePosition(position: Long) {
        chatReplayManager?.updatePosition(position) ?: chatReplayManagerLocal?.updatePosition(position)
    }

    fun updateSpeed(speed: Float) {
        chatReplayManager?.updateSpeed(speed) ?: chatReplayManagerLocal?.updateSpeed(speed)
    }

    private fun readChatFile(url: String, channelId: String?, channelLogin: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nameDisplay = applicationContext.prefs().getString(C.UI_NAME_DISPLAY, "0")
                val messages = mutableListOf<ChatMessage>()
                var startTimeMs = 0L
                val twitchEmotes = mutableListOf<TwitchEmote>()
                val twitchBadges = mutableListOf<TwitchBadge>()
                val cheerEmotesList = mutableListOf<CheerEmote>()
                val emotes = mutableListOf<Emote>()
                if (url.toUri().scheme == ContentResolver.SCHEME_CONTENT) {
                    applicationContext.contentResolver.openInputStream(url.toUri())?.bufferedReader()
                } else {
                    FileInputStream(File(url)).bufferedReader()
                }?.use { fileReader ->
                    JsonReader(fileReader).use { reader ->
                        reader.isLenient = true
                        var position = 0L
                        var token: JsonToken
                        do {
                            token = reader.peek()
                            when (token) {
                                JsonToken.END_DOCUMENT -> {}
                                JsonToken.BEGIN_OBJECT -> {
                                    reader.beginObject().also { position += 1 }
                                    while (reader.hasNext()) {
                                        when (reader.peek()) {
                                            JsonToken.NAME -> {
                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                    "liveStartTime" -> { TwitchApiHelper.parseIso8601DateUTC(reader.nextString().also { position += it.length + 2 })?.let { startTimeMs = it } }
                                                    "liveComments" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            val message = reader.nextString().also { position += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
                                                            when {
                                                                message.contains("PRIVMSG") -> messages.add(ChatUtils.parseChatMessage(message, false))
                                                                message.contains("USERNOTICE") -> messages.add(ChatUtils.parseChatMessage(message, true))
                                                                message.contains("CLEARMSG") -> {
                                                                    val pair = ChatUtils.parseClearMessage(message)
                                                                    val deletedMessage = pair.second?.let { targetId -> messages.find { it.id == targetId } }
                                                                    messages.add(getClearMessage(pair.first, deletedMessage, nameDisplay))
                                                                }
                                                                message.contains("CLEARCHAT") -> messages.add(ChatUtils.parseClearChat(applicationContext, message))
                                                            }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "comments" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            val message = StringBuilder()
                                                            var id: String? = null
                                                            var offsetSeconds: Int? = null
                                                            var userId: String? = null
                                                            var userLogin: String? = null
                                                            var userName: String? = null
                                                            var color: String? = null
                                                            val emotesList = mutableListOf<TwitchEmote>()
                                                            val badgesList = mutableListOf<Badge>()
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "id" -> id = reader.nextString().also { position += it.length + 2 }
                                                                    "commenter" -> {
                                                                        reader.beginObject().also { position += 1 }
                                                                        while (reader.hasNext()) {
                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                "id" -> userId = reader.nextString().also { position += it.length + 2 }
                                                                                "login" -> userLogin = reader.nextString().also { position += it.length + 2 }
                                                                                "displayName" -> userName = reader.nextString().also { position += it.length + 2 }
                                                                                else -> position += skipJsonValue(reader)
                                                                            }
                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                position += 1
                                                                            }
                                                                        }
                                                                        reader.endObject().also { position += 1 }
                                                                    }
                                                                    "contentOffsetSeconds" -> offsetSeconds = reader.nextInt().also { position += it.toString().length }
                                                                    "message" -> {
                                                                        reader.beginObject().also { position += 1 }
                                                                        while (reader.hasNext()) {
                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                "fragments" -> {
                                                                                    reader.beginArray().also { position += 1 }
                                                                                    while (reader.hasNext()) {
                                                                                        reader.beginObject().also { position += 1 }
                                                                                        var emoteId: String? = null
                                                                                        var fragmentText: String? = null
                                                                                        while (reader.hasNext()) {
                                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                                "emote" -> {
                                                                                                    when (reader.peek()) {
                                                                                                        JsonToken.BEGIN_OBJECT -> {
                                                                                                            reader.beginObject().also { position += 1 }
                                                                                                            while (reader.hasNext()) {
                                                                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                                                                    "emoteID" -> emoteId = reader.nextString().also { position += it.length + 2 }
                                                                                                                    else -> position += skipJsonValue(reader)
                                                                                                                }
                                                                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                                                    position += 1
                                                                                                                }
                                                                                                            }
                                                                                                            reader.endObject().also { position += 1 }
                                                                                                        }
                                                                                                        else -> position += skipJsonValue(reader)
                                                                                                    }
                                                                                                }
                                                                                                "text" -> fragmentText = reader.nextString().also { position += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
                                                                                                else -> position += skipJsonValue(reader)
                                                                                            }
                                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                                position += 1
                                                                                            }
                                                                                        }
                                                                                        if (fragmentText != null && !emoteId.isNullOrBlank()) {
                                                                                            emotesList.add(TwitchEmote(
                                                                                                id = emoteId,
                                                                                                begin = message.codePointCount(0, message.length),
                                                                                                end = message.codePointCount(0, message.length) + fragmentText.lastIndex
                                                                                            ))
                                                                                        }
                                                                                        message.append(fragmentText)
                                                                                        reader.endObject().also { position += 1 }
                                                                                        if (reader.peek() != JsonToken.END_ARRAY) {
                                                                                            position += 1
                                                                                        }
                                                                                    }
                                                                                    reader.endArray().also { position += 1 }
                                                                                }
                                                                                "userBadges" -> {
                                                                                    reader.beginArray().also { position += 1 }
                                                                                    while (reader.hasNext()) {
                                                                                        reader.beginObject().also { position += 1 }
                                                                                        var set: String? = null
                                                                                        var version: String? = null
                                                                                        while (reader.hasNext()) {
                                                                                            when (reader.nextName().also { position += it.length + 3 }) {
                                                                                                "setID" -> set = reader.nextString().also { position += it.length + 2 }
                                                                                                "version" -> version = reader.nextString().also { position += it.length + 2 }
                                                                                                else -> position += skipJsonValue(reader)
                                                                                            }
                                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                                position += 1
                                                                                            }
                                                                                        }
                                                                                        if (!set.isNullOrBlank() && !version.isNullOrBlank()) {
                                                                                            badgesList.add(Badge(set, version))
                                                                                        }
                                                                                        reader.endObject().also { position += 1 }
                                                                                        if (reader.peek() != JsonToken.END_ARRAY) {
                                                                                            position += 1
                                                                                        }
                                                                                    }
                                                                                    reader.endArray().also { position += 1 }
                                                                                }
                                                                                "userColor" -> {
                                                                                    when (reader.peek()) {
                                                                                        JsonToken.STRING -> color = reader.nextString().also { position += it.length + 2 }
                                                                                        else -> position += skipJsonValue(reader)
                                                                                    }
                                                                                }
                                                                                else -> position += skipJsonValue(reader)
                                                                            }
                                                                            if (reader.peek() != JsonToken.END_OBJECT) {
                                                                                position += 1
                                                                            }
                                                                        }
                                                                        messages.add(ChatMessage(
                                                                            id = id,
                                                                            userId = userId,
                                                                            userLogin = userLogin,
                                                                            userName = userName,
                                                                            message = message.toString(),
                                                                            color = color,
                                                                            emotes = emotesList,
                                                                            badges = badgesList,
                                                                            bits = 0,
                                                                            timestamp = offsetSeconds?.times(1000L),
                                                                            fullMsg = null
                                                                        ))
                                                                        reader.endObject().also { position += 1 }
                                                                    }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "twitchEmotes" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var id: String? = null
                                                            var data: Pair<Long, Int>? = null
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "id" -> id = reader.nextString().also { position += it.length + 2 }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!id.isNullOrBlank() && data != null) {
                                                                twitchEmotes.add(TwitchEmote(
                                                                    id = id,
                                                                    localData = data
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "twitchBadges" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var setId: String? = null
                                                            var version: String? = null
                                                            var data: Pair<Long, Int>? = null
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "setId" -> setId = reader.nextString().also { position += it.length + 2 }
                                                                    "version" -> version = reader.nextString().also { position += it.length + 2 }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!setId.isNullOrBlank() && !version.isNullOrBlank() && data != null) {
                                                                twitchBadges.add(TwitchBadge(
                                                                    setId = setId,
                                                                    version = version,
                                                                    localData = data
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "cheerEmotes" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var name: String? = null
                                                            var data: Pair<Long, Int>? = null
                                                            var minBits: Int? = null
                                                            var color: String? = null
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "name" -> name = reader.nextString().also { position += it.length + 2 }
                                                                    "minBits" -> minBits = reader.nextInt().also { position += it.toString().length }
                                                                    "color" -> {
                                                                        when (reader.peek()) {
                                                                            JsonToken.STRING -> color = reader.nextString().also { position += it.length + 2 }
                                                                            else -> position += skipJsonValue(reader)
                                                                        }
                                                                    }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!name.isNullOrBlank() && minBits != null && data != null) {
                                                                cheerEmotesList.add(CheerEmote(
                                                                    name = name,
                                                                    localData = data,
                                                                    minBits = minBits,
                                                                    color = color
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "emotes" -> {
                                                        reader.beginArray().also { position += 1 }
                                                        while (reader.hasNext()) {
                                                            reader.beginObject().also { position += 1 }
                                                            var data: Pair<Long, Int>? = null
                                                            var name: String? = null
                                                            var isZeroWidth = false
                                                            while (reader.hasNext()) {
                                                                when (reader.nextName().also { position += it.length + 3 }) {
                                                                    "data" -> {
                                                                        position += 1
                                                                        val length = reader.nextString().length
                                                                        data = Pair(position, length)
                                                                        position += length + 1
                                                                    }
                                                                    "name" -> name = reader.nextString().also { position += it.length + 2 }
                                                                    "isZeroWidth" -> isZeroWidth = reader.nextBoolean().also { position += it.toString().length }
                                                                    else -> position += skipJsonValue(reader)
                                                                }
                                                                if (reader.peek() != JsonToken.END_OBJECT) {
                                                                    position += 1
                                                                }
                                                            }
                                                            if (!name.isNullOrBlank() && data != null) {
                                                                emotes.add(Emote(
                                                                    name = name,
                                                                    localData = data,
                                                                    isZeroWidth = isZeroWidth
                                                                ))
                                                            }
                                                            reader.endObject().also { position += 1 }
                                                            if (reader.peek() != JsonToken.END_ARRAY) {
                                                                position += 1
                                                            }
                                                        }
                                                        reader.endArray().also { position += 1 }
                                                    }
                                                    "startTime" -> { startTimeMs = reader.nextInt().also { position += it.toString().length }.times(1000L) }
                                                    else -> position += skipJsonValue(reader)
                                                }
                                            }
                                            else -> position += skipJsonValue(reader)
                                        }
                                        if (reader.peek() != JsonToken.END_OBJECT) {
                                            position += 1
                                        }
                                    }
                                    reader.endObject().also { position += 1 }
                                }
                                else -> position += skipJsonValue(reader)
                            }
                        } while (token != JsonToken.END_DOCUMENT)
                    }
                }
                _localTwitchEmotes.value = twitchEmotes
                _channelBadges.value = twitchBadges
                _cheerEmotes.value = cheerEmotesList
                _channelStvEmotes.value = emotes
                if (emotes.isEmpty()) {
                    viewModelScope.launch {
                        loadEmotes(channelId, channelLogin)
                    }
                }
                if (messages.isNotEmpty()) {
                    viewModelScope.launch {
                        chatReplayManagerLocal?.start(messages, startTimeMs)
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    private fun skipJsonValue(reader: JsonReader): Int {
        var length = 0
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray().also { length += 1 }
                while (reader.hasNext()) {
                    when (reader.peek()) {
                        JsonToken.NAME -> length += reader.nextName().length + 3
                        else -> {
                            length += skipJsonValue(reader)
                            if (reader.peek() != JsonToken.END_ARRAY) {
                                length += 1
                            }
                        }
                    }
                }
                reader.endArray().also { length += 1 }
            }
            JsonToken.END_ARRAY -> length += 1
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject().also { length += 1 }
                while (reader.hasNext()) {
                    when (reader.peek()) {
                        JsonToken.NAME -> length += reader.nextName().length + 3
                        else -> {
                            length += skipJsonValue(reader)
                            if (reader.peek() != JsonToken.END_OBJECT) {
                                length += 1
                            }
                        }
                    }
                }
                reader.endObject().also { length += 1 }
            }
            JsonToken.END_OBJECT -> length += 1
            JsonToken.STRING -> reader.nextString().let { length += it.length + 2 + it.count { c -> c == '"' || c == '\\' } }
            JsonToken.NUMBER -> length += reader.nextString().length
            JsonToken.BOOLEAN -> length += reader.nextBoolean().toString().length
            else -> reader.skipValue()
        }
        return length
    }

    companion object {
        private const val TAG = "ChatViewModel"

        private var savedEmoteSets: List<String>? = null
        private var savedUserEmotes: List<TwitchEmote>? = null
        private var savedGlobalBadges: List<TwitchBadge>? = null
        private var savedGlobalStvEmotes: List<Emote>? = null
        private var savedGlobalBttvEmotes: List<Emote>? = null
        private var savedGlobalFfzEmotes: List<Emote>? = null
    }
}