package io.github.cdgeass.aliyunpanprovider

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import androidx.core.content.edit
import io.github.cdgeass.AliyunpanClient
import io.github.cdgeass.model.CreateWithFoldersRequest
import io.github.cdgeass.model.CreateWithFoldersResponse
import io.github.cdgeass.model.FileItem
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.FileNotFoundException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap


class AliyunpanProvider : DocumentsProvider() {

    companion object {
        private const val AUTHORITY = "io.github.cdgeass.aliyunpanprovider.documents"
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
        private val UPLOAD_SESSION: ConcurrentHashMap<String, CreateWithFoldersResponse> =
            ConcurrentHashMap()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context!!.getSharedPreferences("AliyunpanProvider", MODE_PRIVATE)
    }

    private val client: AliyunpanClient = AliyunpanClient()

    private val refreshToken: String?
        get() {
            return sharedPreferences.getString("refreshToken", null)
        }
    private var authorization: String?
        get() {
            return sharedPreferences.getString("authorization", null)
        }
        set(value) {
            sharedPreferences.edit { putString("authorization", value) }
        }
    private var backupDriveId: String
        get() {
            return sharedPreferences.getString("backupDriveId", null) ?: ""
        }
        set(value) {
            sharedPreferences.edit { putString("backupDriveId", value) }
        }
    private var resourceDriveId: String
        get() {
            return sharedPreferences.getString("resourceDriveId", null) ?: ""
        }
        set(value) {
            sharedPreferences.edit { putString("resourceDriveId", value) }
        }

    override fun onCreate(): Boolean {
        if (authorization == null) {
            if (refreshToken == null) {
                return false
            }
            try {
                val getAccessTokenResponse = client.getAccessToken(refreshToken).get()
                authorization = getAccessTokenResponse.authorization
            } catch (e: Exception) {
                Log.e("AliyunpanProvider", "Failed to refresh token", e)
                return false
            }
        }

        try {
            val getUserResponse = client.getUser(authorization).get()
            backupDriveId = getUserResponse.backupDriveId
            resourceDriveId = getUserResponse.resourceDriveId
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to get user ${e.message}", e)
            return false
        }

        Log.d(
            "AliyunpanProvider",
            "onCreate backupDriveId: $backupDriveId; resourceDriveId: $resourceDriveId"
        )
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "backup")
            add(DocumentsContract.Root.COLUMN_TITLE, "阿里云盘(备份盘)")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, backupDriveId)
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "resource")
            add(DocumentsContract.Root.COLUMN_TITLE, "阿里云盘(资源盘)")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, resourceDriveId)
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
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
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                )
                add(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    if (documentId == backupDriveId) "阿里云盘(备份盘)" else "阿里云盘(资源盘)"
                )
            }
        } else if (documentId != "null") {
            try {
                val (driveId, fileId) = getDriveIdAndFileId(documentId)
                val fileItem = client.getFile(authorization!!, driveId, fileId).get()
                includeFile(result, fileItem)
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
                val listFileResponse =
                    client.listFile(authorization, driveId, fileId, nextMarker)
                        .get()

                listFileResponse.items.forEach { item ->
                    includeFile(result, item)
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

    override fun isChildDocument(
        parentDocumentId: String?,
        documentId: String?
    ): Boolean {
        val (parentDriveId, _) = getDriveIdAndFileId(parentDocumentId)
        val (driveId, _) = getDriveIdAndFileId(documentId)

        return parentDriveId == driveId
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.d("AliyunpanProvider", "openDocument: $documentId")

        val (driveId, fileId) = getDriveIdAndFileId(documentId)

        val accessMode = ParcelFileDescriptor.parseMode(mode)
        if ((accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY) == 0) {
            // read file
            val cacheDir = context?.cacheDir
            val tempFile = File(cacheDir, "$fileId.tmp")

            if (tempFile.length() == 0L) {
                try {
                    val getDownloadUrlResponse =
                        client.getDownloadUrl(authorization, driveId, fileId).get()

                    downloadFile(getDownloadUrlResponse.url, tempFile)
                } catch (e: Exception) {
                    Log.e("AliyunpanProvider", "Failed to open document $documentId", e)
                    throw e
                }
            }
            return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            // write file
            try {
                val createWithFoldersResponse = UPLOAD_SESSION[documentId]!!

                val pipes = ParcelFileDescriptor.createPipe()
                val readPipe = pipes[0]
                val writePipe = pipes[1]

                Thread {
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(readPipe).use { inputStream ->
                            val bytes = inputStream.readBytes()

                            val request = Request.Builder()
                                .url(createWithFoldersResponse.partInfoList[0].uploadUrl)
                                .header("Content-Length", "${bytes.size}")
                                .put(bytes.toRequestBody(null, 0, bytes.size))
                                .build()
                            val response = OkHttpClient().newCall(request).execute()

                            if (!response.isSuccessful) {
                                throw IOException("Failed to upload document $documentId")
                            }

                            client.completeFile(
                                authorization,
                                driveId,
                                createWithFoldersResponse.uploadId,
                                fileId,
                            ).get()

                            context?.contentResolver?.notifyChange(
                                DocumentsContract.buildDocumentUri(
                                    AUTHORITY,
                                    documentId
                                ), null
                            )
                        }
                    } catch (e: IOException) {
                        Log.e("MyCloudProvider", "Error during upload streaming", e)
                        throw e
                    }
                }.start()

                return writePipe
            } catch (e: Exception) {
                Log.e("AliyunpanProvider", "Failed to write document $documentId", e)
                throw e
            }
        }
    }

    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        Log.d("AliyunpanProvider", "openDocumentThumbnail: $documentId")

        val (driveId, fileId) = getDriveIdAndFileId(documentId)

        val cacheDir = context?.cacheDir
        val thumbnail = File(cacheDir, "$fileId.thumbnail")

        if (thumbnail.length() == 0L) {
            try {
                val fileItem = client.getFile(authorization, driveId, fileId).get()

                downloadFile(fileItem.thumbnail!!, thumbnail)
            } catch (e: Exception) {
                Log.e("AliyunpanProvider", "Failed to get thumbnail $documentId", e)
                throw e
            }
        }
        val pfd = ParcelFileDescriptor.open(thumbnail, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, thumbnail.length())
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String? {
        var (driveId, parentFileId) = getDriveIdAndFileId(parentDocumentId)
        parentFileId = parentFileId ?: "root"

        val createFileRequest = CreateWithFoldersRequest()
        createFileRequest.driveId = driveId
        createFileRequest.parentFileId = parentFileId
        createFileRequest.name = displayName
        createFileRequest.type = "file"
        createFileRequest.createScene = "overwrite"

        try {
            val createFileResponse =
                client.createWithFolders(authorization, createFileRequest).get()

            val documentId = "$driveId-${createFileResponse.fileId}"
            UPLOAD_SESSION[documentId] = createFileResponse
            return documentId
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to create document", e)
            throw e
        }
    }

    override fun deleteDocument(documentId: String?) {
        Log.d("AliyunpanProvider", "deleteDocument: $documentId")

        val (driveId, fileId) = getDriveIdAndFileId(documentId)
        try {
            client.trashRecyclebin(authorization, driveId, fileId).get()
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to delete document", e)
            throw e
        }
    }

    override fun querySearchDocuments(
        rootId: String?,
        query: String?,
        projection: Array<out String?>?
    ): Cursor? {
        Log.d("AliyunpanProvider", "querySearchDocument: $rootId $query")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val driveId = if (rootId == "backup") backupDriveId else resourceDriveId
        try {
            val searchFileResponse = client.searchFile(authorization, listOf(driveId), query).get()
            searchFileResponse.items.forEach {
                includeFile(result, it)
            }
        } catch (e: Exception) {
            Log.e("AliyunpanProvider", "Failed to search file", e)
        }

        return result
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

    private fun includeFile(result: MatrixCursor, fileItem: FileItem) {
        var flags = 0
        flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        if (fileItem.type == "folder") {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        if (fileItem.thumbnail?.isNotEmpty() == true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        }

        result.newRow().apply {
            add(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                "${fileItem.driveId}-${fileItem.fileId}"
            )
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                fileItem.mimeType ?: DocumentsContract.Document.MIME_TYPE_DIR
            )
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, fileItem.name)
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(DocumentsContract.Document.COLUMN_SIZE, fileItem.size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, fileItem.updatedAt.toTimestamp())
        }
    }

    private fun downloadFile(downloadUrl: String, tempFile: File) {
        val response = OkHttpClient().newCall(
            Request.Builder().url(downloadUrl)
                .addHeader("Referer", "https://www.alipan.com/")
                .build()
        ).execute()

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

    private fun String.toTimestamp(): Long {
        return Instant.parse(this).toEpochMilli()
    }
}