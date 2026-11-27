package com.ultron.ai.domain

/** Facts about THIS phone/app right now. Built from real checks (permissions, installed apps, settings), never assumed. */
data class CapabilityEnv(
    val micPermission: Boolean, val cloudConsent: Boolean, val geminiKey: Boolean, val liveCallEnabled: Boolean,
    val wakeReady: Boolean, val contactsPermission: Boolean, val callPermission: Boolean, val smsPermission: Boolean,
    val whatsappInstalled: Boolean, val accessibilityOn: Boolean, val telephony: Boolean,
)

/** One capability: what it is, what it needs, who runs it, how it can be asked, where it stops. */
data class Capability(
    val id: String, val name: String, val description: String,
    val permissions: List<String>, val services: List<String>, val configuration: List<String>,
    val handler: String, val inputs: List<String>, val limitations: String,
    /** Returns the list of unmet requirements (empty = available). */
    val missing: (CapabilityEnv) -> List<String>,
)

data class CapabilityStatus(val cap: Capability, val missing: List<String>) { val available get() = missing.isEmpty() }

object CapabilityRegistry {
    private val cloud: (CapabilityEnv) -> List<String> = { e ->
        buildList { if (!e.cloudConsent) add("Cloud AI allow (Settings)"); if (!e.geminiKey) add("Gemini API key") }
    }
    val all: List<Capability> = listOf(
        Capability("live_voice", "Voice conversation (Gemini Live)", "Boliye, ULTRON Gemini Live se awaaz me jawab deta hai.",
            listOf("Microphone"), listOf("Internet", "Gemini Live"), listOf("Cloud AI consent", "Gemini key", "Live call on"), "LiveCall / LiveSessionEngine",
            listOf("voice"), "Live ke dauran mic audio Google ko jaati hai. Free key par Live ki limit chhoti hoti hai.",
        ) { e -> buildList { if (!e.micPermission) add("Microphone permission"); addAll(cloud(e)); if (!e.liveCallEnabled) add("Live call setting on") } },
        Capability("text_chat", "Text conversation (Gemini Live)", "Chat me type kiya message usi Live session me jaata hai jisme voice jaati hai.",
            emptyList(), listOf("Internet", "Gemini Live"), listOf("Cloud AI consent", "Gemini key", "Live call on"), "LiveChat",
            listOf("text"), "Live band ho to message bheja nahi jaata; koi chupke se doosra AI use nahi hota.",
        ) { e -> buildList { addAll(cloud(e)); if (!e.liveCallEnabled) add("Live call setting on") } },
        Capability("wake", "Offline wake word", "Phone par hi 'Hey ULTRON' sunna, internet ya key ke bina.",
            listOf("Microphone"), listOf("Foreground service"), listOf("Wake recordings ya neural model"), "PersonalWake / OnnxWake",
            listOf("voice"), "Speaker-dependent; false trigger ya miss ho sakta hai. Android/ColorOS background rok sakta hai.",
        ) { e -> buildList { if (!e.micPermission) add("Microphone permission"); if (!e.wakeReady) add("Wake recordings (ya neural model) taiyar nahi") } },
        Capability("web_search", "Web search", "Internet par current jawab dhundna, source links ke saath.",
            emptyList(), listOf("Internet", "Gemini Google Search grounding"), listOf("Cloud AI consent", "Gemini key"), "WebSearch",
            listOf("voice", "text"), "Jawab Google Search grounding se aata hai; sources na mile to ULTRON saaf bolta hai ki web result nahi mila.",
        ) { e -> cloud(e) },
        Capability("calling", "Phone calling", "Contact dhundh kar confirm karke call lagana.",
            listOf("Contacts", "Call phone"), listOf("Telephony"), emptyList(), "DeviceActionRunner.callContact",
            listOf("voice", "text"), "Dialer khulna ya call lagna 'connect hua' nahi hota. Ambiguous naam par ULTRON poochta hai, andaza nahi lagata.",
        ) { e -> buildList { if (!e.telephony) add("Telephony hardware"); if (!e.contactsPermission) add("Contacts permission"); if (!e.callPermission) add("Call permission (nahi to dialer khulega)") }.filter { !it.startsWith("Call permission") } },
        Capability("whatsapp", "WhatsApp message", "Contact resolve karke message taiyar karna; bhejna aapki confirmation par.",
            listOf("Contacts"), listOf("WhatsApp installed"), emptyList(), "DeviceActionRunner.whatsappSend",
            listOf("voice", "text"), "WhatsApp koi delivery result nahi deta. Accessibility off ho to Send aapko dabana hoga.",
        ) { e -> buildList { if (!e.whatsappInstalled) add("WhatsApp installed nahi"); if (!e.contactsPermission) add("Contacts permission") } },
        Capability("sms", "SMS", "Contact ko SMS taiyar karna ya bhejna (alag se, WhatsApp nahi).",
            listOf("Contacts", "Send SMS"), listOf("Telephony"), emptyList(), "DeviceActionRunner.smsSend",
            listOf("voice", "text"), "'Radio ko diya' ka matlab delivered nahi.",
        ) { e -> buildList { if (!e.telephony) add("Telephony hardware"); if (!e.contactsPermission) add("Contacts permission") } },
        Capability("settings", "Phone settings (Wi-Fi, hotspot, torch, volume...)", "Settings panel khol kar switch badalna aur result padhna.",
            emptyList(), listOf("Accessibility service (kuch switches)"), listOf("Accessibility on"), "DeviceActionRunner.setToggle",
            listOf("voice", "text"), "Android 10+ me Wi-Fi/hotspot seedha toggle nahi ho sakta; Accessibility se hota hai.",
        ) { e -> if (e.accessibilityOn) emptyList() else listOf("Accessibility service (Wi-Fi/hotspot/Bluetooth ke liye)") },
        Capability("alarms", "Alarm, timer, reminder", "Alarm/timer lagana aur check karna.", emptyList(), emptyList(), emptyList(), "DeviceActionRunner.setAlarm",
            listOf("voice", "text"), "Alarm intent me sirf time hota hai, date nahi.") { _ -> emptyList() },
        Capability("memory", "Memory", "Aapki batayi baatein yaad rakhna.", emptyList(), emptyList(), emptyList(), "DeviceActionRunner.remember",
            listOf("voice", "text"), "Abhi sirf voice/text se save; manual fields (tags, dates) nahi.") { _ -> emptyList() },
        Capability("apps_media", "Apps kholna, YouTube, media control", "App kholna, YouTube video chalana, play/pause.", emptyList(), emptyList(), emptyList(), "DeviceActionRunner",
            listOf("voice", "text"), "Playback sirf isMusicActive se jaanchi jaati hai; ULTRON dusre app ko kill nahi kar sakta.") { _ -> emptyList() },
    )

    fun evaluate(env: CapabilityEnv): List<CapabilityStatus> = all.map { CapabilityStatus(it, it.missing(env)) }

    /** Plain-language answer built ONLY from the evaluated registry. [focus] limits it to one capability id. */
    fun describe(statuses: List<CapabilityStatus>, focus: String? = null): String {
        val list = if (focus == null) statuses else statuses.filter { it.cap.id == focus }
        if (list.isEmpty()) return "Is cheez ki koi capability registry me nahi hai."
        return list.joinToString("\n") { s ->
            if (s.available) "- ${s.cap.name}: AVAILABLE. ${s.cap.description} Limit: ${s.cap.limitations}"
            else "- ${s.cap.name}: abhi NAHI chalega, kyunki ${s.missing.joinToString(", ")} chahiye."
        }
    }

    /** Maps a user question to one capability id (null = general "what can you do"). */
    fun focusFor(q: String): String? {
        val t = q.lowercase()
        return when {
            "whatsapp" in t -> "whatsapp"
            "call" in t || "phone kar" in t -> "calling"
            "sms" in t -> "sms"
            "search" in t || "internet" in t || "web" in t -> "web_search"
            "offline" in t || "wake" in t -> "wake"
            else -> null
        }
    }
}
