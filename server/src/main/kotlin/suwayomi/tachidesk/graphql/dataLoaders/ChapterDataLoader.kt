/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.dataLoaders

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.v1.core.Slf4jSqlDebugLogger
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.ChapterNodeList
import suwayomi.tachidesk.graphql.types.ChapterNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.server.JavalinSetup.future
import kotlinx.serialization.json.Json

class ChapterDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "ChapterDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapters =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.id inList ids }
                            .map { ChapterType(it) }
                            .associateBy { it.id }
                    ids.map { chapters[it] }
                }
            }
        }
}

class ChaptersForMangaDataLoader : KotlinDataLoader<Int, ChapterNodeList> {
    override val dataLoaderName = "ChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterNodeList> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val filteredScanlatorsByManga = MangaMetaTable
                        .selectAll()
                        .where { (MangaMetaTable.ref inList ids) and (MangaMetaTable.key eq "filteredScanlators") }
                        .associate { it[MangaMetaTable.ref].value to it[MangaMetaTable.value] }
                        .mapValues { (_, value) ->
                            try {
                                Json.decodeFromString<List<String>>(value)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }

                    val chapterHidingRulesByManga = loadChapterHidingRules(ids)

                    val allChapterTypes =
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga inList ids }
                            .map { ChapterType(it) }

                    val chapterNumbersByManga = allChapterTypes
                        .groupBy { it.mangaId }
                        .mapValues { (_, chapters) -> chapters.map { it.chapterNumber }.toSet() }

                    val chaptersByMangaId = allChapterTypes
                        .groupBy { it.mangaId }
                        .mapValues { (mangaId, chapters) ->
                            val filtered = filteredScanlatorsByManga[mangaId] ?: emptyList()
                            val rules = chapterHidingRulesByManga[mangaId] ?: ChapterHidingRules()
                            val numbers = chapterNumbersByManga[mangaId] ?: emptySet()
                            chapters.filter { chapter ->
                                (chapter.scanlator !in filtered) && !rules.shouldHide(chapter.chapterNumber, chapter.scanlator, numbers)
                            }
                        }
                    ids.map { (chaptersByMangaId[it] ?: emptyList()).toNodeList() }
                }
            }
        }
}

class DownloadedChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "DownloadedChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val downloadedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.isDownloaded.count())
                            .where {
                                (ChapterTable.manga inList ids) and
                                    (ChapterTable.isDownloaded eq true)
                            }.groupBy(ChapterTable.manga)
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.isDownloaded.count()] }
                    ids.map { downloadedChapterCountByMangaId[it]?.toInt() ?: 0 }
                }
            }
        }
}

class UnreadChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "UnreadChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val filteredScanlatorsByManga = MangaMetaTable
                        .selectAll()
                        .where { (MangaMetaTable.ref inList ids) and (MangaMetaTable.key eq "filteredScanlators") }
                        .associate { it[MangaMetaTable.ref].value to it[MangaMetaTable.value] }
                        .mapValues { (_, value) ->
                            try {
                                Json.decodeFromString<List<String>>(value)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }

                    val chapterHidingRulesByManga = loadChapterHidingRules(ids)

                    val allChapterNumbersByManga = ChapterTable
                        .select(ChapterTable.manga, ChapterTable.chapter_number)
                        .where { ChapterTable.manga inList ids }
                        .groupBy { it[ChapterTable.manga].value }
                        .mapValues { (_, rows) -> rows.map { it[ChapterTable.chapter_number] }.toSet() }

                    val unreadChapters =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterTable.isRead eq false) }
                            .filter {
                                val mangaId = it[ChapterTable.manga].value
                                val filtered = filteredScanlatorsByManga[mangaId] ?: emptyList()
                                val rules = chapterHidingRulesByManga[mangaId] ?: ChapterHidingRules()
                                val numbers = allChapterNumbersByManga[mangaId] ?: emptySet()
                                (it[ChapterTable.scanlator] !in filtered) && !rules.shouldHide(it[ChapterTable.chapter_number], it[ChapterTable.scanlator], numbers)
                            }
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { unreadChapters[it]?.size ?: 0 }
                }
            }
        }
}

class BookmarkedChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "BookmarkedChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val bookmarkedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.isBookmarked.count())
                            .where {
                                (ChapterTable.manga inList ids) and
                                    (ChapterTable.isBookmarked eq true)
                            }.groupBy(ChapterTable.manga)
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.isBookmarked.count()] }
                    ids.map { bookmarkedChapterCountByMangaId[it]?.toInt() ?: 0 }
                }
            }
        }
}

class HasDuplicateChaptersForMangaDataLoader : KotlinDataLoader<Int, Boolean> {
    override val dataLoaderName = "HasDuplicateChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Boolean> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val duplicatedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.chapter_number, ChapterTable.chapter_number.count())
                            .where {
                                (
                                    ChapterTable.manga inList
                                        ids
                                ) and
                                    (ChapterTable.chapter_number greaterEq 0f)
                            }.groupBy(ChapterTable.manga, ChapterTable.chapter_number)
                            .having { ChapterTable.chapter_number.count() greater 1 }
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.chapter_number.count()] }

                    ids.map { duplicatedChapterCountByMangaId.contains(it) }
                }
            }
        }
}

class LastReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LastReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val lastReadChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.lastReadAt to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> lastReadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class LatestReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LatestReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestReadChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterTable.isRead eq true) }
                            .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestReadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class LatestFetchedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LatestFetchedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestFetchedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.fetchedAt to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestFetchedChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class LatestUploadedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "LatestUploadedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestUploadedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.date_upload to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestUploadedChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class FirstUnreadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "FirstUnreadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val filteredScanlatorsByManga = MangaMetaTable
                        .selectAll()
                        .where { (MangaMetaTable.ref inList ids) and (MangaMetaTable.key eq "filteredScanlators") }
                        .associate { it[MangaMetaTable.ref].value to it[MangaMetaTable.value] }
                        .mapValues { (_, value) ->
                            try {
                                Json.decodeFromString<List<String>>(value)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }

                    val chapterHidingRulesByManga = loadChapterHidingRules(ids)

                    val allChapterNumbersByManga = ChapterTable
                        .select(ChapterTable.manga, ChapterTable.chapter_number)
                        .where { ChapterTable.manga inList ids }
                        .groupBy { it[ChapterTable.manga].value }
                        .mapValues { (_, rows) -> rows.map { it[ChapterTable.chapter_number] }.toSet() }

                    val firstUnreadChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterTable.isRead eq false) }
                            .orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                            .filter {
                                val mangaId = it[ChapterTable.manga].value
                                val filtered = filteredScanlatorsByManga[mangaId] ?: emptyList()
                                val rules = chapterHidingRulesByManga[mangaId] ?: ChapterHidingRules()
                                val numbers = allChapterNumbersByManga[mangaId] ?: emptySet()
                                (it[ChapterTable.scanlator] !in filtered) && !rules.shouldHide(it[ChapterTable.chapter_number], it[ChapterTable.scanlator], numbers)
                            }
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> firstUnreadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class HighestNumberedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType> {
    override val dataLoaderName = "HighestNumberedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val highestNumberedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterTable.chapter_number greater 0f) }
                            .orderBy(ChapterTable.chapter_number to SortOrder.DESC_NULLS_LAST)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id ->
                        highestNumberedChaptersByMangaId[id]
                            ?.firstOrNull()
                            ?.let { chapter -> ChapterType(chapter) }
                    }
                }
            }
        }
}

data class ChapterHidingRules(
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

fun loadChapterHidingRules(ids: List<Int>): Map<Int, ChapterHidingRules> = transaction {
    val hidingKeys = listOf("hideChaptersBelowOne", "hideFractionalChapters", "hideHalfChapters", "showOrphanFractional", "hiddenChapterNumbers", "hiddenChapterScanlatorException", "hiddenChapterScanlatorPairs")
    val metaRows = MangaMetaTable
        .selectAll()
        .where { (MangaMetaTable.ref inList ids) and (MangaMetaTable.key inList hidingKeys) }

    val metaByManga = metaRows.groupBy { it[MangaMetaTable.ref].value }

    ids.associateWith { mangaId ->
        val metas = metaByManga[mangaId] ?: emptyList()
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
}
