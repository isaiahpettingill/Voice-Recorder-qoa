package org.fossify.voicerecorder

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** A real provider with opaque IDs and generic QOA MIME, never filesystem-shaped IDs. */
class TestDocumentsProvider : DocumentsProvider() {
    private data class Entry(val id: String, var name: String, val parent: String?, val directory: Boolean)
    private val entries = linkedMapOf("root" to Entry("root", "Recordings", null, true))
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    private val rootDir get() = File(requireNotNull(context).cacheDir, "document-provider").apply { mkdirs() }
    override fun onCreate() = true
    @Synchronized override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
        addRow(arrayOf("test", "root", "QOA test provider", Root.FLAG_SUPPORTS_CREATE))
    }
    private fun row(cursor: MatrixCursor, e: Entry) {
        val file = File(rootDir, e.id)
        cursor.newRow().apply {
            for (column in cursor.columnNames) add(when (column) {
                Document.COLUMN_DOCUMENT_ID -> e.id
                Document.COLUMN_DISPLAY_NAME -> e.name
                Document.COLUMN_MIME_TYPE -> if (e.directory) Document.MIME_TYPE_DIR else "application/octet-stream"
                Document.COLUMN_FLAGS -> if (e.directory) Document.FLAG_DIR_SUPPORTS_CREATE else
                    Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                Document.COLUMN_SIZE -> if (e.directory) 0L else file.length()
                Document.COLUMN_LAST_MODIFIED -> System.currentTimeMillis()
                else -> null
            })
        }
    }
    @Synchronized override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).also { row(it, entries[documentId] ?: throw FileNotFoundException()) }
    @Synchronized override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: columns).also { result -> entries.values.filter { it.parent == parentDocumentId }.forEach { row(result, it) } }
    @Synchronized override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(entries[parentDocumentId]?.directory == true)
        val id = "opaque:${UUID.randomUUID()}"
        entries[id] = Entry(id, displayName, parentDocumentId, mimeType == Document.MIME_TYPE_DIR)
        if (mimeType != Document.MIME_TYPE_DIR) File(rootDir, id).createNewFile()
        return id
    }
    @Synchronized override fun renameDocument(documentId: String, displayName: String): String {
        if (displayName.startsWith("reject")) throw FileNotFoundException("Provider rejects rename")
        (entries[documentId] ?: throw FileNotFoundException()).name = displayName
        return documentId
    }
    @Synchronized override fun deleteDocument(documentId: String) {
        check(documentId != "root")
        entries.values.filter { it.parent == documentId }.map { it.id }.forEach { deleteDocument(it) }
        entries.remove(documentId) ?: throw FileNotFoundException()
        File(rootDir, documentId).delete()
    }
    @Synchronized override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = entries[documentId]
        while (current != null) {
            if (current.id == parentDocumentId) return true
            current = entries[current.parent]
        }
        return false
    }
    @Synchronized override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val entry = entries[documentId] ?: throw FileNotFoundException()
        if (entry.directory) throw FileNotFoundException()
        if ("w" in mode && entries[entry.parent]?.name == "Reject output") throw FileNotFoundException("Provider rejects output")
        return ParcelFileDescriptor.open(File(rootDir, documentId), ParcelFileDescriptor.parseMode(mode))
    }
}
