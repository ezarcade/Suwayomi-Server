/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.dataLoaders

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.model.table.MangaMetaTable

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
