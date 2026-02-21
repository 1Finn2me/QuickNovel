package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class NovelFireProvider : MainAPI() {
    override val name = "NovelFire"
    override val mainUrl = "https://novelfire.net"
    override val rateLimitTime = 300L
    override val hasMainPage = true

    override val iconId = R.drawable.big_icon_novelfire
    override val iconBackgroundId = R.color.novelFireColor

    override val hasReviews = true

    // Thread-safe caches
    private val commentCursors = ConcurrentHashMap<String, String?>()
    private val postIdCache = ConcurrentHashMap<String, String>()

    // Parallel loading settings
    private val batchSize = 5
    private val maxRetries = 3
    private val retryDelayMs = 3500L

    override val orderBys = listOf(
        "Rank (Top)" to "rank-top",
        "Rating Score (Top)" to "rating-score-top",
        "Review Count (Most)" to "review",
        "Comment Count (Most)" to "comment",
        "Bookmark Count (Most)" to "bookmark",
        "Today Views (Most)" to "today-view",
        "Monthly Views (Most)" to "monthly-view",
        "Total Views (Most)" to "total-view",
        "Title (A>Z)" to "abc",
        "Title (Z>A)" to "cba",
        "Last Updated (Newest)" to "date",
        "Chapter Count (Most)" to "chapter-count-most"
    )

    override val tags = listOf(
        "All" to "",
        "Action" to "3",
        "Adult" to "28",
        "Adventure" to "4",
        "Anime" to "46",
        "Arts" to "47",
        "Comedy" to "5",
        "Drama" to "24",
        "Eastern" to "44",
        "Ecchi" to "26",
        "Fan-fiction" to "48",
        "Fantasy" to "6",
        "Game" to "19",
        "Gender Bender" to "25",
        "Harem" to "7",
        "Historical" to "12",
        "Horror" to "37",
        "Isekai" to "49",
        "Josei" to "2",
        "Lgbt+" to "45",
        "Magic" to "50",
        "Magical Realism" to "51",
        "Manhua" to "52",
        "Martial Arts" to "15",
        "Mature" to "8",
        "Mecha" to "34",
        "Military" to "53",
        "Modern Life" to "54",
        "Movies" to "55",
        "Mystery" to "16",
        "Other" to "64",
        "Psychological" to "9",
        "Realistic Fiction" to "56",
        "Reincarnation" to "43",
        "Romance" to "1",
        "School Life" to "21",
        "Sci-fi" to "20",
        "Seinen" to "10",
        "Shoujo" to "38",
        "Shoujo Ai" to "57",
        "Shounen" to "17",
        "Shounen Ai" to "39",
        "Slice of Life" to "13",
        "Smut" to "29",
        "Sports" to "42",
        "Supernatural" to "18",
        "System" to "58",
        "Tragedy" to "32",
        "Urban" to "63",
        "Urban Life" to "59",
        "Video Games" to "60",
        "War" to "61",
        "Wuxia" to "31",
        "Xianxia" to "23",
        "Xuanhuan" to "22",
        "Yaoi" to "14",
        "Yuri" to "62"
    )

    // ================================================================
    // UTILITY METHODS
    // ================================================================

    private fun deSlash(url: String): String {
        return if (url.startsWith("/")) url.substring(1) else url
    }

    private fun fixPosterUrl(imgElement: Element?): String? {
        if (imgElement == null) return null
        val rawSrc = imgElement.attr("data-src").ifBlank { imgElement.attr("src") }
        if (rawSrc.isBlank() || rawSrc.contains("data:image/gif")) return null
        val cleanedSrc = deSlash(rawSrc)
        return if (cleanedSrc.startsWith("http")) cleanedSrc else "$mainUrl/$cleanedSrc"
    }

    private fun extractNovelSlug(url: String): String {
        return url.replace(mainUrl, "")
            .removePrefix("/")
            .removePrefix("book/")
            .removeSuffix("/")
            .split("/")
            .firstOrNull() ?: url
    }

    private fun extractPostId(document: Document): String? {
        return document.selectFirst("#novel-report[report-post_id]")
            ?.attr("report-post_id")
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseViewCount(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.replace(Regex("[^0-9.KMBkmb]"), "").trim().uppercase()
        return when {
            cleaned.endsWith("K") -> cleaned.dropLast(1).toFloatOrNull()?.times(1_000)?.toInt()
            cleaned.endsWith("M") -> cleaned.dropLast(1).toFloatOrNull()?.times(1_000_000)?.toInt()
            cleaned.endsWith("B") -> cleaned.dropLast(1).toFloatOrNull()?.times(1_000_000_000)?.toInt()
            else -> cleaned.replace(".", "").toIntOrNull()
        }
    }

    // ================================================================
    // MAIN PAGE
    // ================================================================

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val params = mutableListOf<String>()
        if (!tag.isNullOrEmpty()) params.add("categories[]=$tag")
        params.add("ctgcon=and")
        params.add("totalchapter=0")
        params.add("ratcon=min")
        params.add("rating=0")
        params.add("status=-1")
        params.add("sort=${orderBy.takeUnless { it.isNullOrEmpty() } ?: "rank-top"}")
        params.add("page=$page")

        val url = "$mainUrl/search-adv?${params.joinToString("&")}"
        val response = app.get(url)
        val document = response.document

        val novels = document.select(".novel-item").mapNotNull { element ->
            parseNovelElement(element)
        }

        return HeadMainPageResponse(url, novels)
    }

    private fun parseNovelElement(element: Element): SearchResponse? {
        val titleElement = element.selectFirst(".novel-title > a")
            ?: element.selectFirst("a[title]")
            ?: return null

        val name = titleElement.attr("title").ifBlank { titleElement.text() }.trim()
        if (name.isBlank()) return null

        val href = titleElement.attr("href")
        val novelUrl = deSlash(href.replace(mainUrl, "").removePrefix("/"))
        val posterUrl = fixPosterUrl(element.selectFirst(".novel-cover > img") ?: element.selectFirst("img"))

        return newSearchResponse(name = name, url = novelUrl) {
            this.posterUrl = posterUrl
        }
    }

    // ================================================================
    // SEARCH
    // ================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&page=1"
        val document = app.get(url).document

        return document.select(".novel-list.chapters .novel-item").mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val name = linkElement.attr("title").ifBlank { linkElement.text() }.trim()
            if (name.isBlank()) return@mapNotNull null

            val novelUrl = deSlash(linkElement.attr("href").replace(mainUrl, "").removePrefix("/"))
            val posterUrl = fixPosterUrl(element.selectFirst(".novel-cover > img") ?: element.selectFirst("img"))

            newSearchResponse(name = name, url = novelUrl) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ================================================================
    // LOAD NOVEL DETAILS
    // ================================================================

    override suspend fun load(url: String): LoadResponse {
        val novelPath = deSlash(url.replace(mainUrl, "").removePrefix("/"))
        val novelSlug = extractNovelSlug(novelPath)
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl/$novelPath"

        val response = app.get(fullUrl)
        val document = response.document

        val name = document.selectFirst(".novel-title")?.text()?.trim()
            ?: document.selectFirst(".cover > img")?.attr("alt")
            ?: throw ErrorLoadingException("Name not found for '$url'")

        val postId = extractPostId(document)
        if (postId != null) {
            postIdCache[novelSlug] = postId
        }

        // Get total chapter count for fallback pagination
        val chapterCountText = document.selectFirst(".header-stats .icon-book-open")
            ?.parent()?.text()?.trim()
            ?.replace(Regex("[^0-9]"), "")
        val totalChapters = chapterCountText?.toIntOrNull() ?: 0
        val totalPages = (totalChapters + 99) / 100 // Ceiling division

        // Load chapters - AJAX first, then parallel HTML fallback
        val chapters = loadChaptersOptimized(novelSlug, postId, totalPages)

        val data = chapters.map { chapter ->
            newChapterData(name = chapter.name, url = chapter.url) {
                dateOfRelease = chapter.dateOfRelease
            }
        }

        // Load related novels in background (don't block)
        val relatedNovels = try {
            loadRelatedNovels(postId)
        } catch (e: Exception) {
            null
        }

        return newStreamResponse(url = fullUrl, name = name, data = data) {
            related = relatedNovels

            val statusText = document.selectFirst(".header-stats .ongoing")?.text()
                ?: document.selectFirst(".header-stats .completed")?.text()
            statusText?.let { setStatus(it) }

            views = document.selectFirst(".header-stats span:has(i.icon-eye) strong")?.text()?.let {
                parseViewCount(it)
            }

            posterUrl = document.selectFirst(".cover > img")?.let { imgElement ->
                val src = imgElement.attr("data-src").ifBlank { imgElement.attr("src") }
                if (src.isNotBlank() && !src.contains("data:image")) {
                    if (src.startsWith("http")) src else "$mainUrl/${deSlash(src)}"
                } else null
            }

            synopsis = document.selectFirst(".summary .content")?.let { element ->
                element.text().replace("Show More", "").trim().takeIf { it.isNotBlank() }
            } ?: "No Summary Found"

            author = document.selectFirst(".author .property-item > span")?.text()?.trim()

            tags = document.select(".categories .property-item")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            rating = document.selectFirst(".nub")?.text()?.toFloatOrNull()?.let {
                (it * 200).toInt()
            }
        }
    }

    // ================================================================
    // OPTIMIZED CHAPTER LOADING
    // ================================================================

    private data class ChapterInfo(
        val name: String,
        val url: String,
        val dateOfRelease: String? = null,
        val chapterNumber: Int = 0
    )

    /**
     * Optimized chapter loading:
     * 1. Try AJAX endpoint first (single request for ALL chapters)
     * 2. If AJAX fails, use parallel HTML loading with batching
     */
    private suspend fun loadChaptersOptimized(
        novelSlug: String,
        postId: String?,
        totalPages: Int
    ): List<ChapterInfo> {
        // Try AJAX first - this returns ALL chapters in one request
        if (!postId.isNullOrBlank()) {
            try {
                val chapters = loadChaptersViaAjax(novelSlug, postId)
                if (chapters.isNotEmpty()) {
                    return chapters
                }
            } catch (e: RateLimitException) {
                // Wait and retry once
                delay(retryDelayMs)
                try {
                    val chapters = loadChaptersViaAjax(novelSlug, postId)
                    if (chapters.isNotEmpty()) {
                        return chapters
                    }
                } catch (e2: Exception) {
                    logError(e2)
                }
            } catch (e: AjaxNotFoundException) {
                // Fall through to HTML parsing
            } catch (e: Exception) {
                logError(e)
            }
        }

        // Fallback: Load chapters from HTML pages in PARALLEL
        return if (totalPages > 0) {
            loadChaptersFromHtmlParallel(novelSlug, totalPages)
        } else {
            // If we don't know total pages, load sequentially until no more
            loadChaptersFromHtmlSequential(novelSlug)
        }
    }

    /**
     * Load ALL chapters via single AJAX request
     */
    private suspend fun loadChaptersViaAjax(novelSlug: String, postId: String): List<ChapterInfo> {
        val ajaxUrl = "$mainUrl/listChapterDataAjax?post_id=$postId"
        val response = app.get(ajaxUrl)
        val responseText = response.text

        if (responseText.contains("You are being rate limited")) {
            throw RateLimitException()
        }

        if (responseText.contains("Page Not Found 404")) {
            throw AjaxNotFoundException()
        }

        val json = JSONObject(responseText)
        val dataArray = json.optJSONArray("data") ?: return emptyList()

        val chapters = mutableListOf<ChapterInfo>()

        for (i in 0 until dataArray.length()) {
            val chapterObj = dataArray.getJSONObject(i)
            val nSort = chapterObj.optInt("n_sort", i + 1)
            val title = chapterObj.optString("title", "")
            val slug = chapterObj.optString("slug", "")
            val createdAt = chapterObj.optString("created_at", null)

            val chapterName = when {
                title.isNotBlank() -> Jsoup.parse(title).text()
                slug.isNotBlank() -> Jsoup.parse(slug).text()
                else -> "Chapter $nSort"
            }

            chapters.add(ChapterInfo(
                name = chapterName,
                url = "book/$novelSlug/chapter-$nSort",
                dateOfRelease = createdAt,
                chapterNumber = nSort
            ))
        }

        return chapters.sortedBy { it.chapterNumber }
    }

    /**
     * Load chapters from HTML pages in PARALLEL batches
     * This is much faster than sequential loading
     */
    private suspend fun loadChaptersFromHtmlParallel(
        novelSlug: String,
        totalPages: Int
    ): List<ChapterInfo> = coroutineScope {
        val allChapters = mutableListOf<ChapterInfo>()
        val seenUrls = ConcurrentHashMap.newKeySet<String>()

        // Process pages in batches
        for (batchStart in 1..totalPages step batchSize) {
            val batchEnd = minOf(batchStart + batchSize - 1, totalPages)
            val pageRange = (batchStart..batchEnd).toList()

            var retryCount = 0
            var success = false

            while (!success && retryCount < maxRetries) {
                try {
                    // Load all pages in this batch in PARALLEL
                    val results = pageRange.map { page ->
                        async {
                            loadSingleChapterPage(novelSlug, page)
                        }
                    }.awaitAll()

                    // Collect results
                    results.flatten().forEach { chapter ->
                        if (seenUrls.add(chapter.url)) {
                            allChapters.add(chapter)
                        }
                    }
                    success = true

                } catch (e: RateLimitException) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        delay(retryDelayMs)
                    } else {
                        throw e
                    }
                }
            }
        }

        allChapters.sortedBy { it.chapterNumber }
    }

    /**
     * Load a single chapter page (used in parallel loading)
     */
    private suspend fun loadSingleChapterPage(novelSlug: String, page: Int): List<ChapterInfo> {
        val chaptersUrl = "$mainUrl/book/$novelSlug/chapters?page=$page"
        val response = app.get(chaptersUrl)
        val responseText = response.text

        if (responseText.contains("You are being rate limited")) {
            throw RateLimitException()
        }

        val document = Jsoup.parse(responseText)
        val chapterItems = document.select("ul.chapter-list li")

        return chapterItems.mapNotNull { item ->
            val linkElement = item.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")

            if (!href.contains("/chapter-")) return@mapNotNull null

            val chapterTitle = linkElement.attr("title").ifBlank {
                item.selectFirst("strong.chapter-title")?.text()?.trim()
                    ?: linkElement.text().trim()
            }

            val timeElement = item.selectFirst("time.chapter-update")
            val dateOfRelease = timeElement?.text()?.trim()

            val chapterUrl = deSlash(href.removePrefix(mainUrl).removePrefix("/"))

            // Extract chapter number from URL
            val chapterNumber = Regex("chapter-(\\d+)").find(chapterUrl)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            ChapterInfo(
                name = chapterTitle,
                url = chapterUrl,
                dateOfRelease = dateOfRelease,
                chapterNumber = chapterNumber
            )
        }
    }

    /**
     * Fallback: Sequential loading when we don't know total pages
     */
    private suspend fun loadChaptersFromHtmlSequential(novelSlug: String): List<ChapterInfo> {
        val allChapters = mutableListOf<ChapterInfo>()
        val seenUrls = mutableSetOf<String>()
        var currentPage = 1
        var hasMorePages = true
        val maxPages = 100

        while (hasMorePages && currentPage <= maxPages) {
            try {
                val chapters = loadSingleChapterPage(novelSlug, currentPage)

                if (chapters.isEmpty()) {
                    hasMorePages = false
                    continue
                }

                chapters.forEach { chapter ->
                    if (seenUrls.add(chapter.url)) {
                        allChapters.add(chapter)
                    }
                }

                // Check if we got less than expected (last page)
                if (chapters.size < 100) {
                    hasMorePages = false
                }

                currentPage++

            } catch (e: RateLimitException) {
                delay(retryDelayMs)
                // Retry same page
            } catch (e: Exception) {
                logError(e)
                hasMorePages = false
            }
        }

        return allChapters.sortedBy { it.chapterNumber }
    }

    // Custom exceptions for better control flow
    private class RateLimitException : Exception("NovelFire is rate limiting requests")
    private class AjaxNotFoundException : Exception("AJAX endpoint not found")

    // ================================================================
    // RELATED NOVELS
    // ================================================================

    private suspend fun loadRelatedNovels(postId: String?): List<SearchResponse>? {
        if (postId.isNullOrBlank()) return null

        return try {
            val url = "$mainUrl/ajax/novelYouMayLike?post_id=$postId"
            val response = app.get(url)
            val json = JSONObject(response.text)
            val html = json.optString("html", "")

            if (html.isBlank()) return null

            val document = Jsoup.parse(html)
            document.select("li.novel-item").mapNotNull { item ->
                val linkElement = item.selectFirst("a") ?: return@mapNotNull null
                val title = item.selectFirst("h5.novel-title")?.text()?.trim() ?: return@mapNotNull null
                val posterUrl = fixPosterUrl(item.selectFirst("figure.novel-cover img"))
                val novelUrl = deSlash(linkElement.attr("href").removePrefix(mainUrl).removePrefix("/"))

                newSearchResponse(name = title, url = novelUrl) {
                    this.posterUrl = posterUrl
                }
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // ================================================================
    // LOAD CHAPTER CONTENT
    // ================================================================

    override suspend fun loadHtml(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl/$url"
        val response = app.get(fullUrl)
        val document = response.document

        val contentElement = document.selectFirst("#content")
            ?: document.selectFirst(".chapter-content")
            ?: return null

        // Remove obfuscation tags (like LNReader does)
        contentElement.select(":not(p, h1, h2, h3, h4, h5, h6, span, i, b, u, em, strong, img, a, div, br, hr)")
            .forEach { ele ->
                val tagName = ele.tagName()
                // NovelFire uses tags starting with "nf" that are longer than 5 chars
                if (tagName.length > 5 && tagName.startsWith("nf")) {
                    ele.remove()
                }
            }

        // Remove ads
        contentElement.select(
            ".ads, .adsbygoogle, script, style, .ads-holder, .ads-middle, " +
                    "[id*='ads'], [class*='ads'], .hidden, " +
                    "[style*='display:none'], [style*='display: none']"
        ).remove()

        return contentElement.html()
            .replace("&nbsp;", " ")
            .trim()
    }

    // ================================================================
    // REVIEWS / COMMENTS (unchanged)
    // ================================================================

    override suspend fun loadReviews(
        url: String,
        page: Int,
        showSpoilers: Boolean
    ): List<UserReview> {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl/$url"
        val novelSlug = extractNovelSlug(url)

        val postId = if (page == 1) {
            val response = app.get(fullUrl)
            val id = extractPostId(response.document)
            if (id != null) postIdCache[novelSlug] = id
            id
        } else {
            postIdCache[novelSlug]
        }

        if (postId.isNullOrBlank()) return emptyList()

        val cursor = if (page == 1) {
            commentCursors.remove(postId)
            ""
        } else {
            commentCursors[postId] ?: return emptyList()
        }

        val commentUrl = buildString {
            append("$mainUrl/comment/show?post_id=$postId&chapter_id=&order_by=newest")
            if (cursor.isNotEmpty()) append("&cursor=$cursor")
        }

        return try {
            val response = app.get(commentUrl)
            val json = JSONObject(response.text)
            parseCommentsFromJson(json, postId, showSpoilers)
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private fun parseCommentsFromJson(
        json: JSONObject,
        postId: String,
        showSpoilers: Boolean
    ): List<UserReview> {
        val html = json.optString("html", "")
        if (html.isBlank()) return emptyList()

        val nextCursor = json.optString("next_cursor", null)
        if (!nextCursor.isNullOrBlank()) {
            commentCursors[postId] = nextCursor
        } else {
            commentCursors.remove(postId)
        }

        val document = Jsoup.parse(html)
        return document.select("li > div.comment-item").mapNotNull { commentEl ->
            parseCommentElement(commentEl, showSpoilers)
        }
    }

    private fun parseCommentElement(element: Element, showSpoilers: Boolean): UserReview? {
        val header = element.selectFirst("div.comment-header") ?: return null
        val body = element.selectFirst("div.comment-body") ?: return null

        val username = header.selectFirst("span.username")?.text()?.trim()
        var avatarUrl = (header.selectFirst("div.user-avatar img.avatar")
            ?: header.selectFirst("img.avatar"))?.attr("src")

        if (avatarUrl != null) {
            avatarUrl = when {
                avatarUrl.contains("default-avatar") || avatarUrl.contains("data:image") -> null
                !avatarUrl.startsWith("http") -> "https://images.novelfire.net$avatarUrl"
                else -> avatarUrl
            }
        }

        val time = header.selectFirst("span.post-date")?.text()?.trim()
        val commentTextElement = body.selectFirst("div.comment-text")

        val isSpoiler = commentTextElement?.attr("data-spoiler") == "1"
        if (!showSpoilers && isSpoiler) {
            commentTextElement?.html("<em>[Spoiler content hidden]</em>")
        }

        val commentContent = commentTextElement?.html()?.takeIf { it.isNotBlank() } ?: return null

        val parentUsername = header.selectFirst("div.parent-link a")?.text()?.trim()
        val reviewTitle = parentUsername?.let { "Reply to $it" }

        return UserReview(
            commentContent,
            reviewTitle,
            username,
            time,
            fixUrlNull(avatarUrl),
            null,
            null
        )
    }
}