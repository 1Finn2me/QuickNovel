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
import org.json.JSONObject
import org.jsoup.Jsoup
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

    private fun extractPostId(document: org.jsoup.nodes.Document): String? {
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

        // Load chapters using optimized method (check last chapter, generate rest)
        val chapters = getChapters(novelSlug)

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
    // OPTIMIZED CHAPTER LOADING - Check last chapter, generate rest
    // ================================================================

    private data class ChapterInfo(
        val name: String,
        val url: String,
        val dateOfRelease: String? = null
    )

    /**
     * Optimized chapter loading:
     * 1. Get first page to check for pagination
     * 2. If pagination exists, get the last page
     * 3. Get the last chapter link from the last page
     * 4. Extract total chapter number from the last chapter URL
     * 5. Generate all chapter URLs from 1 to totalChapters
     *
     * This only makes 2 requests instead of loading all pages
     */
    private suspend fun getChapters(novelSlug: String): List<ChapterInfo> {
        val firstPageUrl = "$mainUrl/book/$novelSlug/chapters?page=1"
        val document = app.get(firstPageUrl).document

        // Check for pagination
        val pagination = document.selectFirst("div.pagenav div.pagination-container nav ul.pagination")
        if (pagination != null) {
            val lastPageElement = pagination.select("li").let { it.getOrNull(it.size - 2) }
            val lastPageNumber = lastPageElement?.text()?.toIntOrNull() ?: 1

            // Get the last page to find the last chapter
            val lastPageUrl = "$mainUrl/book/$novelSlug/chapters?page=$lastPageNumber"
            val lastPageDoc = app.get(lastPageUrl).document
            val lastChapterLink = lastPageDoc.select("ul.chapter-list li a").last()?.attr("href") ?: ""
            val totalChapters = lastChapterLink.substringAfterLast("/chapter-").toIntOrNull()

            if (totalChapters != null) {
                // Generate all chapters from 1 to totalChapters
                return (1..totalChapters).map { chapterNumber ->
                    ChapterInfo(
                        name = "Chapter $chapterNumber",
                        url = "book/$novelSlug/chapter-$chapterNumber"
                    )
                }
            }
        }

        // Fallback: Parse chapters from the first page if no pagination
        return document.select("ul.chapter-list li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val name = a.selectFirst("span.chapter-title")?.text()
                ?: a.attr("title").ifBlank { a.text() }
            val url = deSlash(a.attr("href").removePrefix(mainUrl).removePrefix("/"))
            val date = li.selectFirst("span.chapter-update")?.text()
                ?: li.selectFirst("time.chapter-update")?.text()

            ChapterInfo(name, url, date)
        }
    }

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

        val title = document.selectFirst("span.chapter-title")
        val contentElement = document.selectFirst("#content")
            ?: document.selectFirst(".chapter-content")
            ?: return null

        // Remove the title if it's duplicated in content
        title?.let { titleElement ->
            contentElement.selectFirst("p")?.let { firstP ->
                if (firstP.text().replace(" ", "").equals(titleElement.text().replace(" ", ""), ignoreCase = true)) {
                    firstP.remove()
                }
            }
        }

        // Remove obfuscation tags
        contentElement.select(":not(p, h1, h2, h3, h4, h5, h6, span, i, b, u, em, strong, img, a, div, br, hr)")
            .forEach { ele ->
                val tagName = ele.tagName()
                if (tagName.length > 5 && tagName.startsWith("nf")) {
                    ele.remove()
                }
            }

        // Remove ads and blocker images
        contentElement.select(
            ".ads, .adsbygoogle, script, style, .ads-holder, .ads-middle, " +
                    "[id*='ads'], [class*='ads'], .hidden, " +
                    "[style*='display:none'], [style*='display: none'], " +
                    "img[src*=disable-blocker.jpg]"
        ).remove()

        val titleHtml = title?.outerHtml() ?: ""
        return (titleHtml + contentElement.html())
            .replace("&nbsp;", " ")
            .trim()
    }

    // ================================================================
    // REVIEWS / COMMENTS
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