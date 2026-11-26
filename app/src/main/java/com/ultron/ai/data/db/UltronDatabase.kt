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
        WorkDocEntity::class,
        MarketEntity::class,
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
    abstract fun workDocs(): WorkDocDao
    abstract fun markets(): MarketDao

    companion object {
        private const val NAME = "ultron.db"

        /**
         * Opens the SQLCipher-encrypted database. If the Keystore key was lost (e.g. the device lock/biometrics were
         * reset) the old file cannot be read. It is NOT deleted: it is renamed to `ultron.db.unreadable-<time>` (with its
         * -wal/-shm/-journal files) so nothing is lost silently, a fresh database is created, and the caller is told so it
         * can inform the user. Only the explicit "reset everything" action removes those files.
         */
        fun open(context: Context, secure: SecureStore, onKeyReset: () -> Unit): UltronDatabase {
            System.loadLibrary("sqlcipher")
            val meta = context.getSharedPreferences("ultron_db_meta", Context.MODE_PRIVATE)
            var name = meta.getString("db_name", NAME) ?: NAME
            val key = secure.databaseKey(context.getDatabasePath(name).exists())
            if (key is SecureStore.DbKey.Replaced) {
                name = quarantine(context, meta, name)
                onKeyReset()
            }
            return Room.databaseBuilder(context.applicationContext, UltronDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(key.passphrase))
                .build()
        }

        /** Moves the unreadable database aside. Returns the file name to use for the new database. */
        private fun quarantine(context: Context, meta: android.content.SharedPreferences, name: String): String {
            val ts = System.currentTimeMillis()
            val main = context.getDatabasePath(name)
            for (suffix in com.ultron.ai.domain.DbKeyPolicy.sidecarSuffixes) {
                val f = java.io.File(main.path + suffix)
                if (f.exists()) f.renameTo(java.io.File(main.parentFile, com.ultron.ai.domain.DbKeyPolicy.quarantineName(name, suffix, ts)))
            }
            if (!main.exists()) return name
            // Rename failed: keep the old file untouched and use a different file name for the new database.
            val alt = "ultron-$ts.db"
            meta.edit().putString("db_name", alt).apply()
            return alt
        }

        /** Names of preserved unreadable database files (for the reset screen). */
        fun unreadableFiles(context: Context): List<java.io.File> =
            context.getDatabasePath(NAME).parentFile?.listFiles { f -> ".unreadable-" in f.name }?.toList().orEmpty()
    }
}

/** Escapes LIKE wildcards so user search text is matched literally (queries use ESCAPE '\'). */
fun escapeLike(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
