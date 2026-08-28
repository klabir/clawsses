package com.clawsses.phone.media

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PendingPhoto(
    val id: String,
    val path: String,
    val sizeBytes: Long,
    val createdAtMs: Long,
)

internal object PendingPhotoBudget {
    fun retained(
        photos: List<PendingPhoto>,
        maxCount: Int,
        maxBytes: Long,
    ): List<PendingPhoto> {
        require(maxCount > 0)
        require(maxBytes > 0)
        var bytes = 0L
        return photos.sortedByDescending(PendingPhoto::createdAtMs)
            .filter { photo ->
                val keep = photo.sizeBytes <= maxBytes && bytes + photo.sizeBytes <= maxBytes
                if (keep) bytes += photo.sizeBytes
                keep
            }
            .take(maxCount)
            .sortedBy(PendingPhoto::createdAtMs)
    }
}

/** A bounded, process-safe queue that keeps original photo bytes out of Compose state. */
class PendingPhotoRepository(context: Context) {
    private val directory = File(context.cacheDir, DIRECTORY_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _photos = MutableStateFlow<List<PendingPhoto>>(emptyList())
    val photos: StateFlow<List<PendingPhoto>> = _photos.asStateFlow()

    init {
        scope.launch { restore() }
    }

    suspend fun add(imageBytes: ByteArray): PendingPhoto? = withContext(Dispatchers.IO) {
        if (imageBytes.isEmpty() || imageBytes.size > MAX_TOTAL_BYTES) return@withContext null
        mutex.withLock {
            directory.mkdirs()
            val createdAt = System.currentTimeMillis()
            val file = File(directory, "$createdAt-${UUID.randomUUID()}.jpg")
            file.writeBytes(imageBytes)
            val added = file.toPendingPhoto(createdAt)
            applyBudget(_photos.value + added)
            added.takeIf { retained -> _photos.value.any { it.id == retained.id } }
        }
    }

    suspend fun consumeEncoded(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encoded = _photos.value.mapNotNull { photo ->
                runCatching {
                    Base64.encodeToString(File(photo.path).readBytes(), Base64.NO_WRAP)
                }.getOrNull()
            }
            _photos.value.forEach { File(it.path).delete() }
            _photos.value = emptyList()
            encoded
        }
    }

    suspend fun removeAt(index: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _photos.value
            val removed = current.getOrNull(index) ?: return@withLock
            File(removed.path).delete()
            _photos.value = current.filterIndexed { photoIndex, _ -> photoIndex != index }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _photos.value.forEach { File(it.path).delete() }
            _photos.value = emptyList()
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun restore() = mutex.withLock {
        directory.mkdirs()
        val restored = directory.listFiles().orEmpty()
            .filter(File::isFile)
            .map { file -> file.toPendingPhoto(file.lastModified()) }
        applyBudget(restored)
    }

    private fun applyBudget(candidates: List<PendingPhoto>) {
        val retained = PendingPhotoBudget.retained(candidates, MAX_COUNT, MAX_TOTAL_BYTES)
        val retainedPaths = retained.mapTo(HashSet(), PendingPhoto::path)
        candidates.filterNot { it.path in retainedPaths }.forEach { File(it.path).delete() }
        _photos.value = retained
    }

    private fun File.toPendingPhoto(createdAtMs: Long) = PendingPhoto(
        id = nameWithoutExtension,
        path = absolutePath,
        sizeBytes = length(),
        createdAtMs = createdAtMs,
    )

    companion object {
        const val MAX_COUNT = 4
        const val MAX_TOTAL_BYTES = 16L * 1024L * 1024L
        private const val DIRECTORY_NAME = "pending-photos"
    }
}
