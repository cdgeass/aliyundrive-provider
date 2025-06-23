package io.github.cdgeass.aliyunpanprovider

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import io.github.cdgeass.AliyunpanClient

class AliyunpanProvider : DocumentsProvider() {

    companion object {
        private val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }

    private val sharedPreferences: SharedPreferences? by lazy {
        context?.getSharedPreferences("AliyunpanProvider", MODE_PRIVATE)
    }

    private val client: AliyunpanClient = AliyunpanClient()

    private lateinit var authorization: String
    private lateinit var driveId: String

    override fun onCreate(): Boolean {
        var refreshToken = sharedPreferences?.getString("refreshToken", "") ?: return false

        try {
            val getAccessTokenResponse = client.getAccessToken(refreshToken).get()
            authorization = getAccessTokenResponse.accessToken

            val getUserResponse = client.getUser(authorization).get()
            driveId = getUserResponse.defaultDriveId
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to get authorization", e)
            return false
        }

        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "aliyunpan")
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.MIME_TYPE_ITEM)
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
            add(DocumentsContract.Root.COLUMN_TITLE, "阿里云盘")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "阿里云盘")
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "root")
        }

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        Log.d("AliyunpanProvider", "queryDocument: $documentId")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        result.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "root")
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.MIME_TYPE_DIR
            )
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "阿里云盘")
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        try {
            val listFileResponse =
                client.listFile(authorization, driveId, parentDocumentId, null).get()
            listFileResponse.items.forEach { item ->
                result.newRow().apply {
                    add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, item.fileId)
                    add(DocumentsContract.Document.COLUMN_MIME_TYPE, item.mimeType)
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, item.name)
                }
            }
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to list file", e)
        }

        return result
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        TODO("Not yet implemented")
    }
}