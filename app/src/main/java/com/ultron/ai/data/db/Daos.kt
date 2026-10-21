package com.ultron.ai.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert suspend fun insert(c: ConversationEntity): Long
    @Update suspend fun update(c: ConversationEntity)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun get(id: Long): ConversationEntity?
    @Query("SELECT * FROM conversations WHERE mode = :mode ORDER BY updatedAt DESC LIMIT 1") suspend fun latest(mode: String): ConversationEntity?
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM conversations") suspend fun deleteAll()

    @Insert suspend fun insertMessage(m: MessageEntity): Long
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC, id ASC")
    fun observeMessages(id: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC, id ASC")
    suspend fun messages(id: Long): List<MessageEntity>
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessage(id: Long)
    @Query("SELECT COUNT(*) FROM messages") fun observeMessageCount(): Flow<Int>
}

@Dao
interface JournalDao {
    @Insert suspend fun insert(e: JournalEntity): Long
    @Update suspend fun update(e: JournalEntity)
    @Delete suspend fun delete(e: JournalEntity)
    @Query("SELECT * FROM journal WHERE id = :id") suspend fun get(id: Long): JournalEntity?
    @Query("SELECT * FROM journal ORDER BY updatedAt DESC") fun observeAll(): Flow<List<JournalEntity>>
    @Query("SELECT * FROM journal WHERE title LIKE '%' || :q || '%' ESCAPE '\\' OR body LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<JournalEntity>>
    @Query("SELECT * FROM journal ORDER BY createdAt ASC") suspend fun all(): List<JournalEntity>
    @Query("DELETE FROM journal") suspend fun deleteAll()
}

@Dao
interface MoodDao {
    @Insert suspend fun insert(e: MoodEntity): Long
    @Delete suspend fun delete(e: MoodEntity)
    @Query("SELECT * FROM mood ORDER BY createdAt DESC") fun observeAll(): Flow<List<MoodEntity>>
    @Query("SELECT * FROM mood ORDER BY createdAt DESC LIMIT 1") fun observeLatest(): Flow<MoodEntity?>
    @Query("SELECT * FROM mood ORDER BY createdAt ASC") suspend fun all(): List<MoodEntity>
    @Query("DELETE FROM mood") suspend fun deleteAll()
}

@Dao
interface MemoryDao {
    @Insert suspend fun insert(e: MemoryEntity): Long
    @Update suspend fun update(e: MemoryEntity)
    @Delete suspend fun delete(e: MemoryEntity)
    @Query("SELECT * FROM memory ORDER BY updatedAt DESC") fun observeAll(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memory WHERE text LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memory ORDER BY createdAt ASC") suspend fun all(): List<MemoryEntity>
    @Query("DELETE FROM memory") suspend fun deleteAll()
}

@Dao
interface ReminderDao {
    @Insert suspend fun insert(e: ReminderEntity): Long
    @Update suspend fun update(e: ReminderEntity)
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun get(id: Long): ReminderEntity?
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC") fun observeAll(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY triggerAt ASC") suspend fun pending(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY triggerAt ASC LIMIT 1") fun observeNext(): Flow<ReminderEntity?>
    @Query("DELETE FROM reminders") suspend fun deleteAll()
}
