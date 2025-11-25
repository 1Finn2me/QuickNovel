package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.toRate
import com.lagradost.quicknovel.util.AppUtils.parseJson
import android.util.Log

class WtrLabProvider : MainAPI() {
    override val name = "WTR-LAB"
    override val mainUrl = "https://wtr-lab.com"
    override val lang = "en"
    override val hasMainPage = true
    override val hasReviews = false
    override val usesCloudFlareKiller = false

    override val iconId = R.drawable.icon_wtrlab

    companion object {
        private const val TAG = "WtrLabProvider"
        private const val CHAPTERS_PER_REQUEST = 500L
    }

    override val tags = listOf(
        Pair("All", ""),
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Fantasy", "fantasy"),
        Pair("Harem", "harem"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Mature", "mature"),
        Pair("Mecha", "mecha"),
        Pair("Mystery", "mystery"),
        Pair("Psychological", "psychological"),
        Pair("Romance", "romance"),
        Pair("School Life", "school-life"),
        Pair("Sci-fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Supernatural", "supernatural"),
        Pair("Tragedy", "tragedy"),
        Pair("Wuxia", "wuxia"),
        Pair("Xianxia", "xianxia"),
        Pair("Xuanhuan", "xuanhuan"),
    )

    override val orderBys = listOf(
        Pair("Popular", "popular"),
        Pair("Latest", "latest"),
        Pair("Rating", "rating"),
        Pair("Views", "views"),
        Pair("New", "new"),
    )

    /**
     * Fix image URL - handle relative paths and missing protocols
     */
    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val tagPath = if (!tag.isNullOrBlank()) "/tag/$tag" else ""
        val order = orderBy ?: "popular"
        val url = "$mainUrl/en/novel-list$tagPath?sort=$order&page=$page"

        Log.d(TAG, "Loading main page: $url")
        val doc = app.get(url).document

        val novels = doc.select(".series-list > div > div > .serie-item").mapNotNull { element ->
            val titleWrap = element.selectFirst(".title-wrap") ?: return@mapNotNull null
            val titleHolder = titleWrap.selectFirst("a.title") ?: return@mapNotNull null
            val href = titleHolder.attr("href")
            if (href.isBlank()) return@mapNotNull null

            // Remove raw title element to get clean title
            titleHolder.selectFirst(".rawtitle")?.remove()
            val name = titleHolder.text().trim()
            if (name.isBlank()) return@mapNotNull null

            // Try multiple selectors for image
            val imgElement = element.selectFirst(".img-wrap img")
                ?: element.selectFirst(".image-wrap img")
                ?: element.selectFirst("a img")
                ?: element.selectFirst("img")

            // Try different attributes for image URL
            val rawPosterUrl = imgElement?.attr("data-src")
                ?: imgElement?.attr("src")
                ?: imgElement?.attr("data-lazy-src")

            val posterUrl = fixImageUrl(rawPosterUrl)

            Log.d(TAG, "Novel: $name, Image: $posterUrl")

            // Try to get rating
            val ratingText = element.selectFirst(".rating-text")?.text()
            val rating = ratingText?.toFloatOrNull()?.times(20)?.toInt()

            // Try to get latest chapter info
            val latestChapter = element.selectFirst(".chapter-info")?.text()

            val fixedUrl = fixUrlNull(href) ?: return@mapNotNull null

            SearchResponse(
                name = name,
                url = if (fixedUrl.startsWith("http")) fixedUrl else "$mainUrl$fixedUrl",
                posterUrl = posterUrl,
                rating = rating,
                latestChapter = latestChapter,
                apiName = this.name
            )
        }

        Log.d(TAG, "Found ${novels.size} novels on main page")
        return HeadMainPageResponse(url, novels)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/novel-finder?text=${query.replace(" ", "+")}"
        Log.d(TAG, "Searching: $url")
        val doc = app.get(url).document

        return doc.select(".series-list > div > div > .serie-item").mapNotNull { element ->
            val titleWrap = element.selectFirst(".title-wrap") ?: return@mapNotNull null
            val titleHolder = titleWrap.selectFirst("a.title") ?: return@mapNotNull null
            val href = titleHolder.attr("href")
            if (href.isBlank()) return@mapNotNull null

            // Remove raw title element to get clean title
            titleHolder.selectFirst(".rawtitle")?.remove()
            val name = titleHolder.text().trim()
            if (name.isBlank()) return@mapNotNull null

            // Try multiple selectors for image
            val imgElement = element.selectFirst(".img-wrap img")
                ?: element.selectFirst(".image-wrap img")
                ?: element.selectFirst("a img")
                ?: element.selectFirst("img")

            val rawPosterUrl = imgElement?.attr("data-src")
                ?: imgElement?.attr("src")
                ?: imgElement?.attr("data-lazy-src")

            val fixedUrl = if (href.startsWith("http")) href else "$mainUrl$href"

            newSearchResponse(name, fixedUrl) {
                posterUrl = fixImageUrl(rawPosterUrl)
                rating = element.selectFirst(".rating-text")?.text()?.toRate(5)
            }
        }
    }

    private suspend fun getChapterRange(
        url: String,
        rawId: Long,
        start: Long,
        end: Long
    ): List<ChapterData> {
        val chapterDataUrl = "$mainUrl/api/chapters/$rawId?start=$start&end=$end"
        Log.d(TAG, "Fetching chapters: $chapterDataUrl")

        return try {
            val response = app.get(chapterDataUrl).text
            Log.d(TAG, "Chapters response length: ${response.length}")

            val chaptersData = parseJson<ChaptersApiResponse>(response)
            Log.d(TAG, "Parsed ${chaptersData.chapters.size} chapters")

            chaptersData.chapters.map { chapter ->
                // Build chapter URL - use the novel URL + /chapter-{order}
                val chapterUrl = "${url.trimEnd('/')}/chapter-${chapter.order}"
                Log.d(TAG, "Chapter ${chapter.order}: $chapterUrl")

                newChapterData(
                    name = "#${chapter.order} ${chapter.title}",
                    url = chapterUrl
                ) {
                    dateOfRelease = chapter.updatedAt
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching chapters: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "Loading novel: $url")
        val doc = app.get(url).document

        // Get title - try multiple selectors
        val title = doc.selectFirst(".title-wrap .text-uppercase")?.text()?.trim()
            ?: doc.selectFirst("h1.title")?.text()?.trim()
            ?: doc.selectFirst(".novel-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Could not find title")

        Log.d(TAG, "Novel title: $title")

        // Parse JSON data from Next.js
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
            ?: throw ErrorLoadingException("Could not find page data")
        val json = jsonNode.data()
        if (json.isBlank()) throw ErrorLoadingException("Empty page data")

        Log.d(TAG, "JSON data length: ${json.length}")

        val pageData = try {
            parseJson<SeriePageResponse>(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing page JSON: ${e.message}", e)
            throw ErrorLoadingException("Failed to parse page data: ${e.message}")
        }

        val serieData = pageData.props.pageProps.serie.serieData
        Log.d(TAG, "Raw ID: ${serieData.rawId}, Total chapters: ${serieData.rawChapterCount}")

        // Get all chapters
        val totalChapters = serieData.rawChapterCount
        val chapters = mutableListOf<ChapterData>()

        if (totalChapters <= CHAPTERS_PER_REQUEST) {
            chapters.addAll(getChapterRange(url, serieData.rawId, 1, totalChapters))
        } else {
            var start = 1L
            while (start <= totalChapters) {
                val end = minOf(start + CHAPTERS_PER_REQUEST - 1, totalChapters)
                chapters.addAll(getChapterRange(url, serieData.rawId, start, end))
                start = end + 1
            }
        }

        Log.d(TAG, "Total chapters loaded: ${chapters.size}")

        // Extract poster - try multiple selectors and attributes
        val imgElement = doc.selectFirst(".image-wrap img")
            ?: doc.selectFirst(".novel-cover img")
            ?: doc.selectFirst(".cover img")
            ?: doc.selectFirst("img.cover")

        val rawPosterUrl = imgElement?.attr("data-src")
            ?: imgElement?.attr("src")
            ?: imgElement?.attr("data-lazy-src")

        val posterUrl = fixImageUrl(rawPosterUrl)
        Log.d(TAG, "Poster URL: $posterUrl")

        // Extract metadata
        val synopsis = doc.selectFirst(".desc-wrap")?.text()?.trim()
            ?: doc.selectFirst(".description")?.text()?.trim()
            ?: doc.selectFirst(".synopsis")?.text()?.trim()

        val rating = doc.selectFirst(".rating-text")?.text()?.toRate(5)

        // Extract author
        val author = doc.select(".detail-line, .info-line, .meta-item").find { line ->
            line.text().contains("Author", ignoreCase = true)
        }?.selectFirst("a, span:last-child")?.text()?.trim()

        // Extract views
        val views = doc.select(".detail-line, .info-line, .meta-item").find { line ->
            line.text().contains("Views", ignoreCase = true)
        }?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // Extract status
        val statusText = doc.select(".detail-line, .info-line, .meta-item").find { line ->
            line.text().contains("Status", ignoreCase = true)
        }?.selectFirst("span, a")?.text()?.trim()

        // Extract genres/tags
        val genres = doc.select(".genre-wrap a, .tag-wrap a, .genres a, .tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return newStreamResponse(title, url, chapters) {
            this.synopsis = synopsis
            this.posterUrl = posterUrl
            this.rating = rating
            this.author = author
            this.views = views
            this.tags = genres
            setStatus(statusText)
        }
    }

    override suspend fun loadHtml(url: String): String? {
        Log.d(TAG, "Loading chapter HTML: $url")

        try {
            val doc = app.get(url).document

            // Parse JSON data from Next.js
            val jsonNode = doc.selectFirst("#__NEXT_DATA__")
            if (jsonNode == null) {
                Log.e(TAG, "Could not find #__NEXT_DATA__ on chapter page")
                // Fallback: try to get content directly from page
                val directContent = doc.selectFirst(".chapter-body")?.html()
                    ?: doc.selectFirst(".chapter-content")?.html()
                    ?: doc.selectFirst(".content")?.html()

                if (!directContent.isNullOrBlank()) {
                    Log.d(TAG, "Using direct content fallback")
                    return directContent
                }
                throw ErrorLoadingException("Could not find chapter data")
            }

            val json = jsonNode.data()
            if (json.isBlank()) {
                Log.e(TAG, "Empty JSON data on chapter page")
                throw ErrorLoadingException("Empty chapter data")
            }

            Log.d(TAG, "Chapter JSON length: ${json.length}")

            val chapterData = try {
                parseJson<ChapterPageResponse>(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing chapter JSON: ${e.message}")
                Log.d(TAG, "JSON preview: ${json.take(500)}")
                throw ErrorLoadingException("Failed to parse chapter data: ${e.message}")
            }

            val chapter = chapterData.props.pageProps.serie.chapter
            Log.d(TAG, "Chapter slug: ${chapter.slug}, rawId: ${chapter.rawId}")

            if (chapter.rawId == 0L || chapter.slug.isBlank()) {
                Log.e(TAG, "Invalid chapter data - rawId: ${chapter.rawId}, slug: ${chapter.slug}")
                throw ErrorLoadingException("Invalid chapter data")
            }

            // Fetch translated content via API
            Log.d(TAG, "Fetching translation from API")
            val response = app.post(
                url = "$mainUrl/api/reader/get",
                data = mapOf(
                    "chapter_no" to chapter.slug,
                    "language" to "en",
                    "raw_id" to chapter.rawId.toString(),
                    "retry" to "false",
                    "translate" to "web"
                )
            )

            val responseText = response.text
            Log.d(TAG, "API response length: ${responseText.length}")

            if (responseText.isBlank()) {
                Log.e(TAG, "Empty API response")
                throw ErrorLoadingException("Empty translation response")
            }

            val contentData = try {
                parseJson<ReaderApiResponse>(responseText)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing API response: ${e.message}")
                Log.d(TAG, "Response preview: ${responseText.take(500)}")
                throw ErrorLoadingException("Failed to parse translation: ${e.message}")
            }

            val bodyParagraphs = contentData.data.data.body
            Log.d(TAG, "Body paragraphs count: ${bodyParagraphs.size}")

            if (bodyParagraphs.isEmpty()) {
                Log.e(TAG, "No paragraphs in response")

                // Try alternative: maybe the translation isn't ready, try AI translation
                Log.d(TAG, "Trying AI translation as fallback")
                val aiResponse = app.post(
                    url = "$mainUrl/api/reader/get",
                    data = mapOf(
                        "chapter_no" to chapter.slug,
                        "language" to "en",
                        "raw_id" to chapter.rawId.toString(),
                        "retry" to "false",
                        "translate" to "ai"
                    )
                )

                val aiContentData = try {
                    parseJson<ReaderApiResponse>(aiResponse.text)
                } catch (e: Exception) {
                    null
                }

                val aiBody = aiContentData?.data?.data?.body
                if (!aiBody.isNullOrEmpty()) {
                    Log.d(TAG, "AI translation paragraphs: ${aiBody.size}")
                    return buildString {
                        for (paragraph in aiBody) {
                            if (!paragraph.contains("window._taboola") &&
                                !paragraph.contains("<script") &&
                                !paragraph.contains("googletag")
                            ) {
                                append("<p>")
                                append(paragraph)
                                append("</p>")
                            }
                        }
                    }.ifBlank { null }
                }

                return null
            }

            // Build HTML content, filtering out ad scripts
            return buildString {
                for (paragraph in bodyParagraphs) {
                    // Skip ad/tracking scripts
                    if (paragraph.contains("window._taboola") ||
                        paragraph.contains("<script") ||
                        paragraph.contains("googletag")
                    ) {
                        continue
                    }

                    append("<p>")
                    append(paragraph)
                    append("</p>")
                }
            }.ifBlank { null }

        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in loadHtml: ${e.message}", e)
            throw ErrorLoadingException("Failed to load chapter: ${e.message}")
        }
    }
}

// ============ API Response Data Classes ============

// Chapters API Response
data class ChaptersApiResponse(
    val chapters: List<ChapterItem> = emptyList()
)

data class ChapterItem(
    @JsonProperty("serie_id")
    val serieId: Long = 0,
    val id: Long = 0,
    val order: Long = 0,
    val title: String = "",
    val name: String = "",
    @JsonProperty("updated_at")
    val updatedAt: String = ""
)

// Serie Page Response (for load())
data class SeriePageResponse(
    val props: SeriePageProps = SeriePageProps()
)

data class SeriePageProps(
    val pageProps: SeriePagePropsInner = SeriePagePropsInner()
)

data class SeriePagePropsInner(
    val serie: SerieWrapper = SerieWrapper()
)

data class SerieWrapper(
    @JsonProperty("serie_data")
    val serieData: SerieData = SerieData()
)

data class SerieData(
    @JsonProperty("raw_id")
    val rawId: Long = 0,
    @JsonProperty("raw_chapter_count")
    val rawChapterCount: Long = 0
)

// Chapter Page Response (for loadHtml())
data class ChapterPageResponse(
    val props: ChapterPageProps = ChapterPageProps()
)

data class ChapterPageProps(
    val pageProps: ChapterPagePropsInner = ChapterPagePropsInner()
)

data class ChapterPagePropsInner(
    val serie: ChapterSerieWrapper = ChapterSerieWrapper()
)

data class ChapterSerieWrapper(
    val chapter: ChapterInfo = ChapterInfo()
)

data class ChapterInfo(
    val id: Long = 0,
    val slug: String = "",
    @JsonProperty("raw_id")
    val rawId: Long = 0,
    val order: Long = 0,
    val title: String = "",
    val name: String = ""
)

// Reader API Response
data class ReaderApiResponse(
    val data: ReaderDataWrapper = ReaderDataWrapper()
)

data class ReaderDataWrapper(
    val data: ReaderContent = ReaderContent()
)

data class ReaderContent(
    val body: List<String> = emptyList(),
    val title: String = ""
)