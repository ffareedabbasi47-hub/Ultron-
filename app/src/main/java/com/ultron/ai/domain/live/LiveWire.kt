package com.ultron.ai.domain.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * Message mapping for the Gemini Live API WebSocket (streaming speech-to-text only; ULTRON speaks with the phone's
 * own TTS). Pure, so it is unit-tested. Field names follow Google's Live API docs for `gemini-3.5-transcribe-live`
 * (manual activity detection: activityStart / audio chunks / activityEnd).
 */
object LiveWire {
    const val MODEL = "models/gemini-3.5-transcribe-live"
    const val HOST_PATH = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun setup(model: String = MODEL): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", model)
            putJsonObject("generationConfig") { putJsonArray("responseModalities") { add("TEXT") } }
            putJsonObject("inputAudioTranscription") { }
            putJsonObject("realtimeInputConfig") { putJsonObject("automaticActivityDetection") { put("disabled", true) } }
        }
    }.toString()

    fun activityStart(): String = """{"realtimeInput":{"activityStart":{}}}"""
    fun activityEnd(): String = """{"realtimeInput":{"activityEnd":{}}}"""

    fun audio(base64Pcm16k: String): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") { put("data", base64Pcm16k); put("mimeType", "audio/pcm;rate=16000") }
        }
    }.toString()

    data class Server(
        val setupComplete: Boolean = false,
        val finalText: String? = null,
        val interimText: String? = null,
        val turnComplete: Boolean = false,
        val error: String? = null,
    )

    fun parse(text: String): Server? {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        if (root["setupComplete"] != null) return Server(setupComplete = true)
        root["error"]?.let { return Server(error = (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "error") }
        val sc = root["serverContent"] as? JsonObject ?: return Server()
        fun textOf(k: String) = ((sc[k] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        return Server(
            finalText = textOf("inputTranscription"),
            interimText = textOf("interimInputTranscription"),
            turnComplete = (sc["turnComplete"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    /** True if the text contains Devanagari: the local command parser expects Roman Hinglish. */
    fun hasDevanagari(s: String) = s.any { it.code in 0x0900..0x097F }

    // ---------------------------------------------------------------------------------------------
    // Full conversation ("Live Call"): audio in, audio out, barge-in handled by the server.
    // ---------------------------------------------------------------------------------------------
    const val CALL_MODEL = "models/gemini-3.8-live"
    const val OUT_RATE = 24_000

    /** Tools ULTRON may call during a conversation. Each maps to a validated local action (see LiveCall). */
    private fun tool(name: String, desc: String, vararg props: Pair<String, String>) = buildJsonObject {
        put("name", name); put("description", desc)
        if (props.isNotEmpty()) putJsonObject("parameters") {
            put("type", "OBJECT")
            putJsonObject("properties") { for ((k, d) in props) putJsonObject(k) { put("type", "STRING"); put("description", d) } }
            putJsonArray("required") { for ((k, d) in props) if (!d.contains("optional", ignoreCase = true)) add(k) } // optional params must not be forced on the model
        }
    }

    fun setupCall(systemPrompt: String, voice: String, model: String = CALL_MODEL): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", model)
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") { putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } } }
            }
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt) }) } }
            putJsonObject("realtimeInputConfig") { put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS") }
            putJsonObject("inputAudioTranscription") { }
            putJsonObject("outputAudioTranscription") { }
            putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        add(tool("open_app", "Open an installed Android app by its name (e.g. YouTube, WhatsApp, Chrome, Camera).", "name" to "App name"))
                        add(tool("youtube_search", "Search YouTube and PLAY the first matching video (song, trailer, any video).", "query" to "Search text"))
                        add(tool("phone_toggle", "Turn a phone switch on or off: wifi, hotspot, bluetooth, airplane, mobile_data, location, nfc or torch.", "name" to "wifi|hotspot|bluetooth|airplane|mobile_data|location|nfc|torch", "state" to "on or off"))
                        add(tool("set_reminder", "Set a reminder after some minutes.", "text" to "What to remind", "minutes" to "Delay in minutes as a number"))
                        add(tool("build_project", "Hand a coding job (website, web page, small app) to the background builder agent. Use when the user asks you to build or code something. Pass a clear one-sentence brief.", "brief" to "What to build, with key requirements"))
                        add(tool("call_contact", "Place a phone call to a saved contact by name, in ANY language or relation (father, papa, abbu, mom...). FIRST tell the user who you will call and wait for a clear yes, THEN call this tool. The phone resolves the real contact and may report it is ambiguous or missing: tell the user that result honestly. Opening the dialer is not a connected call.", "name" to "Contact name or relation as the user said it"))
                        add(tool("call_number", "Call a typed phone number (never emergency numbers). Confirm the digits aloud with the user first.", "number" to "Digits, optional leading +"))
                        add(tool("send_message", "Prepare a message to a contact. channel 'whatsapp' opens the WhatsApp chat with the text prefilled (the user taps Send; delivery is NOT known). channel 'sms' sends a text. FIRST read the exact message back and get a clear yes, THEN call. Never claim it was delivered.", "name" to "Contact name or relation", "body" to "Exact message text", "channel" to "whatsapp or sms"))
                        add(tool("set_alarm", "Set an alarm. 24-hour hour/minute. Use in_minutes instead for 'after N minutes'.", "hour" to "optional if in_minutes is used: 0-23", "minute" to "optional if in_minutes is used: 0-59", "in_minutes" to "optional: minutes from now", "tomorrow" to "optional: true"))
                        add(tool("set_timer", "Start a countdown timer.", "seconds" to "Duration in seconds"))
                        add(tool("device_control", "Change volume, brightness, do-not-disturb or media playback. kind: volume_up, volume_down, brightness (value 1-100), dnd (value on/off), media (value play/pause/next/previous/close_youtube).", "kind" to "volume_up|volume_down|brightness|dnd|media", "value" to "optional for volume; brightness 1-100, dnd on/off, media play/pause/next/previous/close_youtube"))
                        add(tool("remember", "Save a fact the user asked you to remember.", "text" to "What to remember"))
                        add(tool("web_search", "Research / search the live web for current information, news or documentation (works for requests in any language, e.g. 'is topic par research karo'). Call it, with several different queries if you need to compare sources. Answer ONLY from what it returns, say it came from the web, and if it says the search failed, tell the user that honestly instead of guessing.", "query" to "What to search for"))
                        add(tool("my_capabilities", "Get the REAL list of what ULTRON can and cannot do right now on this phone (permissions, config). Call this for any question about your abilities and never claim abilities it does not list.", "topic" to "optional: whatsapp, calling, sms, web_search, wake or empty"))
                        add(tool("stop_listening", "End the voice call when the user says goodbye or asks you to stop."))
                    }
                })
            }
        }
    }.toString()

    fun toolResponse(id: String, name: String, result: String): String = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                add(buildJsonObject { put("id", id); put("name", name); putJsonObject("response") { put("result", result) } })
            }
        }
    }.toString()

    class ToolCall(val id: String, val name: String, val args: Map<String, String>)
    /** A typed message into the SAME live session (Live API realtimeInput text). */
    fun text(t: String): String = buildJsonObject { putJsonObject("realtimeInput") { put("text", t) } }.toString()

    /** Earlier conversation restored into a fresh session (role: "user" | "model"). Not a turn: the model does not answer it. */
    fun history(turns: List<Pair<String, String>>): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                turns.forEach { (role, t) ->
                    add(buildJsonObject { put("role", role); putJsonArray("parts") { add(buildJsonObject { put("text", t) }) } })
                }
            }
            put("turnComplete", false)
        }
    }.toString()

    class CallMsg(
        val setupComplete: Boolean = false,
        val audio: List<String> = emptyList(),   // base64 PCM16 @ 24 kHz chunks
        val interrupted: Boolean = false,
        val turnComplete: Boolean = false,
        val heard: String? = null,                // what the user said (transcription)
        val said: String? = null,                 // what ULTRON said (transcription)
        val tools: List<ToolCall> = emptyList(),
        val goAway: Boolean = false,
        val error: String? = null,
    )

    fun parseCall(text: String): CallMsg? {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        if (root["setupComplete"] != null) return CallMsg(setupComplete = true)
        root["error"]?.let { return CallMsg(error = (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "error") }
        if (root["goAway"] != null) return CallMsg(goAway = true)
        (root["toolCall"] as? JsonObject)?.let { tc ->
            val calls = (tc["functionCalls"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val args = (o["args"] as? JsonObject)?.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
                ToolCall(id, name, args)
            }
            return CallMsg(tools = calls)
        }
        val sc = root["serverContent"] as? JsonObject ?: return CallMsg()
        val audio = ArrayList<String>()
        ((sc["modelTurn"] as? JsonObject)?.get("parts") as? kotlinx.serialization.json.JsonArray)?.forEach { p ->
            val inl = (p as? JsonObject)?.get("inlineData") as? JsonObject
            val mime = inl?.get("mimeType")?.jsonPrimitive?.contentOrNull.orEmpty()
            val data = inl?.get("data")?.jsonPrimitive?.contentOrNull
            if (data != null && (mime.isEmpty() || mime.startsWith("audio"))) audio += data
        }
        fun tx(k: String) = ((sc[k] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        return CallMsg(
            audio = audio,
            interrupted = (sc["interrupted"] as? JsonPrimitive)?.contentOrNull == "true",
            turnComplete = (sc["turnComplete"] as? JsonPrimitive)?.contentOrNull == "true",
            heard = tx("inputTranscription"),
            said = tx("outputTranscription"),
        )
    }
}
