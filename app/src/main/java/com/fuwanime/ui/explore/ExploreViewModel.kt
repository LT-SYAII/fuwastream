package com.fuwanime.ui.explore

import com.fuwanime.R
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuwanime.data.Anime
import com.fuwanime.data.AnimeRepository
import com.fuwanime.data.LibraryRepository
import com.fuwanime.data.SortOrder
import com.fuwanime.data.manga.Manga
import com.fuwanime.data.manga.MangaOrder
import com.fuwanime.data.manga.MangaRepository
import com.fuwanime.data.remote.AnimeApiService
import com.fuwanime.util.UiText
import com.fuwanime.util.friendlyText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StatusFilter(@StringRes val label: Int) {
    ALL(R.string.browse_all), ONGOING(R.string.common_status_airing), FINISHED(R.string.common_status_finished),
}

/** [apiType] is the API's `type` value; null means no type filter. */
enum class TypeFilter(@StringRes val label: Int, val apiType: String?) {
    ALL(R.string.browse_all, null),
    SERIES(R.string.browse_type_series, "SERIES"),
    MOVIE(R.string.browse_type_movie, "MOVIE"),
    LIVE_ACTION(R.string.browse_type_live_action, "LIVE ACTION"),
    ONA(R.string.browse_type_ona, "ONA"),
    OVA(R.string.browse_type_ova, "OVA"),
    SPECIAL(R.string.browse_type_special, "SPECIAL");

    fun matches(type: String): Boolean =
        apiType == null || type.trim().replace('-', ' ').equals(apiType, ignoreCase = true)
}

data class ExploreFilters(
    val query: String = "",
    /** Every selected genre must match (AND). */
    val genres: Set<String> = emptySet(),
    val status: StatusFilter = StatusFilter.ALL,
    val type: TypeFilter = TypeFilter.ALL,
    val sort: SortOrder = SortOrder.POPULAR,
) {
    val activeCount: Int get() = genres.size + listOfNotNull(
        status.takeIf { it != StatusFilter.ALL },
        type.takeIf { it != TypeFilter.ALL },
    ).size
}

data class ExploreUiState(
    val filters: ExploreFilters = ExploreFilters(),
    val results: List<Anime> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: UiText? = null,
    /** Universal search: manga hits shown next to the anime results. */
    val mangaResults: List<Manga> = emptyList(),
    val mangaLoading: Boolean = false,
)

class ExploreViewModel(
    private val repo: AnimeRepository,
    private val library: LibraryRepository,
    private val mangaRepository: MangaRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ExploreUiState(filters = ExploreFilters(genres = setOfNotNull(savedState.get<String>("genre")?.takeIf { it.isNotBlank() }))),
    )
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    val recentSearches: StateFlow<List<String>> =
        library.recentSearches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val raw = mutableListOf<Anime>()
    private val seen = HashSet<String>()
    private var nextPage = 0
    private var loadJob: Job? = null
    private var debounceJob: Job? = null
    private var mangaJob: Job? = null

    init {
        reload()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(filters = it.filters.copy(query = query)) }
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(450)
            reload()
        }
    }

    fun submitSearch(query: String = _state.value.filters.query) {
        debounceJob?.cancel()
        _state.update { it.copy(filters = it.filters.copy(query = query)) }
        viewModelScope.launch { library.addSearch(query) }
        reload()
    }

    fun removeRecent(query: String) = viewModelScope.launch { library.removeSearch(query) }
    fun clearRecent() = viewModelScope.launch { library.clearSearches() }

    fun toggleGenre(genre: String) = updateFilters { it.copy(genres = if (genre in it.genres) it.genres - genre else it.genres + genre) }
    fun setStatus(status: StatusFilter) = updateFilters { it.copy(status = status) }
    fun setType(type: TypeFilter) = updateFilters { it.copy(type = type) }
    fun setSort(sort: SortOrder) = updateFilters { it.copy(sort = sort) }
    fun clearFilters() = updateFilters { it.copy(genres = emptySet(), status = StatusFilter.ALL, type = TypeFilter.ALL) }

    private fun updateFilters(change: (ExploreFilters) -> ExploreFilters) {
        val before = _state.value.filters
        val after = change(before)
        if (after == before) return
        _state.update { it.copy(filters = after) }
        // Sort, query and type hit the server; genre/status only re-filter what we already have.
        if (after.sort != before.sort || after.query != before.query || after.type != before.type) reload()
        else refilter(fillPage = true)
    }

    fun reload() {
        loadJob?.cancel()
        raw.clear()
        seen.clear()
        nextPage = 0
        _state.update { it.copy(results = emptyList(), loading = true, endReached = false, error = null) }
        loadMore()
        searchManga(_state.value.filters.query)
    }

    /** Universal search: anime and manga in one query, without waiting for the anime pager to finish. */
    private fun searchManga(query: String) {
        mangaJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _state.update { it.copy(mangaResults = emptyList(), mangaLoading = false) }
            return
        }
        mangaJob = viewModelScope.launch {
            _state.update { it.copy(mangaLoading = true) }
            // runCatching would also swallow cancellation, letting a slow old query overwrite newer hits.
            val local = try {
                mangaRepository.searchIndonesia(q)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val dex = try {
                mangaRepository.browse(MangaOrder.RELEVANCE, q).items
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // Different sources give the same title different ids; keep the first (local) hit per title.
            val merged = (local + dex).distinctBy { it.id }.distinctBy { it.title.trim().lowercase() }.take(12)
            _state.update { it.copy(mangaResults = merged, mangaLoading = false) }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.endReached || (loadJob?.isActive == true)) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = !it.loading, error = null) }
            val startCount = _state.value.results.size
            // Genre/status filtering is client-side, so keep fetching until enough new matches show up.
            repeat(MAX_PAGES_PER_BATCH) {
                val f = _state.value.filters
                val page = repo.explore(nextPage, f.sort, f.query, f.type.apiType).getOrElse { e ->
                    _state.update { it.copy(loading = false, loadingMore = false, error = e.friendlyText()) }
                    return@launch
                }
                nextPage++
                page.forEach { if (seen.add(it.id)) raw += it }
                val end = page.size < AnimeApiService.EXPLORE_PAGE_SIZE
                if (end) _state.update { it.copy(endReached = true) }
                val matches = refilter(fillPage = false)
                if (end || matches - startCount >= MIN_BATCH) {
                    _state.update { it.copy(loading = false, loadingMore = false) }
                    return@launch
                }
            }
            _state.update { it.copy(loading = false, loadingMore = false) }
        }
    }

    private fun refilter(fillPage: Boolean): Int {
        val f = _state.value.filters
        val filtered = raw.filter { a ->
            matchesGenres(a, f.genres) &&
                when (f.status) {
                    StatusFilter.ALL -> true
                    StatusFilter.ONGOING -> a.isOngoing
                    StatusFilter.FINISHED -> !a.isOngoing && !a.isUpcoming
                } &&
                // Server-filtered pages already match; keyword searches are type-filtered here.
                f.type.matches(a.type)
        }
        _state.update { it.copy(results = filtered) }
        if (fillPage) {
            if (filtered.size < MIN_BATCH && !_state.value.endReached) loadMore()
        }
        return filtered.size
    }

    private companion object {
        const val MIN_BATCH = 18
        const val MAX_PAGES_PER_BATCH = 6
    }
}
