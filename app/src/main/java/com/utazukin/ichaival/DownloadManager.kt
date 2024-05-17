/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

interface ImageDownloadListener {
    fun onImageDownloaded(id: String, pagesDownloaded: Int)
}

interface DownloadListener : ImageDownloadListener {
    fun onDownloadRemoved(id: String)
    fun onDownloadCanceled(id: String)
}

class DownloadJob(val job: Job) {
    var pageCount = 0
}

object DownloadManager {
    private const val downloadsPath = "downloads"
    private val scope by lazy { CoroutineScope(Dispatchers.IO) }
    private val runningDownloads = mutableMapOf<String, DownloadJob>()
    private val downloadListeners = mutableListOf<ImageDownloadListener>()
    private val downloadSemaphore by lazy { Semaphore(3) }

    fun download(id: String, overwrite: Boolean = false) {
        if (runningDownloads.containsKey(id))
            return

        val downloadDir = File(App.context.noBackupFilesDir, "$downloadsPath/$id")
        if (overwrite && downloadDir.exists())
            downloadDir.deleteRecursively()

        if (!downloadDir.mkdirs())
            return

        val thumbDir = File(downloadDir, "thumbs")
        if (!thumbDir.mkdirs())
            return

        val job = scope.launch {
            downloadSemaphore.withPermit {
                val pages = DatabaseReader.getPageList(App.context, id, true)
                for ((i, page) in pages.withIndex())
                    downloadImage(id, page, i, downloadDir, thumbDir)
                runningDownloads.remove(id)
            }
        }
        runningDownloads[id] = DownloadJob(job)
        updateListeners(id, 0)
    }

    private suspend fun downloadImage(id: String, url: String, index: Int, downloadDir: File, thumbDir: File) {
        val filename = index.toString()
        val file = File(downloadDir, filename)
        val thumbFile = File(thumbDir, filename)
        val imageDownload = scope.async { WebHandler.downloadImage(url) }
        val thumbDownload = scope.async { WebHandler.downloadThumb(id, index) }
        val (image, thumb) = listOf(imageDownload, thumbDownload).awaitAll()
        image?.use { file.outputStream().use { f -> it.copyTo(f) } }
        thumb?.use { thumbFile.outputStream().use { f -> it.copyTo(f) } }
        updateListeners(id, index + 1)
    }

    fun resumeDownload(id: String, from: Int) {
        if (runningDownloads.containsKey(id))
            return

        val downloadDir = File(App.context.noBackupFilesDir, "$downloadsPath/$id")
        if (!downloadDir.exists())
            return

        val thumbDir = File(downloadDir, "thumbs")
        if (!thumbDir.exists() && !thumbDir.mkdirs())
            return

        val job = scope.launch {
            downloadSemaphore.withPermit {
                val pages = DatabaseReader.getPageList(App.context, id, true)
                for (i in from until pages.size)
                    downloadImage(id, pages[i], i, downloadDir, thumbDir)
                runningDownloads.remove(id)
            }
        }
        runningDownloads[id] = DownloadJob(job)
        updateListeners(id, from)
    }

    fun addListener(listener: ImageDownloadListener) {
        downloadListeners.add(listener)
        for ((id, job) in runningDownloads)
            listener.onImageDownloaded(id, job.pageCount)
    }
    fun removeListener(listener: ImageDownloadListener) = downloadListeners.remove(listener)

    fun cancelDownload(id: String) {
        val downloadJob = runningDownloads.remove(id)
        if (downloadJob != null){
            downloadJob.job.cancel()
            updateCancelListeners(id)
        }
    }

    fun deleteArchive(id: String) {
        val file = File(App.context.noBackupFilesDir, "$downloadsPath/$id")
        if (file.exists())
            file.deleteRecursively()
        updateRemoveListeners(id)
    }

    fun getDownloadedPage(id: String, page: Int) : String? {
        val file = File(App.context.noBackupFilesDir, "$downloadsPath/$id/$page")
        return if (file.exists()) file.path else null
    }

    fun getDownloadThumb(id: String, page: Int) : String? {
        val file = File(App.context.noBackupFilesDir, "$downloadsPath/$id/thumbs/$page")
        return if (file.exists()) file.path else null
    }

    fun getDownloadedArchives() : List<String> {
        val downloadDir = File(App.context.noBackupFilesDir, downloadsPath)
        val files = downloadDir.listFiles() ?: return emptyList()
        files.sortBy { it.lastModified() }
        return buildList(files.size) {
            for (file in files)
                add(file.name)
        }
    }

    fun getDownloadedPageCount(id: String) : Int {
        val downloadDir = File(App.context.noBackupFilesDir, "$downloadsPath/$id")
        if (!downloadDir.exists())
            return 0

        return downloadDir.listFiles(File::isFile)?.size ?: 0
    }

    fun isDownloaded(id: String) = File(App.context.noBackupFilesDir, "$downloadsPath/$id").exists()

    fun isDownloading(id: String) = runningDownloads.containsKey(id)

    private fun updateListeners(id: String, pagesDownloaded: Int) {
        runningDownloads[id]?.pageCount = pagesDownloaded
        scope.launch(Dispatchers.Main) {
            for (listener in downloadListeners)
                listener.onImageDownloaded(id, pagesDownloaded)
        }
    }

    private fun updateRemoveListeners(id: String) {
        scope.launch(Dispatchers.Main) {
            for (listener in downloadListeners) {
                if (listener is DownloadListener)
                    listener.onDownloadRemoved(id)
            }
        }
    }

    private fun updateCancelListeners(id: String) {
        scope.launch(Dispatchers.Main) {
            for (listener in downloadListeners) {
                if (listener is DownloadListener)
                    listener.onDownloadCanceled(id)
            }
        }
    }
}