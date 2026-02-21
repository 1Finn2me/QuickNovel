package com.lagradost.quicknovel.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.BookDownloader2Helper
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.ui.UiImage
import com.lagradost.quicknovel.ui.img

class DownloadFragment : Fragment() {
    private lateinit var viewModel: DownloadViewModel

    // Data classes for compatibility
    data class DownloadData(
        @JsonProperty("source") val source: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("author") val author: String?,
        @JsonProperty("posterUrl") val posterUrl: String?,
        @JsonProperty("rating") val rating: Int?,
        @JsonProperty("peopleVoted") val peopleVoted: Int?,
        @JsonProperty("views") val views: Int?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("tags") val tags: List<String>?,
        @JsonProperty("apiName") val apiName: String,
        @JsonProperty("lastUpdated") val lastUpdated: Long?,
        @JsonProperty("lastDownloaded") val lastDownloaded: Long?,
    )

    data class DownloadDataLoaded(
        val source: String,
        val name: String,
        val author: String?,
        val posterUrl: String?,
        //RATING IS FROM 0-100
        val rating: Int?,
        val peopleVoted: Int?,
        val views: Int?,
        val synopsis: String?,
        val tags: List<String>?,
        val apiName: String,
        val downloadedCount: Long,
        val downloadedTotal: Long,
        val ETA: String,
        val state: DownloadState,
        val id: Int,
        val generating: Boolean,
        val lastUpdated: Long?,
        val lastDownloaded: Long?,
        val lastReadTimestamp: Long = 0L
    ) {
        val image: UiImage? by lazy {
            if (isImported) {
                val bitmap = BookDownloader2Helper.getCachedBitmap(activity, apiName, author, name)
                if (bitmap != null) {
                    return@lazy UiImage.Bitmap(bitmap)
                }
            }
            img(posterUrl)
        }

        val isImported: Boolean get() = (apiName == IMPORT_SOURCE || apiName == IMPORT_SOURCE_PDF)

        // FIX: Include UI-relevant fields in equals/hashCode for proper Compose recomposition
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DownloadDataLoaded) return false
            return id == other.id &&
                    downloadedCount == other.downloadedCount &&
                    downloadedTotal == other.downloadedTotal &&
                    state == other.state &&
                    generating == other.generating &&
                    lastReadTimestamp == other.lastReadTimestamp &&
                    ETA == other.ETA &&
                    name == other.name &&
                    posterUrl == other.posterUrl
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + downloadedCount.hashCode()
            result = 31 * result + downloadedTotal.hashCode()
            result = 31 * result + state.hashCode()
            result = 31 * result + generating.hashCode()
            result = 31 * result + lastReadTimestamp.hashCode()
            return result
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        viewModel = ViewModelProvider(activity ?: this)[DownloadViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DownloadScreen(viewModel = viewModel)
            }
        }
    }
}