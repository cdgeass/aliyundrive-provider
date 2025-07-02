package io.github.cdgeass.aliyundriveprovider

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.core.content.edit
import io.github.cdgeass.AliyunpanClient
import io.github.cdgeass.model.CreateWithFoldersRequest
import io.github.cdgeass.model.CreateWithFoldersResponse
import io.github.cdgeass.model.FileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap


class AliyundriveProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "AliyundriveProvider"
        private const val AUTHORITY = "io.github.cdgeass.aliyundriveprovider.documents"
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client: AliyunpanClient = AliyunpanClient()
    private val directoryCache: ConcurrentHashMap<Uri, List<FileItem>> = ConcurrentHashMap()

    private val storageManager: StorageManager by lazy {
        context!!.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    }
    private val fileHandler = lazy {
        Handler(
            HandlerThread(this.javaClass.simpleName)
                .apply { start() }
                .looper
        )
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context!!.getSharedPreferences(TAG, Context.MODE_PRIVATE)
    }
    private val refreshToken: String?
        get() {
            return sharedPreferences.getString("refreshToken", null)
        }
    private val authorization: String?
        get() {
            return sharedPreferences.getString("authorization", null)
        }

    private var backupDriveId: String?
        get() {
            return sharedPreferences.getString("backupDriveId", null)
        }
        set(value) {
            sharedPreferences.edit { putString("backupDriveId", value) }
        }
    private var resourceDriveId: String?
        get() {
            return sharedPreferences.getString("resourceDriveId", null)
        }
        set(value) {
            sharedPreferences.edit { putString("resourceDriveId", value) }
        }

    private suspend fun getAuthorization(): String {
        if (refreshToken == null) {
            throw UserNotAuthenticatedException()
        }

        var authorization = sharedPreferences.getString("authorization", null)
        var expiredAt = sharedPreferences.getLong("expiredAt", 0L)
        if (authorization != null && System.currentTimeMillis() < expiredAt) {
            return authorization
        }

        val response = client.getAccessToken(refreshToken).await()
        authorization = response.authorization
        expiredAt = System.currentTimeMillis() + response.expiresIn() * 1000

        sharedPreferences.edit {
            putString("authorization", authorization)
            putLong("expiredAt", expiredAt)
        }

        return authorization
    }

    override fun onCreate(): Boolean {
        return refreshToken != null
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        if (backupDriveId == null || resourceDriveId == null) {
            val uri = DocumentsContract.buildRootsUri(AUTHORITY)
            result.apply {
                extras = Bundle().apply {
                    putBoolean(DocumentsContract.EXTRA_LOADING, true)
                }
                setNotificationUri(context?.contentResolver, uri)
            }.also {
                applicationScope.launch {
                    try {
                        val response = client.getUser(getAuthorization()).await()
                        backupDriveId = response.backupDriveId
                        resourceDriveId = response.resourceDriveId

                        context?.contentResolver?.notifyChange(uri, null, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get user ${e.message}", e)
                    }
                }
            }
        } else {
            result.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, "backup")
                add(DocumentsContract.Root.COLUMN_TITLE, "阿里云盘(备份盘)")
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, backupDriveId)
                add(
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                            or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
                            or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            }
            result.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, "resource")
                add(DocumentsContract.Root.COLUMN_TITLE, "阿里云盘(资源盘)")
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, resourceDriveId)
                add(
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                            or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
                            or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            }
        }

        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        Log.d(TAG, "queryDocument: $documentId")

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
                val (driveId, parentDocumentId, fileId) = getDriveIdAndFileId(documentId)
                val fileItem = client.getFile(authorization!!, driveId, fileId).get()
                includeFile(result, parentDocumentId, fileItem)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get file", e)
            }
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        Log.d(TAG, "queryChildDocument $parentDocumentId")

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val (driveId, _, fileId) = getDriveIdAndFileId(parentDocumentId)
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId)

        val fileItems = directoryCache[uri]
        if (fileItems == null) {
            result.apply {
                extras = Bundle().apply {
                    putBoolean(DocumentsContract.EXTRA_LOADING, true)
                }
                setNotificationUri(context?.contentResolver, uri)
            }.also {
                applicationScope.launch {
                    try {
                        val authorization = getAuthorization()
                        val fileItems = mutableListOf<FileItem>()
                        var nextMarker: String? = null

                        while (true) {
                            val listFileResponse = client.listFile(
                                authorization, driveId, fileId, nextMarker
                            ).await()

                            fileItems.addAll(listFileResponse.items)

                            nextMarker = listFileResponse.nextMarker
                            if (nextMarker.isEmpty()) {
                                break
                            }
                        }

                        directoryCache.put(uri, fileItems)
                        context?.contentResolver?.notifyChange(uri, null, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to list file", e)
                    }
                }
            }
        } else {
            fileItems.forEach {
                includeFile(result, parentDocumentId, it)
            }
        }

        return result
    }

    override fun isChildDocument(
        parentDocumentId: String?, documentId: String?
    ): Boolean {
        return (parentDocumentId == null) or (documentId?.startsWith(parentDocumentId!!) == true)
    }

    override fun openDocument(
        documentId: String?, mode: String?, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.d(TAG, "openDocument: $documentId")

        val (driveId, parentDocumentId, fileId) = getDriveIdAndFileId(documentId)

        val accessMode = ParcelFileDescriptor.parseMode(mode)
        if ((accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY) == 0) {
            // read file
            try {
                val getDownloadUrlResponse = client.getDownloadUrl(
                    authorization, driveId, fileId
                ).get()
                val url = getDownloadUrlResponse.url
                val size = getDownloadUrlResponse.size

                return storageManager.openProxyFileDescriptor(
                    ParcelFileDescriptor.parseMode(mode),
                    object : ProxyFileDescriptorCallback() {
                        override fun onGetSize(): Long {
                            return size
                        }

                        override fun onRead(
                            offset: Long,
                            size: Int,
                            data: ByteArray?
                        ): Int {
                            Log.d(TAG, "Open document end $documentId $offset-$size")
                            try {
                                val response = OkHttpClient().newCall(
                                    Request.Builder()
                                        .url(url)
                                        .addHeader("Range", "bytes=$offset-${offset + size - 1}")
                                        .build()
                                ).execute()
                                if (!response.isSuccessful) {
                                    throw FileNotFoundException("Failed to open file ${response.body?.string()}")
                                }

                                var bytesRead = 0
                                response.body?.use { body ->
                                    body.byteStream().use { inputStream ->
                                        while (bytesRead < size) {
                                            val offset = inputStream.read(
                                                data, bytesRead, size - bytesRead
                                            )
                                            if (offset == -1) {
                                                break
                                            }
                                            bytesRead += offset
                                        }
                                    }
                                }

                                return bytesRead
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to openDocument $documentId $offset-$size")
                                throw e
                            }
                        }

                        override fun onRelease() {
                            Log.d(TAG, "Open document end $documentId")
                        }
                    },
                    fileHandler.value
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open document $documentId", e)
                throw e
            }
        } else {
            // write file
            val createWithFoldersResponse = UPLOAD_SESSION[documentId]!!

            val (readPipe, writePipe) = ParcelFileDescriptor.createPipe()
            applicationScope.launch {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readPipe).use { inputStream ->
                        val bytes = inputStream.readBytes()

                        val request = Request.Builder()
                            .url(createWithFoldersResponse.partInfoList[0].uploadUrl)
                            .header("Content-Length", "${bytes.size}")
                            .put(bytes.toRequestBody(null, 0, bytes.size)).build()
                        val response = withContext(Dispatchers.IO) {
                            OkHttpClient().newCall(request).execute()
                        }

                        if (!response.isSuccessful) {
                            throw IOException("Failed to upload document $documentId")
                        }

                        client.completeFile(
                            authorization,
                            driveId,
                            createWithFoldersResponse.uploadId,
                            fileId
                        ).await()

                        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId)
                        directoryCache.remove(uri)
                        context?.contentResolver?.notifyChange(uri, null, 0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write document $documentId", e)
                    throw e
                }
            }

            return writePipe
        }
    }

    override fun openDocumentThumbnail(
        documentId: String?, sizeHint: Point?, signal: CancellationSignal?
    ): AssetFileDescriptor? {
        Log.d(TAG, "openDocumentThumbnail: $documentId")

        val (driveId, _, fileId) = getDriveIdAndFileId(documentId)

        val cacheDir = context?.cacheDir
        val thumbnail = File(cacheDir, "$documentId.thumbnail")

        if (!thumbnail.exists()) {
            applicationScope.launch {
                try {
                    thumbnail.parentFile?.mkdirs()
                    thumbnail.createNewFile()

                    val fileItem = client.getFile(authorization, driveId, fileId).await()
                    withContext(Dispatchers.IO) {
                        downloadFile(fileItem.thumbnail, thumbnail)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get thumbnail $documentId", e)
                    throw e
                }
            }
        }
        val pfd = ParcelFileDescriptor.open(thumbnail, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, thumbnail.length())
    }

    override fun createDocument(
        parentDocumentId: String?, mimeType: String?, displayName: String?
    ): String? {
        Log.d(TAG, "createDocument $parentDocumentId")

        val (driveId, _, parentFileId) = getDriveIdAndFileId(parentDocumentId)

        val createFileRequest = CreateWithFoldersRequest().apply {
            this.driveId = driveId
            this.parentFileId = parentFileId ?: "root"
            name = displayName
            type = "file"
            createScene = "overwrite"
        }

        try {
            val response = client.createWithFolders(authorization, createFileRequest).get()

            val documentId = "$parentDocumentId/${response.fileId}"
            UPLOAD_SESSION[documentId] = response

            return documentId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create document", e)
            throw e
        }
    }

    override fun deleteDocument(documentId: String?) {
        Log.d(TAG, "deleteDocument: $documentId")

        val (driveId, parentDocumentId, fileId) = getDriveIdAndFileId(documentId)
        try {
            client.trashRecyclebin(authorization, driveId, fileId).get()

            val uri = DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId)
            directoryCache.remove(uri)
            context?.contentResolver?.notifyChange(uri, null, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete document", e)
            throw e
        }
    }

    override fun querySearchDocuments(
        rootId: String?, query: String?, projection: Array<out String?>?
    ): Cursor? {
        Log.d(TAG, "querySearchDocument: $rootId $query")

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val driveId = if (rootId == "backup") backupDriveId else resourceDriveId
        try {
            val searchFileResponse = client.searchFile(authorization, listOf(driveId), query).get()
            searchFileResponse.items.forEach {
                includeFile(result, rootId, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search file", e)
        }

        return result
    }

    private fun getDriveIdAndFileId(documentId: String?): Triple<String?, String?, String?> {
        if (documentId == null) {
            return Triple(null, null, null)
        }
        if (!documentId.contains("/")) {
            return Triple(documentId, null, null)
        }

        val documentIds = documentId.split("/")
        return Triple(
            documentIds[0],
            documentIds.dropLast(1).joinToString("/"),
            documentIds.last()
        )
    }

    private fun includeFile(result: MatrixCursor, parentDocumentId: String?, fileItem: FileItem) {
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
                "$parentDocumentId/${fileItem.fileId}"
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

    private fun downloadFile(downloadUrl: String, file: File) {
        val response = OkHttpClient().newCall(
            Request.Builder()
                .url(downloadUrl)
                .addHeader("Referer", "https://www.alipan.com/")
                .build()
        ).execute()

        if (response.isSuccessful) {
            response.body?.byteStream()?.use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } else {
            Log.e(TAG, "Failed to download file: ${response.body}")
            throw FileNotFoundException("${response.body}")
        }
    }

    private fun String.toTimestamp(): Long {
        return Instant.parse(this).toEpochMilli()
    }
}