package com.ultron.ai.domain

/**
 * Decides what to do with the encrypted database when its passphrase is (not) available. Pure logic so the
 * rule "never silently delete the user's only copy of their data" is unit-tested.
 */
object DbKeyPolicy {
    enum class Action {
        /** The stored passphrase decrypted: open the existing database. */
        USE_EXISTING,
        /** Nothing stored and no database file: first run. */
        CREATE_NEW,
        /** The passphrase cannot be recovered but a database file exists: keep the old file aside (never delete it) and start a new one. */
        QUARANTINE_AND_CREATE,
    }

    fun decide(blobPresent: Boolean, decrypted: Boolean, dbFileExists: Boolean): Action = when {
        blobPresent && decrypted -> Action.USE_EXISTING
        !blobPresent && !dbFileExists -> Action.CREATE_NEW
        else -> Action.QUARANTINE_AND_CREATE
    }

    /** `ultron.db` + `-wal` -> `ultron.db.unreadable-<ts>-wal` style names that can never collide with the live database. */
    fun quarantineName(base: String, suffix: String, timestampMs: Long): String = "$base.unreadable-$timestampMs$suffix"

    val sidecarSuffixes = listOf("", "-wal", "-shm", "-journal")
}
