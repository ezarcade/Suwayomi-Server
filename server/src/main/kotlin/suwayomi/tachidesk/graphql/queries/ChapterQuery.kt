/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.queries

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.dataLoaders.ChapterHidingRules
import suwayomi.tachidesk.graphql.dataLoaders.loadChapterHidingRules
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.DoubleFilter
import suwayomi.tachidesk.graphql.queries.filter.Filter
import suwayomi.tachidesk.graphql.queries.filter.HasGetOp
import suwayomi.tachidesk.graphql.queries.filter.IntFilter
import suwayomi.tachidesk.graphql.queries.filter.LongFilter
import suwayomi.tachidesk.graphql.queries.filter.OpAnd
import suwayomi.tachidesk.graphql.queries.filter.StringFilter
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompare
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareEntity
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareString
import suwayomi.tachidesk.graphql.queries.filter.applyOps
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Order
import suwayomi.tachidesk.graphql.server.primitives.OrderBy
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.server.primitives.QueryResults
import suwayomi.tachidesk.graphql.server.primitives.applyBeforeAfter
import suwayomi.tachidesk.graphql.server.primitives.applySortAndGetPaginationInfo
import suwayomi.tachidesk.graphql.server.primitives.greaterNotUnique
import suwayomi.tachidesk.graphql.server.primitives.lessNotUnique
import suwayomi.tachidesk.graphql.types.ChapterNodeList
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import java.util.concurrent.CompletableFuture

/**
 * TODO Queries
 * - Filter in library
 * - Get page list?
 */
class ChapterQuery {
    @RequireAuth
    fun chapter(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<ChapterType> = dataFetchingEnvironment.getValueFromDataLoader("ChapterDataLoader", id)

    enum class ChapterOrderBy(
        override val column: Column<*>,
    ) : OrderBy<ChapterType> {
        ID(ChapterTable.id),
        SOURCE_ORDER(ChapterTable.sourceOrder),
        NAME(ChapterTable.name),
        UPLOAD_DATE(ChapterTable.date_upload),
        CHAPTER_NUMBER(ChapterTable.chapter_number),
        LAST_READ_AT(ChapterTable.lastReadAt),
        FETCHED_AT(ChapterTable.fetchedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> ChapterTable.id greater cursor.value.toInt()
                SOURCE_ORDER -> greaterNotUnique(ChapterTable.sourceOrder, ChapterTable.id, cursor, String::toInt)
                NAME -> greaterNotUnique(ChapterTable.name, ChapterTable.id, cursor, String::toString)
                UPLOAD_DATE -> greaterNotUnique(ChapterTable.date_upload, ChapterTable.id, cursor, String::toLong)
                CHAPTER_NUMBER -> greaterNotUnique(ChapterTable.chapter_number, ChapterTable.id, cursor, String::toFloat)
                LAST_READ_AT -> greaterNotUnique(ChapterTable.lastReadAt, ChapterTable.id, cursor, String::toLong)
                FETCHED_AT -> greaterNotUnique(ChapterTable.fetchedAt, ChapterTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> ChapterTable.id less cursor.value.toInt()
                SOURCE_ORDER -> lessNotUnique(ChapterTable.sourceOrder, ChapterTable.id, cursor, String::toInt)
                NAME -> lessNotUnique(ChapterTable.name, ChapterTable.id, cursor, String::toString)
                UPLOAD_DATE -> lessNotUnique(ChapterTable.date_upload, ChapterTable.id, cursor, String::toLong)
                CHAPTER_NUMBER -> lessNotUnique(ChapterTable.chapter_number, ChapterTable.id, cursor, String::toFloat)
                LAST_READ_AT -> lessNotUnique(ChapterTable.lastReadAt, ChapterTable.id, cursor, String::toLong)
                FETCHED_AT -> lessNotUnique(ChapterTable.fetchedAt, ChapterTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: ChapterType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    SOURCE_ORDER -> type.id.toString() + "-" + type.sourceOrder
                    NAME -> type.id.toString() + "-" + type.name
                    UPLOAD_DATE -> type.id.toString() + "-" + type.uploadDate
                    CHAPTER_NUMBER -> type.id.toString() + "-" + type.chapterNumber
                    LAST_READ_AT -> type.id.toString() + "-" + type.lastReadAt
                    FETCHED_AT -> type.id.toString() + "-" + type.fetchedAt
                }
            return Cursor(value)
        }
    }

    data class ChapterOrder(
        override val by: ChapterOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<ChapterOrderBy>

    data class ChapterCondition(
        val id: Int? = null,
        val url: String? = null,
        val name: String? = null,
        val uploadDate: Long? = null,
        val chapterNumber: Float? = null,
        val scanlator: String? = null,
        val mangaId: Int? = null,
        val isRead: Boolean? = null,
        val isBookmarked: Boolean? = null,
        val lastPageRead: Int? = null,
        val lastReadAt: Long? = null,
        val sourceOrder: Int? = null,
        val realUrl: String? = null,
        val fetchedAt: Long? = null,
        val isDownloaded: Boolean? = null,
        val pageCount: Int? = null,
    ) : HasGetOp {
        override fun getOp(): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, ChapterTable.id)
            opAnd.eq(url, ChapterTable.url)
            opAnd.eq(name, ChapterTable.name)
            opAnd.eq(uploadDate, ChapterTable.date_upload)
            opAnd.eq(chapterNumber, ChapterTable.chapter_number)
            opAnd.eq(scanlator, ChapterTable.scanlator)
            opAnd.eq(mangaId, ChapterTable.manga)
            opAnd.eq(isRead, ChapterTable.isRead)
            opAnd.eq(isBookmarked, ChapterTable.isBookmarked)
            opAnd.eq(lastPageRead, ChapterTable.lastPageRead)
            opAnd.eq(lastReadAt, ChapterTable.lastReadAt)
            opAnd.eq(sourceOrder, ChapterTable.sourceOrder)
            opAnd.eq(realUrl, ChapterTable.realUrl)
            opAnd.eq(fetchedAt, ChapterTable.fetchedAt)
            opAnd.eq(isDownloaded, ChapterTable.isDownloaded)
            opAnd.eq(pageCount, ChapterTable.pageCount)

            return opAnd.op
        }
    }

    data class ChapterFilter(
        val id: IntFilter? = null,
        val url: StringFilter? = null,
        val name: StringFilter? = null,
        val uploadDate: LongFilter? = null,
        val chapterNumber: DoubleFilter? = null,
        val scanlator: StringFilter? = null,
        val mangaId: IntFilter? = null,
        val isRead: BooleanFilter? = null,
        val isBookmarked: BooleanFilter? = null,
        val lastPageRead: IntFilter? = null,
        val lastReadAt: LongFilter? = null,
        val sourceOrder: IntFilter? = null,
        val realUrl: StringFilter? = null,
        val fetchedAt: LongFilter? = null,
        val isDownloaded: BooleanFilter? = null,
        val pageCount: IntFilter? = null,
        val inLibrary: BooleanFilter? = null,
        override val and: List<ChapterFilter>? = null,
        override val or: List<ChapterFilter>? = null,
        override val not: ChapterFilter? = null,
    ) : Filter<ChapterFilter> {
        override fun getOpList(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(ChapterTable.id, id),
                andFilterWithCompareString(ChapterTable.url, url),
                andFilterWithCompareString(ChapterTable.name, name),
                andFilterWithCompare(ChapterTable.date_upload, uploadDate),
                andFilterWithCompare(ChapterTable.chapter_number, chapterNumber?.toFloatFilter()),
                andFilterWithCompareString(ChapterTable.scanlator, scanlator),
                andFilterWithCompareEntity(ChapterTable.manga, mangaId),
                andFilterWithCompare(ChapterTable.isRead, isRead),
                andFilterWithCompare(ChapterTable.isBookmarked, isBookmarked),
                andFilterWithCompare(ChapterTable.lastPageRead, lastPageRead),
                andFilterWithCompare(ChapterTable.lastReadAt, lastReadAt),
                andFilterWithCompare(ChapterTable.sourceOrder, sourceOrder),
                andFilterWithCompareString(ChapterTable.realUrl, realUrl),
                andFilterWithCompare(ChapterTable.fetchedAt, fetchedAt),
                andFilterWithCompare(ChapterTable.isDownloaded, isDownloaded),
                andFilterWithCompare(ChapterTable.pageCount, pageCount),
            )

        fun getLibraryOp() = andFilterWithCompare(MangaTable.inLibrary, inLibrary)
    }

    @RequireAuth
    fun chapters(
        condition: ChapterCondition? = null,
        filter: ChapterFilter? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: ChapterOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<ChapterOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): ChapterNodeList {
        val queryResults =
            transaction {
                val res = ChapterTable.selectAll()

                val libraryOp = filter?.getLibraryOp()
                if (libraryOp != null) {
                    res.adjustColumnSet {
                        innerJoin(MangaTable)
                    }
                    res.andWhere { libraryOp }
                }

                res.applyOps(condition, filter)

                val baseSort = listOf(ChapterOrder(ChapterOrderBy.ID, SortOrder.ASC))
                val deprecatedSort = listOfNotNull(orderBy?.let { ChapterOrder(orderBy, orderByType) })
                val actualSort = (order.orEmpty() + deprecatedSort + baseSort)

                val (total, firstResult, lastResult) = res.applySortAndGetPaginationInfo(actualSort, before, last, ChapterTable.id)

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: ChapterOrderBy.ID,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (ChapterType) -> Cursor = (order?.firstOrNull()?.by ?: ChapterOrderBy.ID)::asCursor

        val resultsAsType = queryResults.results.map { ChapterType(it) }

        val mangaId = condition?.mangaId ?: filter?.mangaId?.let { it.equalTo ?: it.`in`?.singleOrNull() }
        val filteredResults = if (mangaId != null) {
            val filteredScanlators = try {
                transaction {
                    MangaMetaTable.selectAll()
                        .where { (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq "filteredScanlators") }
                        .firstOrNull()
                        ?.let { Json.decodeFromString<List<String>>(it[MangaMetaTable.value]) }
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val hidingRules = loadChapterHidingRules(listOf(mangaId))[mangaId] ?: ChapterHidingRules()

            val allChapterNumbers = transaction {
                ChapterTable
                    .select(ChapterTable.chapter_number)
                    .where { ChapterTable.manga eq mangaId }
                    .map { it[ChapterTable.chapter_number] }
                    .toSet()
            }

            resultsAsType.filter { chapter ->
                val scanlatorOk = filteredScanlators.isEmpty() || chapter.scanlator !in filteredScanlators
                scanlatorOk && !hidingRules.shouldHide(chapter.chapterNumber, chapter.scanlator, allChapterNumbers)
            }
        } else {
            resultsAsType
        }

        return ChapterNodeList(
            filteredResults,
            if (filteredResults.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    filteredResults.firstOrNull()?.let {
                        ChapterNodeList.ChapterEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    filteredResults.lastOrNull()?.let {
                        ChapterNodeList.ChapterEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = queryResults.lastKey != filteredResults.lastOrNull()?.id,
                    hasPreviousPage = queryResults.firstKey != filteredResults.firstOrNull()?.id,
                    startCursor = filteredResults.firstOrNull()?.let { getAsCursor(it) },
                    endCursor = filteredResults.lastOrNull()?.let { getAsCursor(it) },
                ),
            totalCount = filteredResults.size,
        )
    }
}
