package com.ultron.ai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ultron.ai.core.security.SecureStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        JournalEntity::class,
        MoodEntity::class,
        MemoryEntity::class,
        ReminderEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UltronDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun journal(): JournalDao
    abstract fun mood(): MoodDao
    abstract fun memory(): MemoryDao
    abstract fun reminders(): ReminderDao

    companion object {
        private const val NAME = "ultron.db"

        /**
         * Opens the SQLCipher-encrypted database. If the Keystore key was lost (e.g. the device
         * lock/biometrics were reset) the old file is unreadable by design; it is deleted and
         * a fresh database is created, and the caller is told so it can inform the user.
         */
        fun open(context: Context, secure: SecureStore, onKeyReset: () -> Unit): UltronDatabase {
            System.loadLibrary("sqlcipher")
            val (passphrase, fresh) = secure.databasePassphrase()
            if (fresh && context.getDatabasePath(NAME).exists()) {
                context.deleteDatabase(NAME)
                onKeyReset()
            }
            return Room.databaseBuilder(context.applicationContext, UltronDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}

/** Escapes LIKE wildcards so user search text is matched literally (queries use ESCAPE '\'). */
fun escapeLike(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
