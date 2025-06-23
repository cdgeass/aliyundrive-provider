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
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileNotFoundException
import java.io.File

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
    private lateinit var backupDriveId: String
    private lateinit var resourceDriveId: String

    override fun onCreate(): Boolean {
        authorization = sharedPreferences?.getString("authorization", null) ?: return false

        try {
            val getUserResponse = client.getUser(authorization).get()
            backupDriveId = getUserResponse.backupDriveId
            resourceDriveId = getUserResponse.resourceDriveId
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to get authorization", e)
            return false
        }

        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "backup")
            add(DocumentsContract.Root.COLUMN_TITLE, "备份盘")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "阿里云盘")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, backupDriveId)
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
        }
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "resource")
            add(DocumentsContract.Root.COLUMN_TITLE, "资源盘")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "阿里云盘")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, resourceDriveId)
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
        }

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        Log.d("AliyunpanProvider", "queryDocument: $documentId")

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        if (documentId == backupDriveId || documentId == resourceDriveId) {
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                add(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                add(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    if (documentId == backupDriveId) "备份盘" else "资源盘"
                )
            }
        } else if (documentId != "null") {
            try {
                val (driveId, fileId) = getDriveIdAndFileId(documentId)
                val fileItem = client.getFile(authorization, driveId, fileId).get()

                result.newRow().apply {
                    add(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        "$driveId-${fileItem.fileId}"
                    )
                    add(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        fileItem.mimeType ?: DocumentsContract.Document.MIME_TYPE_DIR
                    )
                    add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, fileItem.name)
                }
            } catch (e: Exception) {
                Log.e("AliyunpanProvider", "Failed to get file", e)
            }
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
            val (driveId, fileId) = getDriveIdAndFileId(parentDocumentId)
            var nextMarker: String? = null
            while (true) {
                var listFileResponse =
                    client.listFile(authorization, driveId, fileId, nextMarker)
                        .get()

                listFileResponse.items.forEach { item ->
                    result.newRow().apply {
                        add(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            "$driveId-${item.fileId}"
                        )
                        add(
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            item.mimeType ?: DocumentsContract.Document.MIME_TYPE_DIR
                        )
                        add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, item.name)
                    }
                }

                nextMarker = listFileResponse.nextMarker
                if (nextMarker.isEmpty()) {
                    break
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
        Log.d("AliyunpanProvider", "openDocument: $documentId")

        val cacheDir = context?.cacheDir
        val tempFile = File.createTempFile(documentId!!, null, cacheDir)
        try {
            val (driveId, fileId) = getDriveIdAndFileId(documentId)
            val getDownloadUrlResponse =
                client.getDownloadUrl(authorization, driveId, fileId).get()

            downloadFile(getDownloadUrlResponse.url, tempFile)
            return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to open document", e)
            throw e
        }
    }

    private fun getDriveIdAndFileId(documentId: String?): Pair<String?, String?> {
        if (documentId == null) {
            return Pair(null, null)
        }

        if (documentId.contains("-")) {
            val parts = documentId.split("-")
            return Pair(parts[0], parts[1])
        } else {
            return Pair(documentId, null)
        }
    }

    private fun downloadFile(downloadUrl: String, tempFile: File) {
        val response = OkHttpClient().newCall(Request.Builder().url(downloadUrl).build()).execute()
        if (response.isSuccessful) {
            response.body?.byteStream()?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } else {
            Log.e("AliyunpanProvider", "Failed to download file: ${response.body}")
            throw FileNotFoundException("${response.body}")
        }
    }
}