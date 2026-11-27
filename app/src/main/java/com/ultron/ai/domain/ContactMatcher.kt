package com.ultron.ai.domain

data class ContactEntry(val id: String, val name: String, val number: String, val isMobile: Boolean = false)

sealed interface ContactMatch {
    data object None : ContactMatch
    data class One(val name: String, val number: String) : ContactMatch
    /** Several different people match: NEVER pick one, ask the user for the full name. */
    data class Ambiguous(val names: List<String>) : ContactMatch
    /** One person, several different numbers and no single mobile: ask which. */
    data class MultiNumber(val name: String, val numbers: List<String>) : ContactMatch
}

/** Pure contact lookup + number normalisation (unit-tested). Never guesses between people. */
object ContactMatcher {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Keeps a leading + and digits; null when it cannot be a phone number (3..15 digits). */
    fun normalizeNumber(raw: String): String? {
        val t = raw.trim(); val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        if (digits.length !in 3..15) return null
        return (if (plus) "+" else "") + digits
    }

    private fun same(a: String, b: String) = a.takeLast(10) == b.takeLast(10)

    private val possessive = Regex("^(?:my|mere|meri|mera|apne|apni|apna|our)\\s+")
    private val relationGroups: List<Set<String>> = listOf(
        setOf("father", "papa", "pappa", "dad", "daddy", "abbu", "abu", "abba", "baba", "pitaji", "papaji", "pita"),
        setOf("mother", "mom", "mommy", "mummy", "maa", "ma", "ammi", "amma", "mata", "mataji", "mummyji"),
        setOf("brother", "bhai", "bhaiya", "bro"),
        setOf("sister", "behen", "behan", "didi", "sis"),
        setOf("wife", "biwi", "patni"), setOf("husband", "pati"),
        setOf("grandfather", "dada", "dadu", "nana", "nanu"), setOf("grandmother", "dadi", "nani"),
    )

    /** "my father" -> also tries papa / dad / abbu ...: contacts are saved under many names. Literal name is always tried first. */
    fun find(query: String, entries: List<ContactEntry>): ContactMatch {
        val q0 = norm(query).replace(possessive, "").trim()
        val candidates = buildList { add(q0); relationGroups.firstOrNull { q0 in it }?.forEach { if (it != q0) add(it) } }
        for (c in candidates) { val r = findOne(c, entries); if (r !is ContactMatch.None) return r }
        return ContactMatch.None
    }

    private fun findOne(query: String, entries: List<ContactEntry>): ContactMatch {
        val q = norm(query)
        if (q.isEmpty()) return ContactMatch.None
        val qTokens = q.split(' ')
        fun score(name: String): Int {
            val n = norm(name)
            return when {
                n == q -> 3
                n.startsWith("$q ") || n.split(' ').firstOrNull() == q -> 2
                qTokens.all { t -> n.split(' ').any { it.startsWith(t) } } -> 1
                else -> 0
            }
        }
        val scored = entries.map { it to score(it.name) }.filter { it.second > 0 }
        if (scored.isEmpty()) return ContactMatch.None
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        val people = top.groupBy { it.id }
        if (people.size > 1) return ContactMatch.Ambiguous(people.values.map { it.first().name }.distinct().take(4))
        val list = people.values.first()
        val distinct = ArrayList<ContactEntry>()
        for (e in list) { val n = normalizeNumber(e.number) ?: continue; if (distinct.none { same(normalizeNumber(it.number)!!, n) }) distinct.add(e) }
        if (distinct.isEmpty()) return ContactMatch.None
        if (distinct.size == 1) return ContactMatch.One(distinct[0].name, normalizeNumber(distinct[0].number)!!)
        val mobiles = distinct.filter { it.isMobile }
        if (mobiles.size == 1) return ContactMatch.One(mobiles[0].name, normalizeNumber(mobiles[0].number)!!)
        return ContactMatch.MultiNumber(distinct[0].name, distinct.map { normalizeNumber(it.number)!! })
    }
}
