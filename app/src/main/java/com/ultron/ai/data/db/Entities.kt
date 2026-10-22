package com.ultron.ai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val mode: String, // "chat" | "study"
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // "user" | "assistant"
    val text: String,
    val createdAt: Long,
    val status: String = STATUS_OK, // ok | cancelled
    val source: String = SOURCE_AI, // ai | local
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_CANCELLED = "cancelled"
        const val SOURCE_AI = "ai"
        const val SOURCE_LOCAL = "local"
    }
}

@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "mood")
data class MoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: Int, // 1 (low) .. 5 (high), self-reported
    val note: String,
    val createdAt: Long,
)

@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String, // "manual" | "command" | "scan"
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    val createdAt: Long,
    val fired: Boolean = false,
)

/** Local workspace item: kind = "doc" | "code" | "post". Everything is user-written or user-approved; nothing is fetched. */
@Entity(tableName = "workdocs", indices = [Index("kind")])
data class WorkDocEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Optional planned date (epoch millis) for marketing posts; 0 = none. */
    val plannedAt: Long = 0,
)

/** Manual watchlist/position entry. Prices are typed by the user; no live market feed exists in this build. */
@Entity(tableName = "markets")
data class MarketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val name: String,
    val quantity: Double,
    val buyPrice: Double,
    val lastPrice: Double,
    val note: String,
    val updatedAt: Long,
)
