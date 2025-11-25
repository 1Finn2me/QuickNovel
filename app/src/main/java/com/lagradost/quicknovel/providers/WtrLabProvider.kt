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

class WtrLabProvider : MainAPI() {
    override val name = "WTR-LAB"
    override val mainUrl = "https://wtr-lab.com"
    override val lang = "en"
    override val hasMainPage = true
    override val hasReviews = false
    override val usesCloudFlareKiller = false


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

    companion object {
        private const val CHAPTERS_PER_REQUEST = 500L
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

            val posterUrl = element.selectFirst("a > img")?.attr("src")

            // Try to get rating
            val ratingText = element.selectFirst(".rating-text")?.text()
            val rating = ratingText?.toFloatOrNull()?.times(20)?.toInt()

            // Try to get latest chapter info
            val latestChapter = element.selectFirst(".chapter-info")?.text()

            SearchResponse(
                name = name,
                url = fixUrlNull(href) ?: return@mapNotNull null,
                posterUrl = posterUrl,
                rating = rating,
                latestChapter = latestChapter,
                apiName = this.name
            )
        }

        return HeadMainPageResponse(url, novels)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/novel-finder?text=${query.replace(" ", "+")}"
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

            newSearchResponse(name, href) {
                posterUrl = element.selectFirst("a > img")?.attr("src")
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

        return try {
            val response = app.get(chapterDataUrl).text
            val chaptersData = parseJson<ChaptersApiResponse>(response)

            chaptersData.chapters.map { chapter ->
                newChapterData(
                    name = "#${chapter.order} ${chapter.title}",
                    url = "${url.trimEnd('/')}/chapter-${chapter.order}"
                ) {
                    dateOfRelease = chapter.updatedAt
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Get title
        val titleWrap = doc.selectFirst(".title-wrap")
            ?: throw ErrorLoadingException("Could not find title wrapper")
        val title = titleWrap.selectFirst(".text-uppercase")?.text()?.trim()
            ?: throw ErrorLoadingException("Could not find title")

        // Parse JSON data from Next.js
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
            ?: throw ErrorLoadingException("Could not find page data")
        val json = jsonNode.data()
        if (json.isBlank()) throw ErrorLoadingException("Empty page data")

        val pageData = parseJson<SeriePageResponse>(json)
        val serieData = pageData.props.pageProps.serie.serieData

        // Get all chapters (handle pagination for large chapter counts)
        val totalChapters = serieData.rawChapterCount
        val chapters = mutableListOf<ChapterData>()

        if (totalChapters <= CHAPTERS_PER_REQUEST) {
            // Single request for small chapter counts
            chapters.addAll(getChapterRange(url, serieData.rawId, 1, totalChapters))
        } else {
            // Multiple requests for large chapter counts
            var start = 1L
            while (start <= totalChapters) {
                val end = minOf(start + CHAPTERS_PER_REQUEST - 1, totalChapters)
                chapters.addAll(getChapterRange(url, serieData.rawId, start, end))
                start = end + 1
            }
        }

        // Extract metadata
        val synopsis = doc.selectFirst(".desc-wrap")?.text()?.trim()
        val posterUrl = doc.selectFirst(".image-wrap > img")?.attr("src")
        val rating = doc.selectFirst(".rating-text")?.text()?.toRate(5)

        // Extract author from detail lines
        val author = doc.select(".detail-line").find { line ->
            line.text().contains("Author", ignoreCase = true)
        }?.selectFirst("a")?.text()?.trim()

        // Extract views
        val views = doc.select(".detail-line").find { line ->
            line.text().contains("Views", ignoreCase = true)
        }?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // Extract status
        val statusText = doc.select(".detail-line").find { line ->
            line.text().contains("Status", ignoreCase = true)
        }?.selectFirst("span")?.text()?.trim()

        // Extract genres/tags
        val genres = doc.select(".genre-wrap a, .tag-wrap a").map { it.text().trim() }
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
        val doc = app.get(url).document

        // Parse JSON data from Next.js
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
            ?: throw ErrorLoadingException("Could not find chapter data")
        val json = jsonNode.data()
        if (json.isBlank()) throw ErrorLoadingException("Empty chapter data")

        val chapterData = parseJson<ChapterPageResponse>(json)
        val chapter = chapterData.props.pageProps.serie.chapter

        // Fetch translated content via API
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

        val contentData = response.parsed<ReaderApiResponse>()
        val bodyParagraphs = contentData.data.data.body

        if (bodyParagraphs.isEmpty()) {
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
    val rawId: Long = 0
)

// Reader API Response
data class ReaderApiResponse(
    val data: ReaderDataWrapper = ReaderDataWrapper()
)

data class ReaderDataWrapper(
    val data: ReaderContent = ReaderContent()
)

data class ReaderContent(
    val body: List<String> = emptyList()
)