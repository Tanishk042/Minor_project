package org.forestear.node.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class QueuedEvent(val id: Long, val tries: Int, val payload: String)

/**
 * Durable offline queue for alert events (never for live telemetry).
 * Writes are serialised because the service and the flush thread both touch it.
 */
class OfflineQueue(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "forestear_offline.db", null, 1
) {
    private val lock = Any()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "created_ms INTEGER NOT NULL," +
                "tries INTEGER NOT NULL DEFAULT 0," +
                "payload TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enqueue(payload: String, cap: Int = 5000): Long = synchronized(lock) {
        val db = writableDatabase
        val id = db.insert("events", null, ContentValues().apply {
            put("created_ms", System.currentTimeMillis())
            put("tries", 0)
            put("payload", payload)
        })
        val count = countLocked()
        if (count > cap) {
            db.execSQL(
                "DELETE FROM events WHERE id IN " +
                    "(SELECT id FROM events ORDER BY id LIMIT ?)",
                arrayOf(count - cap)
            )
        }
        id
    }

    fun pending(limit: Int = 50): List<QueuedEvent> = synchronized(lock) {
        val out = mutableListOf<QueuedEvent>()
        readableDatabase.rawQuery(
            "SELECT id, tries, payload FROM events ORDER BY id LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += QueuedEvent(c.getLong(0), c.getInt(1), c.getString(2))
            }
        }
        out
    }

    fun delete(id: Long) = synchronized(lock) {
        writableDatabase.delete("events", "id=?", arrayOf(id.toString()))
    }

    fun incrementTries(id: Long) = synchronized(lock) {
        writableDatabase.execSQL("UPDATE events SET tries=tries+1 WHERE id=?", arrayOf(id))
    }

    fun count(): Int = synchronized(lock) { countLocked() }

    fun clear() = synchronized(lock) {
        writableDatabase.delete("events", null, null)
    }

    private fun countLocked(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
