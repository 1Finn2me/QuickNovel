package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.MainActivity.Companion.app
import java.net.URLEncoder

class FanFictionProvider : MainAPI() {
    override val name = "FanFiction.net"
    override val mainUrl = "https://www.fanfiction.net"
    override val hasMainPage = true

    override val iconId = R.drawable.ic_fanfiction
    override val iconBackgroundId = R.color.fanfiction_header_color

    // Mapped from FFN's filter modal (censorid / ratings)
    override val mainCategories = listOf(
        "All Ratings" to "10",
        "K to T" to "103",
        "K to K+" to "102",
        "Rated K" to "1",
        "Rated K+" to "2",
        "Rated T" to "3",
        "Rated M" to "4"
    )

    // Mapped from FFN's filter modal (sortid)
    override val orderBys = listOf(
        "Update Date" to "1",
        "Publish Date" to "2",
        "Reviews" to "3",
        "Favorites" to "4",
        "Follows" to "5"
    )

    // Handpicked popular fandoms
    override val tags = listOf(
        "Just In" to "",
        "Harry Potter" to "book/Harry-Potter/",
        "Naruto" to "anime/Naruto/",
        "Twilight" to "book/Twilight/",
        "Percy Jackson and the Olympians" to "book/Percy-Jackson-and-the-Olympians/",
        "Spider-Man" to "comic/Spider-Man/",
        "Batman" to "comic/Batman/",
        "Avengers" to "movie/Avengers/",
        "Star Wars" to "movie/Star-Wars/",
        "My Hero Academia" to "anime/My-Hero-Academia-%E5%83%95%E3%81%AE%E3%83%92%E3%83%BC%E3%83%AD%E3%83%BC%E3%82%A2%E3%82%AB%E3%83%87%E3%83%9F%E3%82%A2/",
        "Game of Thrones" to "tv/Game-of-Thrones/",
        "Supernatural" to "tv/Supernatural/",
        "Doctor Who" to "tv/Doctor-Who/",
        "Bleach" to "anime/Bleach/",
        "One Piece" to "anime/One-Piece/",
        "Dragon Ball Z" to "anime/Dragon-Ball-Z/"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val censorId = mainCategory ?: "10"
        val sortId = orderBy ?: "1"

        val url = if (tag.isNullOrBlank()) {
            // Just In feed
            "$mainUrl/j/0/0/$censorId/0/0/$sortId/0/0/$page/"
        } else {
            // Fandom browse
            "$mainUrl/$tag?srt=$sortId&r=$censorId&p=$page"
        }

        val document = app.get(url).document

        // Use just "div.z-list" to match all variants (with or without .zhover.zpointer)
        val returnValue = document.select("div.z-list").mapNotNull { div ->
            val a = div.selectFirst("a.stitle") ?: return@mapNotNull null
            val title = a.text()
            val storyUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

            newSearchResponse(name = title, url = storyUrl) {
                // Get image - check for lazy loading or direct src
                val img = div.selectFirst("img")
                val imgSrc = img?.attr("data-original")?.ifEmpty { null }
                    ?: img?.attr("src")

                // Filter out placeholder images
                posterUrl = imgSrc?.let {
                    if (it.contains("/static/images/d_60_90.jpg")) null
                    else fixUrlNull(it)
                }

                // Extract latest chapter from metadata
                val gray = div.selectFirst("div.z-padtop2.xgray")?.text()
                if (gray != null && gray.contains("Chapters:")) {
                    val chapterNum = "Chapters: (\\d+)".toRegex().find(gray)?.groupValues?.get(1)
                    if (chapterNum != null) {
                        latestChapter = "Chapter $chapterNum"
                    }
                }
            }
        }

        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // URL encode the search query
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/?keywords=$encodedQuery&ready=1&type=story"
        val document = app.get(url).document

        // Search results use "div.z-list" without .zhover.zpointer
        return document.select("div.z-list").mapNotNull { div ->
            val a = div.selectFirst("a.stitle") ?: return@mapNotNull null
            val title = a.text()
            val storyUrl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

            newSearchResponse(name = title, url = storyUrl) {
                // Get image - check for lazy loading or direct src
                val img = div.selectFirst("img")
                val imgSrc = img?.attr("data-original")?.ifEmpty { null }
                    ?: img?.attr("src")

                // Filter out placeholder images
                posterUrl = imgSrc?.let {
                    if (it.contains("/static/images/d_60_90.jpg")) null
                    else fixUrlNull(it)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div#profile_top b.xcontrast_txt")?.text() ?: return null
        val author = document.selectFirst("div#profile_top a.xcontrast_txt[href^=/u/]")?.text()
        val synopsis = document.selectFirst("div#profile_top div.xcontrast_txt")?.text()

        val metaText = document.selectFirst("div#profile_top span.xgray.xcontrast_txt")?.text() ?: ""

        val tagsList = mutableListOf<String>()
        var ratingValue: Int? = null
        var peopleVotedValue: Int? = null

        // Parse metadata
        val metaParts = metaText.split(" - ")

        // Extract rating
        val ratingText = metaParts.firstOrNull()?.substringAfter("Rated:")?.trim()
        if (!ratingText.isNullOrBlank()) {
            tagsList.add("Rated: $ratingText")
        }

        // Extract genres
        if (metaParts.size > 2) {
            val possibleGenres = metaParts[2]
            if (!possibleGenres.contains(":") && !possibleGenres.startsWith("Chapters")) {
                tagsList.addAll(possibleGenres.split("/").map { it.trim() })
            }
        }

        // Extract characters
        val characterMatch = metaText.substringAfter("Characters:", "")
            .substringBefore(" - ", "")
            .trim()
        if (characterMatch.isNotEmpty()) {
            characterMatch.split(",").take(4).forEach {
                tagsList.add(it.trim().removeSurrounding("[", "]"))
            }
        }

        // Extract reviews count
        val reviewsMatch = "Reviews: ([\\d,]+)".toRegex().find(metaText)
        if (reviewsMatch != null) {
            peopleVotedValue = reviewsMatch.groupValues[1].replace(",", "").toIntOrNull()
        }

        // Calculate rating from favs + follows
        val favsMatch = "Favs: ([\\d,]+)".toRegex().find(metaText)
        val followsMatch = "Follows: ([\\d,]+)".toRegex().find(metaText)
        if (favsMatch != null && followsMatch != null) {
            val favs = favsMatch.groupValues[1].replace(",", "").toIntOrNull() ?: 0
            val follows = followsMatch.groupValues[1].replace(",", "").toIntOrNull() ?: 0
            ratingValue = ((favs + follows) / 10).coerceIn(0, 1000)
        }

        val isComplete = metaText.contains("Complete", ignoreCase = true)

        // Extract cover image
        val coverUrl = document.selectFirst("img[src*=/image/]")?.attr("src")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div#profile_top img")?.attr("data-original")?.let { fixUrlNull(it) }
            ?: document.selectFirst("div#profile_top img")?.attr("src")?.let { fixUrlNull(it) }

        // Extract story ID
        val storyId = url.substringAfter("/s/").substringBefore("/")

        // Get chapters - FIXED: Only select from the FIRST dropdown to avoid duplicates
        val chapters = document.selectFirst("select#chap_select")?.select("option")?.mapNotNull { opt ->
            val cName = opt.text().trim()
            val chapterIndex = opt.attr("value")
            if (chapterIndex.isEmpty()) return@mapNotNull null

            val chapterUrl = "$mainUrl/s/$storyId/$chapterIndex/"
            newChapterData(name = cName, url = chapterUrl)
        } ?: listOf(newChapterData(name = "Chapter 1", url = url)) // One-shot

        return newStreamResponse(name = title, url = url, data = chapters) {
            this.author = author
            this.synopsis = synopsis
            this.tags = tagsList
            this.posterUrl = coverUrl

            if (ratingValue != null) {
                this.rating = ratingValue
            }
            if (peopleVotedValue != null) {
                this.peopleVoted = peopleVotedValue
            }

            setStatus(if (isComplete) "Complete" else "Ongoing")
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        return document.selectFirst("div#storytext, div.storytext")?.html()
    }
}