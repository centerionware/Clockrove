package io.github.centerionware.clockrove.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

/** Bounded SAF tree access for automatic backups. No path is exposed to Dart. */
internal object AutoBackupFolder {
    internal class Failure(val code: String, message: String) : Exception(message)

    fun hasWritePermission(context: Context, folder: String): Boolean {
        val uri = try { Uri.parse(folder) } catch (_: Exception) { return false }
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun displayName(context: Context, folder: String): String? {
        val uri = try { Uri.parse(folder) } catch (_: Exception) { return null }
        if (!DocumentsContract.isTreeUri(uri)) return null
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri),
        )
        return try {
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun create(context: Context, folder: String, name: String): Uri {
        val tree = folderUri(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                "application/octet-stream",
                name,
            ) ?: throw Failure("FOLDER_WRITE_FAILED", "The backup file could not be created")
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("FOLDER_WRITE_FAILED", "The backup file could not be created")
        }
    }

    fun openOutput(context: Context, document: Uri): OutputStream =
        try {
            context.contentResolver.openOutputStream(document, "w")
                ?: throw Failure("FOLDER_WRITE_FAILED", "The backup file could not be opened")
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("FOLDER_WRITE_FAILED", "The backup file could not be opened")
        }

    fun listManaged(context: Context, folder: String): List<AutoBackupRetention.Entry> {
        val tree = folderUri(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return try {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val result = mutableListOf<AutoBackupRetention.Entry>()
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (!AutoBackupRetention.isManagedName(name)) continue
                    result.add(
                        AutoBackupRetention.Entry(
                            documentId = id,
                            name = name,
                            modifiedMillis = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                            partial = name.endsWith(
                                AutoBackupRetention.FILE_SUFFIX +
                                    AutoBackupRetention.FILE_PARTIAL_SUFFIX,
                            ),
                        ),
                    )
                }
                result
            } ?: throw Failure("FOLDER_READ_FAILED", "The backup folder could not be read")
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("FOLDER_READ_FAILED", "The backup folder could not be read")
        }
    }

    fun prune(context: Context, folder: String, nowMillis: Long): Int {
        val entries = listManaged(context, folder)
        if (entries.isEmpty()) return 0
        val retained = AutoBackupRetention.retainedDocumentIds(
            entries.filter { !it.partial },
            nowMillis,
        )
        val tree = folderUri(folder)
        var removed = 0
        for (entry in entries) {
            if (!entry.partial && entry.documentId in retained) continue
            val document = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                entry.documentId,
            )
            try {
                if (DocumentsContract.deleteDocument(context.contentResolver, document)) {
                    removed++
                }
            } catch (_: Exception) {
                // A provider may refuse one deletion; keep the completed backup and
                // retry the remaining excess on the next daily run.
            }
        }
        return removed
    }

    fun rename(context: Context, document: Uri, finalName: String): Uri =
        try {
            DocumentsContract.renameDocument(
                context.contentResolver,
                document,
                finalName,
            ) ?: throw Failure("FOLDER_WRITE_FAILED", "The backup file could not be finalized")
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("FOLDER_WRITE_FAILED", "The backup file could not be finalized")
        }

    fun delete(context: Context, folder: String, document: Uri) {
        try {
            val tree = folderUri(folder)
            val real = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(document),
            )
            DocumentsContract.deleteDocument(context.contentResolver, real)
        } catch (_: Exception) {
            // The next retention pass removes an abandoned partial file.
        }
    }

    private fun folderUri(folder: String): Uri {
        val uri = try { Uri.parse(folder) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) {
            throw Failure("FOLDER_UNAVAILABLE", "Choose the backup folder again")
        }
        return uri
    }
}
