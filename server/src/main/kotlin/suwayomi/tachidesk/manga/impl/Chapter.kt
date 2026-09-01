package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
import eu.kanade.tachiyomi.util.chapter.ChapterSanitizer.sanitize
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.BatchUpdateStatement
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.Manga.getMangaMetaMap
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.download.DownloadManager.EnqueueInput
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.impl.util.updateChapterDownloadDir
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.PaginatedList
import suwayomi.tachidesk.manga.model.dataclass.paginatedFrom
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.manga.impl.util.getMangaDownloadDir
import suwayomi.tachidesk.server.serverConfig
import java.io.File
import java.time.Instant
import java.util.TreeSet
import kotlin.math.max

private fun List<ChapterDataClass>.removeDuplicates(currentChapter: ChapterDataClass): List<ChapterDataClass> =
    groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter.id }
                ?: chapters.find { it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }

object Chapter {
    private val logger = KotlinLogging.logger { }

    /** get chapter list when showing a manga */
    suspend fun getChapterList(
        mangaId: Int,
        onlineFetch: Boolean = false,
    ): List<ChapterDataClass> =
        if (onlineFetch) {
            getSourceChapters(mangaId).filterHidden(mangaId)
        } else {
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                    .map {
                        ChapterTable.toDataClass(it)
                    }
            }.let { chapters ->
                if (chapters.isEmpty()) {
                    getSourceChapters(mangaId).filterHidden(mangaId)
                } else {
                    chapters.filterHidden(mangaId)
                }
            }
        }

    fun getCountOfMangaChapters(mangaId: Int): Int =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .count()
                .toInt()
        }

    private suspend fun getSourceChapters(mangaId: Int): List<ChapterDataClass> {
        Manga.updateMangaAndChapters(
            mangaId,
            updateManga = false,
            updateChapters = true,
        )

        return transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                .map {
                    ChapterTable.toDataClass(it)
                }
        }
    }

    suspend fun updateChapterListDatabase(
        mangaEntry: ResultRow,
        chapters: List<SChapter>,
        source: Source,
    ): List<SChapter> {
        val currentLatestChapterNumber = Manga.getLatestChapter(mangaEntry[MangaTable.id].value)?.chapterNumber ?: 0f
        val numberOfCurrentChapters = getCountOfMangaChapters(mangaEntry[MangaTable.id].value)
        // it's possible that the source returns a list containing chapters with the same url
        // once such duplicated chapters have been added, they aren't being removed anymore as long as there is
        // a chapter with the same url in the fetched chapter list, even if the duplicated chapter itself
        // does not exist anymore on the source
        val uniqueChapters = chapters.distinctBy { it.url }

        if (uniqueChapters.isEmpty()) {
            throw Exception("No chapters found")
        }

        // Recognize number for new chapters.
        val sManga =
            SManga.create().apply {
                url = mangaEntry[MangaTable.url]
                title = mangaEntry[MangaTable.title]
                thumbnail_url = mangaEntry[MangaTable.thumbnail_url]
                artist = mangaEntry[MangaTable.artist]
                author = mangaEntry[MangaTable.author]
                description = mangaEntry[MangaTable.description]
                genre = mangaEntry[MangaTable.genre]
                status = mangaEntry[MangaTable.status]
                update_strategy = UpdateStrategy.valueOf(mangaEntry[MangaTable.updateStrategy])
                memo = mangaEntry[MangaTable.memo]
                initialized = mangaEntry[MangaTable.initialized]
            }
        uniqueChapters.forEach { chapter ->
            (source as? HttpSource)?.prepareNewChapter(chapter, sManga)
            val chapterNumber =
                ChapterRecognition.parseChapterNumber(
                    mangaEntry[MangaTable.title],
                    chapter.name,
                    chapter.chapter_number.toDouble(),
                )
            chapter.chapter_number = chapterNumber.toFloat()
            chapter.name = chapter.name.sanitize(mangaEntry[MangaTable.title])
            chapter.scanlator = chapter.scanlator?.ifBlank { null }?.trim()
        }

        val now = Instant.now().epochSecond
        // Used to not set upload date of older chapters
        // to a higher value than newer chapters
        var maxSeenUploadDate = 0L

        val chaptersInDb =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaEntry[MangaTable.id].value }
                    .map { ChapterTable.toDataClass(it) }
                    .toList()
            }

        // new chapters after they have been added to the database for auto downloads
        val insertedChapterIds = mutableListOf<Int>()

        val chaptersToInsert = mutableListOf<ChapterDataClass>() // do not yet have an ID from the database
        val chaptersToUpdate = mutableListOf<ChapterDataClass>()

        uniqueChapters.reversed().forEachIndexed { index, fetchedChapter ->
            val chapterEntry = chaptersInDb.find { it.url == fetchedChapter.url }

            val chapterData =
                ChapterDataClass.fromSChapter(
                    fetchedChapter,
                    chapterEntry?.id ?: 0,
                    index + 1,
                    now,
                    mangaEntry[MangaTable.id].value,
                    runCatching {
                        (source as? HttpSource)?.getChapterUrl(fetchedChapter)
                    }.getOrNull(),
                )

            if (chapterEntry == null) {
                val newChapterData =
                    if (chapterData.uploadDate == 0L) {
                        val altDateUpload = if (maxSeenUploadDate == 0L) now else maxSeenUploadDate
                        chapterData.copy(uploadDate = altDateUpload)
                    } else {
                        maxSeenUploadDate = max(maxSeenUploadDate, chapterData.uploadDate)
                        chapterData
                    }
                chaptersToInsert.add(newChapterData)
            } else {
                val newChapterData =
                    if (chapterData.uploadDate == 0L) {
                        chapterData.copy(uploadDate = chapterEntry.uploadDate)
                    } else {
                        chapterData
                    }
                chaptersToUpdate.add(newChapterData)
            }
        }

        val deletedChapterNumbers = TreeSet<Float>()
        val deletedReadChapterNumbers = TreeSet<Float>()
        val deletedBookmarkedChapterNumbers = TreeSet<Float>()
        val deletedDownloadedChapterByChapterNumber = mutableMapOf<Float, ChapterDataClass>()
        val deletedChapterNumberDateFetchMap = mutableMapOf<Float, Long>()

        // clear any orphaned/duplicate chapters that are in the db but not in `chapterList`
        val chapterUrls = uniqueChapters.map { it.url }.toSet()

        val chaptersIdsToDelete =
            chaptersInDb.mapNotNull { dbChapter ->
                if (!chapterUrls.contains(dbChapter.url)) {
                    if (dbChapter.read) deletedReadChapterNumbers.add(dbChapter.chapterNumber)
                    if (dbChapter.bookmarked) deletedBookmarkedChapterNumbers.add(dbChapter.chapterNumber)
                    if (dbChapter.downloaded) deletedDownloadedChapterByChapterNumber[dbChapter.chapterNumber] = dbChapter
                    deletedChapterNumbers.add(dbChapter.chapterNumber)
                    deletedChapterNumberDateFetchMap[dbChapter.chapterNumber] = dbChapter.fetchedAt
                    dbChapter.id
                } else {
                    null
                }
            }

        // delete downloaded files for orphaned chapters before removing DB records
        val downloadedOrphanIds = chaptersInDb
            .filter { !chapterUrls.contains(it.url) && it.downloaded }
            .map { it.id }
        downloadedOrphanIds.forEach { ChapterDownloadHelper.delete(mangaEntry[MangaTable.id].value, it) }
        if (downloadedOrphanIds.isNotEmpty()) {
            logger.info { "deleted ${downloadedOrphanIds.size} hidden chapter download(s) from disk" }
        }

        suspendTransaction {
            // we got some clean up due
            if (chaptersIdsToDelete.isNotEmpty()) {
                DownloadManager.dequeue(chaptersIdsToDelete)
                PageTable.deleteWhere { chapter inList chaptersIdsToDelete }
                ChapterTable.deleteWhere { id inList chaptersIdsToDelete }
            }

            if (chaptersToInsert.isNotEmpty()) {
                val insertedChapters =
                    ChapterTable
                        .batchInsert(chaptersToInsert) { chapter ->
                            this[ChapterTable.url] = chapter.url
                            this[ChapterTable.name] = chapter.name
                            this[ChapterTable.date_upload] = chapter.uploadDate
                            this[ChapterTable.chapter_number] = chapter.chapterNumber
                            this[ChapterTable.scanlator] = chapter.scanlator
                            this[ChapterTable.sourceOrder] = chapter.index
                            this[ChapterTable.fetchedAt] = chapter.fetchedAt
                            this[ChapterTable.manga] = chapter.mangaId
                            this[ChapterTable.realUrl] = chapter.realUrl
                            this[ChapterTable.memo] = chapter.memo
                            this[ChapterTable.isRead] = false
                            this[ChapterTable.isBookmarked] = false
                            this[ChapterTable.isDownloaded] = false
                            this[ChapterTable.lastModifiedAt] = chapter.lastModifiedAt
                            this[ChapterTable.version] = chapter.version
                            this[ChapterTable.pageCount] = -1

                            // is recognized chapter number
                            if (chapter.chapterNumber >= 0f && chapter.chapterNumber in deletedChapterNumbers) {
                                this[ChapterTable.isRead] = chapter.chapterNumber in deletedReadChapterNumbers
                                this[ChapterTable.isBookmarked] = chapter.chapterNumber in deletedBookmarkedChapterNumbers

                                // Try to use the fetch date of the original entry to not pollute 'Updates' tab
                                deletedChapterNumberDateFetchMap[chapter.chapterNumber]?.let {
                                    this[ChapterTable.fetchedAt] = it
                                }
                            }
                        }.map { ChapterTable.toDataClass(it) }

                insertedChapters.forEach { insertedChapterIds.add(it.id) }

                val chaptersToPreserveDownload =
                    insertedChapters.filter { chapter ->
                        val deletedChapter =
                            deletedDownloadedChapterByChapterNumber[chapter.chapterNumber] ?: return@filter false

                        // For a new (unrecognized) chapter, we have to handle the existing downloads as obsolete in case the scanlator changed because we can't assume that the pages are still the same
                        val isSameScanlator = chapter.scanlator == deletedChapter.scanlator
                        val isPreservable = isSameScanlator && updateChapterDownloadDir(deletedChapter, chapter)

                        isPreservable
                    }

                if (chaptersToPreserveDownload.isNotEmpty()) {
                    BatchUpdateStatement(ChapterTable)
                        .apply {
                            chaptersToPreserveDownload.forEach {
                                addBatch(EntityID(it.id, ChapterTable))

                                this[ChapterTable.isDownloaded] = true
                                this[ChapterTable.pageCount] = deletedDownloadedChapterByChapterNumber[it.chapterNumber]!!.pageCount
                            }
                        }.toExecutable()
                        .execute(this@suspendTransaction)
                }
            }

            if (chaptersToUpdate.isNotEmpty()) {
                BatchUpdateStatement(ChapterTable)
                    .apply {
                        chaptersToUpdate.forEach {
                            addBatch(EntityID(it.id, ChapterTable))

                            val currentChapter = chaptersInDb.find { dbChapter -> dbChapter.id == it.id }!!

                            this[ChapterTable.name] = it.name
                            this[ChapterTable.date_upload] = it.uploadDate
                            this[ChapterTable.chapter_number] = it.chapterNumber
                            this[ChapterTable.scanlator] = it.scanlator
                            this[ChapterTable.sourceOrder] = it.index
                            this[ChapterTable.realUrl] = it.realUrl
                            this[ChapterTable.memo] = it.memo
                            this[ChapterTable.isDownloaded] = currentChapter.downloaded
                            this[ChapterTable.pageCount] = currentChapter.pageCount

                            if (!currentChapter.downloaded) {
                                return@forEach
                            }

                            val isDownloadPreservable = updateChapterDownloadDir(currentChapter, it)
                            if (!isDownloadPreservable) {
                                this[ChapterTable.isDownloaded] = false
                                this[ChapterTable.pageCount] = -1
                            }
                        }
                    }.toExecutable()
                    .execute(this@suspendTransaction)
            }

            MangaTable.update({ MangaTable.id eq mangaEntry[MangaTable.id].value }) {
                it[chaptersLastFetchedAt] = Instant.now().epochSecond
            }
        }

        val mangaId = mangaEntry[MangaTable.id].value
        val autoDeleteOrphanFractional = transaction {
            MangaMetaTable.selectAll()
                .where { (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq "autoDeleteOrphanFractional") }
                .firstOrNull()?.let { it[MangaMetaTable.value].toBoolean() }
        } ?: false

        if (autoDeleteOrphanFractional && insertedChapterIds.isNotEmpty()) {
            val insertedChaptersForCheck =
                transaction {
                    ChapterTable
                        .select(ChapterTable.id, ChapterTable.chapter_number, ChapterTable.manga)
                        .where { ChapterTable.id inList insertedChapterIds }
                        .map { it[ChapterTable.id].value to it[ChapterTable.chapter_number] }
                }

            val integerChapterNumbers = insertedChaptersForCheck
                .map { (_, num) -> num }
                .filter { it % 1f == 0f && it >= 0f }
                .toSet()

            if (integerChapterNumbers.isNotEmpty()) {
                val chaptersToDeleteDownloads =
                    transaction {
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga eq mangaId) and (ChapterTable.isDownloaded eq true) }
                            .map { ChapterTable.toDataClass(it) }
                            .filter { dbChapter ->
                                val isFractional = dbChapter.chapterNumber % 1f != 0f
                                val intPart = dbChapter.chapterNumber.toInt().toFloat()
                                isFractional && intPart in integerChapterNumbers
                            }
                    }

                if (chaptersToDeleteDownloads.isNotEmpty()) {
                    val chapterIdsToDelete = chaptersToDeleteDownloads.map { it.id }
                    logger.info { "auto-deleting ${chapterIdsToDelete.size} orphan fractional download(s)" }
                    chaptersToDeleteDownloads.forEach { ChapterDownloadHelper.delete(mangaId, it.id) }
                    transaction {
                        ChapterTable.update({ ChapterTable.id inList chapterIdsToDelete }) {
                            it[isDownloaded] = false
                            it[pageCount] = -1
                        }
                    }
                }
            }
        }

        if (mangaEntry[MangaTable.inLibrary]) {
            // We have to query the inserted chapters to get the up-to-date data. I.e. "last_modified_at" is not returned by the insert statement, due to being set by a DB trigger
            val insertedChapters =
                transaction {
                    ChapterTable.selectAll().where { ChapterTable.id inList insertedChapterIds }.map(
                        ChapterTable::toDataClass,
                    )
                }
            downloadNewChapters(
                mangaId,
                currentLatestChapterNumber,
                numberOfCurrentChapters,
                insertedChapters,
            )
        }

        return uniqueChapters
    }

    private fun downloadNewChapters(
        mangaId: Int,
        prevLatestChapterNumber: Float,
        prevNumberOfChapters: Int,
        newChapters: List<ChapterDataClass>,
    ) {
        val log =
            KotlinLogging.logger(
                "${logger.name}::downloadNewChapters(" +
                    "mangaId= $mangaId, " +
                    "prevLatestChapterNumber= $prevLatestChapterNumber, " +
                    "prevNumberOfChapters= $prevNumberOfChapters, " +
                    "newChapters= ${newChapters.size}, " +
                    "autoDownloadNewChaptersLimit= ${serverConfig.autoDownloadNewChaptersLimit.value}, " +
                    "autoDownloadIgnoreReUploads= ${serverConfig.autoDownloadIgnoreReUploads.value}" +
                    ")",
            )

        if (!serverConfig.autoDownloadNewChapters.value) {
            log.debug { "automatic download is not configured" }
            return
        }

        if (newChapters.isEmpty()) {
            log.debug { "no new chapters available" }
            return
        }

        val filteredScanlators = getMangaMetaMap(mangaId)["filteredScanlators"]?.let {
            try {
                Json.decodeFromString<List<String>>(it)
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()

        val nonFilteredNewChapters = if (filteredScanlators.isNotEmpty()) {
            newChapters.filter { it.scanlator !in filteredScanlators }
        } else {
            newChapters
        }

        if (nonFilteredNewChapters.isEmpty()) {
            log.debug { "no new chapters available after scanlator filter" }
            return
        }

        val wasInitialFetch = prevNumberOfChapters == 0
        if (wasInitialFetch) {
            log.debug { "skipping download on initial fetch" }
            return
        }

        if (!Manga.isInIncludedDownloadCategory(log, mangaId)) {
            return
        }

        val unreadChapters = Manga.getUnreadChapters(mangaId).subtract(nonFilteredNewChapters.toSet())

        val skipDueToUnreadChapters = serverConfig.excludeEntryWithUnreadChapters.value && unreadChapters.isNotEmpty()
        if (skipDueToUnreadChapters) {
            log.debug { "ignore due to unread chapters" }
            return
        }

        val hidingRules = loadChapterHidingRulesForManga(mangaId)
        val chaptersToDownload = nonFilteredNewChapters.filter { !hidingRules.shouldHide(it.chapterNumber, it.scanlator) }

        if (chaptersToDownload.isEmpty()) {
            log.debug { "no new chapters available after hiding rules filter" }
            return
        }

        val chapterIdsToDownload = getNewChapterIdsToDownload(chaptersToDownload, prevLatestChapterNumber)

        if (chapterIdsToDownload.isEmpty()) {
            log.debug { "no chapters available for download" }
            return
        }

        log.info { "download ${chapterIdsToDownload.size} new chapter(s)..." }

        DownloadManager.enqueue(EnqueueInput(chapterIdsToDownload))
    }

    private fun getNewChapterIdsToDownload(
        newChapters: List<ChapterDataClass>,
        prevLatestChapterNumber: Float,
    ): List<Int> {
        val reUploadedChapters = newChapters.filter { it.chapterNumber < prevLatestChapterNumber }
        val actualNewChapters = newChapters.subtract(reUploadedChapters.toSet()).toList()
        val chaptersToConsiderForDownloadLimit =
            if (serverConfig.autoDownloadIgnoreReUploads.value) {
                if (actualNewChapters.isNotEmpty()) actualNewChapters.removeDuplicates(actualNewChapters[0]) else emptyList()
            } else {
                newChapters.removeDuplicates(newChapters[0])
            }.sortedBy { it.index }

        val latestChapterToDownloadIndex =
            if (serverConfig.autoDownloadNewChaptersLimit.value == 0) {
                chaptersToConsiderForDownloadLimit.size
            } else {
                serverConfig.autoDownloadNewChaptersLimit.value.coerceIn(0, chaptersToConsiderForDownloadLimit.size)
            }
        val limitedChaptersToDownload = chaptersToConsiderForDownloadLimit.subList(0, latestChapterToDownloadIndex)
        val limitedChaptersToDownloadWithDuplicates =
            (
                limitedChaptersToDownload +
                    newChapters.filter { newChapter ->
                        limitedChaptersToDownload.find { it.chapterNumber == newChapter.chapterNumber } != null
                    }
            ).toSet()

        return limitedChaptersToDownloadWithDuplicates.map { it.id }
    }

    fun modifyChapter(
        mangaId: Int,
        chapterIndex: Int,
        isRead: Boolean?,
        isBookmarked: Boolean?,
        markPrevRead: Boolean?,
        lastPageRead: Int?,
    ): Int {
        val chapterId =
            transaction {
                val chapter =
                    ChapterTable
                        .selectAll()
                        .where { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }
                        .first()

                val chapterIdValue = chapter[ChapterTable.id].value

                if (listOf(isRead, isBookmarked, lastPageRead).any { it != null }) {
                    ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }) { update ->
                        isRead?.also {
                            update[ChapterTable.isRead] = it
                        }
                        isBookmarked?.also {
                            update[ChapterTable.isBookmarked] = it
                        }
                        lastPageRead?.also {
                            update[ChapterTable.lastPageRead] = it
                            update[lastReadAt] = Instant.now().epochSecond
                        }
                    }
                }

                markPrevRead?.let {
                    ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder less chapterIndex) }) {
                        it[ChapterTable.isRead] = markPrevRead
                    }
                }

                chapterIdValue
            }

        if (isRead == true || markPrevRead == true) {
            Track.asyncTrackChapter(setOf(mangaId))
        }

        return chapterId
    }

    @Serializable
    data class ChapterChange(
        val isRead: Boolean? = null,
        val isBookmarked: Boolean? = null,
        val lastPageRead: Int? = null,
        val delete: Boolean? = null,
    )

    @Serializable
    data class MangaChapterBatchEditInput(
        val chapterIds: List<Int>? = null,
        val chapterIndexes: List<Int>? = null,
        val change: ChapterChange?,
    )

    @Serializable
    data class ChapterBatchEditInput(
        val chapterIds: List<Int>? = null,
        val change: ChapterChange?,
    )

    suspend fun modifyChapters(
        input: MangaChapterBatchEditInput,
        mangaId: Int? = null,
    ) {
        // Make sure change is defined
        if (input.change == null) return
        val (isRead, isBookmarked, lastPageRead, delete) = input.change

        // Handle deleting separately
        if (delete == true) {
            deleteChapters(input, mangaId)
        }

        // return early if there are no other changes
        if (listOfNotNull(isRead, isBookmarked, lastPageRead).isEmpty()) return

        // Make sure some filter is defined
        val condition =
            when {
                mangaId != null -> {
                    // mangaId is not null, scope query under manga
                    when {
                        input.chapterIds != null -> {
                            (ChapterTable.manga eq mangaId) and (ChapterTable.id inList input.chapterIds)
                        }

                        input.chapterIndexes != null -> {
                            (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder inList input.chapterIndexes)
                        }

                        else -> {
                            null
                        }
                    }
                }

                else -> {
                    // mangaId is null, only chapterIndexes is valid for this case
                    when {
                        input.chapterIds != null -> {
                            (ChapterTable.id inList input.chapterIds)
                        }

                        else -> {
                            null
                        }
                    }
                }
            } ?: return

        transaction {
            val now = Instant.now().epochSecond
            ChapterTable.update({ condition }) { update ->
                isRead?.also {
                    update[ChapterTable.isRead] = it
                }
                isBookmarked?.also {
                    update[ChapterTable.isBookmarked] = it
                }
                lastPageRead?.also {
                    update[ChapterTable.lastPageRead] = it
                    update[lastReadAt] = now
                }
            }
        }

        if (isRead == true) {
            val mangaIds =
                transaction {
                    ChapterTable
                        .selectAll()
                        .where(condition)
                        .map { it[ChapterTable.manga].value }
                        .toSet()
                }
            Track.asyncTrackChapter(mangaIds)
        }
    }

    fun getChaptersMetaMaps(chapterIds: List<Int>): Map<Int, Map<String, String>> =
        transaction {
            ChapterMetaTable
                .selectAll()
                .where { ChapterMetaTable.ref inList chapterIds }
                .groupBy { it[ChapterMetaTable.ref].value }
                .mapValues { it.value.associate { it[ChapterMetaTable.key] to it[ChapterMetaTable.value] } }
                .withDefault { emptyMap() }
        }

    fun getChapterMetaMap(chapter: Int): Map<String, String> =
        transaction {
            ChapterMetaTable
                .selectAll()
                .where { ChapterMetaTable.ref eq chapter }
                .associate { it[ChapterMetaTable.key] to it[ChapterMetaTable.value] }
        }

    fun modifyChapterMeta(
        mangaId: Int,
        chapterIndex: Int,
        key: String,
        value: String,
    ) {
        transaction {
            val chapterId =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }
                    .first()[ChapterTable.id]
                    .value
            modifyChapterMeta(chapterId, key, value)
        }
    }

    fun modifyChapterMeta(
        chapterId: Int,
        key: String,
        value: String,
    ) {
        modifyChaptersMetas(mapOf(chapterId to mapOf(key to value)))
    }

    fun modifyChaptersMetas(metaByChapterId: Map<Int, Map<String, String>>) {
        transaction {
            val chapterIds = metaByChapterId.keys
            val metaKeys = metaByChapterId.flatMap { it.value.keys }

            val dbMetaByChapterId =
                ChapterMetaTable
                    .selectAll()
                    .where { (ChapterMetaTable.ref inList chapterIds) and (ChapterMetaTable.key inList metaKeys) }
                    .groupBy { it[ChapterMetaTable.ref].value }

            val existingMetaByMetaId =
                chapterIds.flatMap { chapterId ->
                    val dbMetaByKey = dbMetaByChapterId[chapterId].orEmpty().associateBy { it[ChapterMetaTable.key] }
                    val existingMetas = metaByChapterId[chapterId].orEmpty().filter { (key) -> key in dbMetaByKey.keys }

                    existingMetas.map { entry ->
                        val metaId = dbMetaByKey[entry.key]!![ChapterMetaTable.id].value

                        metaId to entry
                    }
                }

            val newMetaByChapterId =
                chapterIds.flatMap { chapterId ->
                    val dbMetaByKey = dbMetaByChapterId[chapterId].orEmpty().associateBy { it[ChapterMetaTable.key] }

                    metaByChapterId[chapterId]
                        .orEmpty()
                        .filter { entry -> entry.key !in dbMetaByKey.keys }
                        .map { entry -> chapterId to entry }
                }

            if (existingMetaByMetaId.isNotEmpty()) {
                BatchUpdateStatement(ChapterMetaTable)
                    .apply {
                        existingMetaByMetaId.forEach { (metaId, entry) ->
                            addBatch(EntityID(metaId, ChapterMetaTable))
                            this[ChapterMetaTable.value] = entry.value
                        }
                    }.toExecutable()
                    .execute(this@transaction)
            }

            if (newMetaByChapterId.isNotEmpty()) {
                ChapterMetaTable.batchInsert(newMetaByChapterId) { (chapterId, entry) ->
                    this[ChapterMetaTable.ref] = EntityID(chapterId, ChapterTable)
                    this[ChapterMetaTable.key] = entry.key
                    this[ChapterMetaTable.value] = entry.value
                }
            }
        }
    }

    suspend fun deleteChapter(
        mangaId: Int,
        chapterIndex: Int,
    ) {
        suspendTransaction {
            val chapterId =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }
                    .first()[ChapterTable.id]
                    .value

            ChapterDownloadHelper.delete(mangaId, chapterId)

            ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }) {
                it[isDownloaded] = false
            }
        }
    }

    private suspend fun deleteChapters(
        input: MangaChapterBatchEditInput,
        mangaId: Int? = null,
    ) {
        if (input.chapterIds != null) {
            deleteChapters(input.chapterIds)
        } else if (input.chapterIndexes != null && mangaId != null) {
            suspendTransaction {
                val chapterIds =
                    ChapterTable
                        .select(ChapterTable.manga, ChapterTable.id)
                        .where {
                            (ChapterTable.sourceOrder inList input.chapterIndexes) and
                                (ChapterTable.manga eq mangaId)
                        }.map { row ->
                            val chapterId = row[ChapterTable.id].value
                            ChapterDownloadHelper.delete(mangaId, chapterId)

                            chapterId
                        }

                ChapterTable.update({ ChapterTable.id inList chapterIds }) {
                    it[isDownloaded] = false
                }
            }
        }
    }

    fun getHiddenDownloadedChapters(mangaId: Int): List<ChapterDataClass> {
        val allChapters = transaction {
            ChapterTable.selectAll()
                .where { ChapterTable.manga eq mangaId }
                .map { ChapterTable.toDataClass(it) }
        }
        val result = mutableListOf<ChapterDataClass>()

        if (allChapters.isNotEmpty()) {
            val rules = loadChapterHidingRulesForManga(mangaId)
            val numbers = allChapters.map { it.chapterNumber }.toSet()
            val filteredScanlators = getMangaMetaMap(mangaId)["filteredScanlators"]?.let {
                try { Json.decodeFromString<List<String>>(it).toSet() } catch (_: Exception) { emptySet() }
            } ?: emptySet()
            result.addAll(allChapters.filter { chapter ->
                chapter.downloaded && (
                    chapter.scanlator in filteredScanlators ||
                    rules.shouldHide(chapter.chapterNumber, chapter.scanlator, numbers)
                )
            })
        }

        // scan filesystem for orphaned download files that have no DB record
        val rootDir = File(runBlocking { getMangaDownloadDir(mangaId) })
        if (rootDir.exists()) {
            val knownDirs = allChapters.map { chapter ->
                xyz.nulldev.androidcompat.util.SafePath.buildValidFilename(
                    if (chapter.scanlator != null) "${chapter.scanlator}_${chapter.name}" else chapter.name
                )
            }.toSet()
            rootDir.listFiles()?.forEach { entry ->
                val name = entry.name.removeSuffix(".cbz")
                if (name !in knownDirs) {
                    result.add(ChapterDataClass(
                        id = 0,
                        url = entry.absolutePath,
                        name = entry.name,
                        uploadDate = 0L,
                        chapterNumber = -1f,
                        scanlator = null,
                        mangaId = mangaId,
                        read = false,
                        bookmarked = false,
                        lastPageRead = 0,
                        lastReadAt = 0,
                        index = -1,
                        fetchedAt = 0,
                        downloaded = true,
                        realUrl = entry.absolutePath,
                    ))
                }
            }
        }

        return result
    }

    fun pruneHiddenDownloads(mangaId: Int): Int {
        val hiddenDownloaded = getHiddenDownloadedChapters(mangaId)
        var total = 0

        val normalChapters = hiddenDownloaded.filter { it.id > 0 }
        val orphanChapters = hiddenDownloaded.filter { it.id == 0 }

        if (normalChapters.isNotEmpty()) {
            normalChapters.forEach { runBlocking { ChapterDownloadHelper.delete(mangaId, it.id) } }
            val ids = normalChapters.map { it.id }
            transaction {
                ChapterTable.update({ ChapterTable.id inList ids }) {
                    it[isDownloaded] = false
                    it[pageCount] = -1
                }
            }
            total += normalChapters.size
            logger.info { "pruned ${normalChapters.size} hidden chapter download(s) from DB for manga $mangaId" }
        }

        if (orphanChapters.isNotEmpty()) {
            orphanChapters.forEach { chapter ->
                val path = chapter.realUrl
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }
                }
            }
            total += orphanChapters.size
            logger.info { "deleted ${orphanChapters.size} orphaned download file(s) from disk for manga $mangaId" }
        }

        return total
    }

    suspend fun deleteChapters(chapterIds: List<Int>) {
        suspendTransaction {
            ChapterTable
                .select(ChapterTable.manga, ChapterTable.id)
                .where { ChapterTable.id inList chapterIds }
                .forEach { row ->
                    val chapterMangaId = row[ChapterTable.manga].value
                    val chapterId = row[ChapterTable.id].value
                    ChapterDownloadHelper.delete(chapterMangaId, chapterId)
                }

            ChapterTable.update({ ChapterTable.id inList chapterIds }) {
                it[isDownloaded] = false
            }
        }
    }

    fun getRecentChapters(pageNum: Int): PaginatedList<MangaChapterDataClass> =
        paginatedFrom(pageNum) {
            transaction {
                (ChapterTable innerJoin MangaTable)
                    .selectAll()
                    .where { (MangaTable.inLibrary eq true) and (ChapterTable.fetchedAt greater MangaTable.inLibraryAt) }
                    .orderBy(ChapterTable.fetchedAt to SortOrder.DESC)
                    .map {
                        MangaChapterDataClass(
                            MangaTable.toDataClass(it),
                            ChapterTable.toDataClass(it),
                        )
                    }
            }
        }

    fun updateChapterProgress(
        mangaId: Int,
        chapterIndex: Int,
        pageNo: Int,
    ): Int {
        val chapterData =
            transaction {
                ChapterTable
                    .selectAll()
                    .where {
                        (ChapterTable.sourceOrder eq chapterIndex) and
                            (ChapterTable.manga eq mangaId)
                    }.first()
                    .let { ChapterTable.toDataClass(it) }
            }

        val oneIndexedPageNo = pageNo.inc()
        val isRead = chapterData.pageCount.takeIf { it == oneIndexedPageNo }?.let { true }

        modifyChapter(
            mangaId,
            chapterIndex,
            isRead = isRead,
            lastPageRead = pageNo,
            isBookmarked = null,
            markPrevRead = null,
        )

        return chapterData.id
    }

    private data class ChapterHidingRules(
        val hideBelowOne: Boolean = false,
        val hideFractional: Boolean = false,
        val hideHalf: Boolean = false,
        val showOrphanFractional: Boolean = false,
        val hiddenNumbers: Set<Float> = emptySet(),
        val exemptedScanlators: Set<String> = emptySet(),
        val hiddenChapterScanlatorPairs: Set<String> = emptySet(),
    ) {
        private fun parentExists(chapterNumber: Float, allChapterNumbers: Set<Float>?): Boolean {
            if (allChapterNumbers == null) return true
            val intPart = chapterNumber.toInt().toFloat()
            return intPart in allChapterNumbers
        }

        fun shouldHide(chapterNumber: Float, scanlator: String? = null, allChapterNumbers: Set<Float>? = null): Boolean {
            if (scanlator in exemptedScanlators) return false
            if (hideBelowOne && chapterNumber < 1f) return true
            if (hideFractional && chapterNumber % 1f != 0f) {
                if (showOrphanFractional && !parentExists(chapterNumber, allChapterNumbers)) return false
                return true
            }
            if (hideHalf && chapterNumber % 1f == 0.5f) return true
            if (chapterNumber in hiddenNumbers) return true
            val keyNum = if (chapterNumber % 1f == 0f) chapterNumber.toInt().toString() else chapterNumber.toString()
            val pairKey = "$keyNum:$scanlator"
            if (pairKey in hiddenChapterScanlatorPairs) return true
            return false
        }
    }

    private fun loadChapterHidingRulesForManga(mangaId: Int): ChapterHidingRules = transaction {
        val hidingKeys = listOf("hideChaptersBelowOne", "hideFractionalChapters", "hideHalfChapters", "showOrphanFractional", "hiddenChapterNumbers", "hiddenChapterScanlatorException", "hiddenChapterScanlatorPairs")
        val metaRows = MangaMetaTable
            .selectAll()
            .where { (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key inList hidingKeys) }

        val metas = metaRows.toList()
        ChapterHidingRules(
            hideBelowOne = metas.find { it[MangaMetaTable.key] == "hideChaptersBelowOne" }
                ?.let { it[MangaMetaTable.value].toBoolean() } ?: false,
            hideFractional = metas.find { it[MangaMetaTable.key] == "hideFractionalChapters" }
                ?.let { it[MangaMetaTable.value].toBoolean() } ?: false,
            hideHalf = metas.find { it[MangaMetaTable.key] == "hideHalfChapters" }
                ?.let { it[MangaMetaTable.value].toBoolean() } ?: false,
            showOrphanFractional = metas.find { it[MangaMetaTable.key] == "showOrphanFractional" }
                ?.let { it[MangaMetaTable.value].toBoolean() } ?: false,
            hiddenNumbers = metas.find { it[MangaMetaTable.key] == "hiddenChapterNumbers" }
                ?.let { row ->
                    row[MangaMetaTable.value].split(",")
                        .mapNotNull { s -> s.trim().toFloatOrNull() }
                        .toSet()
                } ?: emptySet(),
            exemptedScanlators = metas.find { it[MangaMetaTable.key] == "hiddenChapterScanlatorException" }
                ?.let { row ->
                    row[MangaMetaTable.value].split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                } ?: emptySet(),
            hiddenChapterScanlatorPairs = metas.find { it[MangaMetaTable.key] == "hiddenChapterScanlatorPairs" }
                ?.let { row ->
                    row[MangaMetaTable.value].split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { pair ->
                            val parts = pair.split(":", limit = 2)
                            if (parts.size == 2) {
                                val num = parts[0].toFloatOrNull()
                                val keyNum = if (num != null && num % 1f == 0f) num.toInt().toString() else parts[0]
                                "$keyNum:${parts[1]}"
                            } else pair
                        }
                        .toSet()
                } ?: emptySet(),
        )
    }

    private fun List<ChapterDataClass>.filterHidden(mangaId: Int): List<ChapterDataClass> {
        val rules = loadChapterHidingRulesForManga(mangaId)
        val numbers = this.map { it.chapterNumber }.toSet()
        return this.filter { chapter -> !rules.shouldHide(chapter.chapterNumber, chapter.scanlator, numbers) }
    }
}
