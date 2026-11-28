package com.ultron.ai.domain

/**
 * Decides, from the VISIBLE WhatsApp screen, whether it is safe to press Send:
 * the right app, the right chat (contact name or number on screen) and the EXACT message in the input box.
 * Anything else -> NotReady and nothing is tapped. (Press Send, delivered and read are different things; this only gates the tap.)
 */
object WhatsAppGuard {
    sealed interface Result {
        data class Ready(val sendNodeId: Int) : Result
        data class NotReady(val reason: String) : Result
    }

    private val sendLabels = setOf("send", "भेजें", "भेजे", "bhejo")
    private val packages = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun check(snap: ScreenSnapshot?, contactName: String, number: String, body: String): Result {
        if (snap == null) return Result.NotReady("screen padh nahi paya")
        if (snap.packageName !in packages) return Result.NotReady("WhatsApp screen par nahi hai (${snap.packageName})")
        val want = norm(body)
        val edit = snap.nodes.filter { it.editable }
        if (edit.none { norm(it.label) == want })
            return Result.NotReady(if (edit.isEmpty()) "message box nahi dikha" else "message box me poora exact text nahi dikha")
        val name = norm(contactName).lowercase()
        val last10 = number.filter { it.isDigit() }.takeLast(10)
        val chatOk = snap.nodes.any { n ->
            !n.editable && (n.label.lowercase().contains(name) && name.isNotEmpty() || (last10.length >= 7 && n.label.filter { it.isDigit() }.endsWith(last10)))
        }
        if (!chatOk) return Result.NotReady("chat me ${contactName} ka naam/number nahi dikha, galat chat ho sakta hai")
        val send = snap.nodes.firstOrNull { it.clickable && !it.editable && norm(it.label).lowercase() in sendLabels }
            ?: return Result.NotReady("Send button nahi dikha")
        return Result.Ready(send.id)
    }

    /** True when the input box no longer holds the message (WhatsApp took it). Says nothing about delivery. */
    fun messageLeftBox(snap: ScreenSnapshot?, body: String): Boolean =
        snap != null && snap.packageName in packages && snap.nodes.none { it.editable && norm(it.label) == norm(body) }
}
