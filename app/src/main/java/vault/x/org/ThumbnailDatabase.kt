package vault.x.org

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

// Simple Database to track: "Does file X have a thumbnail?"
class ThumbnailDatabase(context: Context) : SQLiteOpenHelper(context, "Thumbnails.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // Table: ID (UUID) -> Path (Where the jpg is)
        db.execSQL("CREATE TABLE thumbs (id TEXT PRIMARY KEY, path TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS thumbs")
        onCreate(db)
    }

    // Add a record
    fun addThumbnail(id: String, path: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("path", path)
        }
        writableDatabase.insertWithOnConflict("thumbs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // Get a specific thumbnail path
    fun getThumbnailPath(id: String): String? {
        val cursor = readableDatabase.query("thumbs", arrayOf("path"), "id = ?", arrayOf(id), null, null, null)
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    // Get ALL known thumbnails (Fast Map for UI)
    fun getAllThumbnails(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val cursor = readableDatabase.rawQuery("SELECT id, path FROM thumbs", null)
        cursor.use {
            while (it.moveToNext()) {
                map[it.getString(0)] = it.getString(1)
            }
        }
        return map
    }
}