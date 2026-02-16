package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class WuxiaWorldProvider : MainAPI() {
    override val name = "WuxiaWorld"
    override val mainUrl = "https://www.wuxiaworld.com"
    override val hasMainPage = true

    // Add icon to drawable resources
    // override val iconId = R.drawable.icon_wuxiaworld

    override val tags = listOf(
        "All" to "",
        "Action" to "Action",
        "Adventure" to "Adventure",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
        "Fantasy" to "Fantasy",
        "Martial Arts" to "Martial Arts",
        "Mystery" to "Mystery",
        "Romance" to "Romance",
        "Sci-fi" to "Sci-fi",
        "Slice of Life" to "Slice of Life",
        "Supernatural" to "Supernatural",
        "Tragedy" to "Tragedy",
        "Xianxia" to "Xianxia",
        "Xuanhuan" to "Xuanhuan",
    )

    override val orderBys = listOf(
        "All" to "",
        "Popular" to "popular",
        "New" to "new",
        "Completed" to "completed",
    )

    /**
     * Parse novel list from JSON API response
     */
    private fun parseNovelList(items: JSONArray): List<SearchResponse> {
        val novels = mutableListOf<SearchResponse>()
        for (i in 0 until items.length()) {
            try {
                val novel = items.getJSONObject(i)
                val name = novel.optString("name", "")
                val slug = novel.optString("slug", "")
                val coverUrl = novel.optString("coverUrl", null)

                if (name.isNotEmpty() && slug.isNotEmpty()) {
                    novels.add(
                        SearchResponse(
                            name = name,
                            url = "$mainUrl/novel/$slug",
                            posterUrl = coverUrl,
                            rating = null,
                            latestChapter = null,
                            apiName = this.name
                        )
                    )
                }
            } catch (e: Exception) {
                continue
            }
        }
        return novels
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = "$mainUrl/api/novels"
        val response = app.get(url)

        return try {
            val json = JSONObject(response.text)
            val items = json.getJSONArray("items")
            HeadMainPageResponse(url, parseNovelList(items))
        } catch (e: Exception) {
            HeadMainPageResponse(url, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/novels/search?query=$encodedQuery"
        val response = app.get(url)

        return try {
            val json = JSONObject(response.text)
            val items = json.getJSONArray("items")
            parseNovelList(items)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Extract slug from URL
        val slug = url.substringAfter("/novel/").substringBefore("/").trim()

        if (slug.isEmpty()) {
            throw ErrorLoadingException("Invalid novel URL")
        }

        // Try to get novel info from API
        val apiUrl = "$mainUrl/api/novels/$slug"
        val apiResponse = try {
            val response = app.get(apiUrl)
            JSONObject(response.text)
        } catch (e: Exception) {
            null
        }

        val name: String
        val posterUrl: String?
        val synopsis: String?
        val author: String?
        val genres: List<String>
        val statusText: String?

        if (apiResponse != null) {
            // Parse from API response
            name = apiResponse.optString("name", "Unknown")
            posterUrl = apiResponse.optString("coverUrl", null)
            synopsis = buildString {
                val desc = apiResponse.optString("description", "")
                val syn = apiResponse.optString("synopsis", "")
                if (desc.isNotEmpty()) append(desc)
                if (desc.isNotEmpty() && syn.isNotEmpty()) append("\n\n")
                if (syn.isNotEmpty()) append(syn)
            }.takeIf { it.isNotEmpty() }
            author = apiResponse.optString("authorName", null)
            genres = try {
                val genresArray = apiResponse.optJSONArray("genres")
                if (genresArray != null) {
                    (0 until genresArray.length()).map { genresArray.getString(it) }
                } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            statusText = when (apiResponse.optInt("status", -1)) {
                0 -> "Completed"
                1 -> "Ongoing"
                2 -> "Hiatus"
                else -> null
            }
        } else {
            // Fallback to HTML scraping
            val document = app.get(url).document
            name = document.selectFirst("h1")?.text()
                ?: document.selectFirst("h1.novel-title")?.text()
                        ?: throw ErrorLoadingException("Could not find novel name")
            posterUrl = document.selectFirst("img[src*=cover]")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            synopsis = document.selectFirst("div.novel-summary")?.text()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            author = document.selectFirst("span.author")?.text()
            genres = document.select("a[href*=genre]").map { it.text() }
            statusText = document.selectFirst("span.status")?.text()
        }

        // Get chapters - try API first
        val chaptersApiUrl = "$mainUrl/api/novels/$slug/chapters"
        val chapters = try {
            val chaptersResponse = app.get(chaptersApiUrl)
            val chaptersJson = JSONObject(chaptersResponse.text)
            val items = chaptersJson.optJSONArray("items") ?: JSONArray()

            val chapterList = mutableListOf<Pair<String, String>>()
            for (i in 0 until items.length()) {
                val chapterGroup = items.getJSONObject(i)
                val chapterArray = chapterGroup.optJSONArray("chapterList") ?: continue

                for (j in 0 until chapterArray.length()) {
                    val chapter = chapterArray.getJSONObject(j)
                    val chapterName = chapter.optString("name", "Chapter ${j + 1}")
                    val chapterSlug = chapter.optString("slug", "")

                    // Check if chapter is locked
                    val isLocked = chapter.optJSONObject("relatedUserInfo")
                        ?.optJSONObject("isChapterUnlocked")
                        ?.optBoolean("value", true) == false

                    val displayName = if (isLocked) "$chapterName 🔒" else chapterName

                    if (chapterSlug.isNotEmpty()) {
                        chapterList.add(displayName to "$mainUrl/novel/$slug/$chapterSlug")
                    }
                }
            }
            chapterList.map { (cName, cUrl) -> newChapterData(cName, cUrl) }
        } catch (e: Exception) {
            // Fallback to HTML scraping for chapters
            val document = app.get(url).document
            document.select("a[href*=/novel/$slug/]").mapNotNull { element ->
                val chapterUrl = element.attr("href")
                val chapterName = element.text()
                if (chapterUrl.contains(slug) && chapterName.isNotEmpty() &&
                    !chapterUrl.endsWith("/$slug") && !chapterUrl.endsWith("/$slug/")) {
                    newChapterData(chapterName, fixUrlNull(chapterUrl) ?: return@mapNotNull null)
                } else null
            }.distinctBy { it.url }
        }

        return newStreamResponse(name, url, chapters) {
            this.posterUrl = posterUrl
            this.synopsis = synopsis
            this.author = author
            this.tags = genres
            setStatus(statusText)
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document

        // Try multiple selectors for chapter content
        val content = document.selectFirst("#chapter-content")
            ?: document.selectFirst(".chapter-content")
            ?: document.selectFirst("div.chapter-body")
            ?: document.selectFirst("div[class*=chapter]")

        if (content == null) {
            // WuxiaWorld may require JavaScript - content might be loaded dynamically
            // This is a limitation of HTML scraping
            return "<p>Unable to load chapter content. This chapter may require JavaScript or be premium/locked.</p>"
        }

        // Clean up the content
        content.select("script, .chapter-nav, .ads, .advertisement").remove()

        return content.html()
    }
}