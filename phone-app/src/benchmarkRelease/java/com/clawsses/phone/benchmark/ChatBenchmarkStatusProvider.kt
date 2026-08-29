package com.clawsses.phone.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local completion signal exposed only by the isolated benchmark application. */
class ChatBenchmarkStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(COLUMN_COMPLETED)).apply {
        addRow(arrayOf(if (completed.get()) 1 else 0))
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.status"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.clawsses.phone.benchmark.status"
        const val COLUMN_COMPLETED = "completed"
        val STATUS_URI: Uri = Uri.parse("content://$AUTHORITY/workload")

        private val completed = AtomicBoolean(false)

        fun reset() = completed.set(false)

        fun markComplete() = completed.set(true)
    }
}
