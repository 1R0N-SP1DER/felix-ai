package com.felix.faze

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Shows each connected computer in the phone's Files picker as "Your PC (name)", read only. */
class PcDocuments : DocumentsProvider() {
    private val core: FazeCore get() = (context!!.applicationContext as FazeApp).core

    private fun files() = PcFiles { pc, msg -> core.link.ask(pc, msg, 60_000) }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON))
        try {
            val on = core.link.connected()
            for (p in core.linkPeers.all().filter { it.id in on }) {
                c.newRow().add(Root.COLUMN_ROOT_ID, p.id).add(Root.COLUMN_FLAGS, 0).add(Root.COLUMN_TITLE, "Your PC (${p.name})")
                    .add(Root.COLUMN_DOCUMENT_ID, PcFiles.id(p.id)).add(Root.COLUMN_ICON, context!!.applicationInfo.icon)
            }
        } catch (e: Exception) {
            // the app is still starting: no computers yet
        }
        return c
    }

    private fun row(c: MatrixCursor, e: PcEntry) {
        val mime = if (e.dir) Document.MIME_TYPE_DIR else MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, e.docId).add(Document.COLUMN_DISPLAY_NAME, e.name).add(Document.COLUMN_SIZE, e.size)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_LAST_MODIFIED, e.mtime).add(Document.COLUMN_FLAGS, 0)
    }

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: columns)
        val (pc, folder, path) = PcFiles.parse(documentId) ?: throw FileNotFoundException(documentId)
        val name = if (path.isEmpty()) (if (folder.isEmpty()) "Your PC" else folder) else path.substringAfterLast('/')
        // A document is a folder when it is a computer or shared folder, or when the id was handed out as a folder; files are told apart by their listing.
        val parent = if (path.contains('/')) PcFiles.id(pc, folder, path.substringBeforeLast('/')) else PcFiles.id(pc, folder)
        val entry = if (path.isEmpty()) PcEntry(documentId, name, true, 0, 0) else files().children(parent).firstOrNull { it.docId == documentId } ?: throw FileNotFoundException(documentId)
        row(c, entry)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: columns)
        try {
            for (e in files().children(parentDocumentId)) row(c, e)
        } catch (e: java.io.IOException) {
            throw FileNotFoundException(e.message)
        }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.any { it == 'w' || it == 'a' }) throw SecurityException("the computer's files are read only")
        val dir = File(context!!.cacheDir, "pcfiles").apply { mkdirs() }
        val file = File(dir, documentId.hashCode().toUInt().toString(16) + "_" + documentId.substringAfterLast('/').filter { it.isLetterOrDigit() || it == '.' }.takeLast(40))
        try {
            files().download(documentId, file)
        } catch (e: java.io.IOException) {
            file.delete()
            throw FileNotFoundException(e.message)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith(parentDocumentId.trimEnd('/'))
}
