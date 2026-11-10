package com.fuwanime.ui.manga

import com.fuwanime.data.manga.isKomikuId
import com.fuwanime.data.manga.isShinigamiId
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.fuwanime.R
import com.fuwanime.data.local.SettingsStore
import com.fuwanime.data.manga.Manga
import com.fuwanime.data.manga.MangaChapter
import com.fuwanime.data.manga.MangaDownloadRepository
import com.fuwanime.data.manga.MangaStatus
import com.fuwanime.data.local.MangaDownloadState
import com.fuwanime.data.local.ReaderMode
import com.fuwanime.data.manga.mergeChapters
import com.fuwanime.data.manga.readerLanguage
import com.fuwanime.data.manga.withChapter
import com.fuwanime.data.manga.MangaLibrary
import com.fuwanime.data.manga.MangaRepository
import com.fuwanime.data.manga.nextChapter
import com.fuwanime.data.manga.previousChapter
import com.fuwanime.ui.components.ErrorState
import com.fuwanime.util.UiText
import com.fuwanime.util.friendlyText
import com.fuwanime.util.fuwaViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The chapter to read. A saved chapter can drop out of the picked list (another group's upload won, or the app
 * language changed); its pages still load by id, so read it as an unlisted chapter labelled from history.
 */
fun readerChapter(chapters: List<MangaChapter>, chapterId: String, savedLabel: String?): MangaChapter =
    chapters.firstOrNull { it.id == chapterId }
        ?: MangaChapter(chapterId, number = null, title = savedLabel, language = "", group = null, pages = 0, externalUrl = null, publishAt = 0)

/** First page to show: the saved one, clamped to the chapter; 0 without history. */
fun readerStartIndex(savedPage: Int?, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (savedPage ?: 0).coerceIn(0, pageCount - 1)

data class ReaderState(
    val manga: Manga? = null,
    val chapters: List<MangaChapter> = emptyList(),
    val chapter: MangaChapter? = null,
    val pages: List<String>? = null,
    val startPage: Int = 0,
    val error: UiText? = null,
    /** The chapter last asked for, so a retry reopens it even before it has loaded. */
    val requestedId: String = "",
    /** Bumped when page URLs are refetched, so failed images retry with the new server. */
    val generation: Int = 0,
)

class MangaReaderViewModel(
    private val mangaId: String,
    firstChapterId: String,
    private val repo: MangaRepository,
    private val library: MangaLibrary,
    private val settings: SettingsStore,
    private val downloads: MangaDownloadRepository,
    /** Outlives the screen, so the save on exit isn't cancelled with viewModelScope. */
    private val appScope: kotlinx.coroutines.CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val readerMode: StateFlow<ReaderMode> = library.preference(mangaId).map { pref ->
        pref?.readerMode?.let { raw -> ReaderMode.entries.firstOrNull { it.name == raw } } ?: ReaderMode.WEBTOON
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderMode.WEBTOON)

    init { open(firstChapterId) }

    fun open(chapterId: String) {
        _state.value = _state.value.copy(chapter = null, pages = null, error = null, requestedId = chapterId)
        viewModelScope.launch {
            try {
                val dataSaver = settings.mangaDataSaver.first()
                // Pages don't depend on the chapter list; fetch them while the sources are merged.
                // runCatching: a failed async child would otherwise cancel this coroutine and crash past the catch below.
                // A downloaded chapter reads its saved images, no network.
                val pagesJob = async { runCatching { downloads.localPages(chapterId) ?: repo.pages(chapterId, dataSaver) } }
                val (manga, chapters) = try {
                    val manga = _state.value.manga ?: repo.detail(mangaId)
                    // Same merged list as the detail page (cached there), kept to the language of the opened chapter.
                    manga to _state.value.chapters.ifEmpty {
                        val all = repo.mergedChapters(manga)
                        val merged = readerLanguage(all, chapterId, repo.language())?.let { mergeChapters(all, it) } ?: all
                        all.firstOrNull { it.id == chapterId }?.let { withChapter(merged, it) } ?: merged
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Offline: a downloaded chapter still opens, with Prev/Next among the downloaded chapters.
                    val row = downloads.row(chapterId)?.takeIf { it.state == MangaDownloadState.DONE.name } ?: throw e
                    (_state.value.manga ?: Manga(row.mangaId, row.mangaTitle, row.coverUrl, "", emptyList(), MangaStatus.UNKNOWN, null, null)) to
                        downloads.offline(mangaId)
                }
                val saved = library.historyFor(mangaId).first().firstOrNull { it.chapterId == chapterId }
                val chapter = readerChapter(chapters, chapterId, saved?.chapterLabel)
                val pages = pagesJob.await().getOrThrow()
                _state.value = ReaderState(manga, chapters, chapter, pages, readerStartIndex(saved?.page, pages.size), requestedId = chapterId, generation = _state.value.generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.friendlyText())
            }
        }
    }

    /** MangaDex@Home URLs expire; a failed image asks for a fresh server. */
    fun refreshPages() {
        val chapter = _state.value.chapter ?: return
        viewModelScope.launch {
            runCatching { repo.pages(chapter.id, settings.mangaDataSaver.first(), forceRefresh = true) }
                .onSuccess { pages -> _state.value = _state.value.copy(pages = pages, generation = _state.value.generation + 1) }
        }
    }

    /** Takes the chapter explicitly: on a chapter switch the old one is saved after the state has moved on. */
    fun saveProgress(chapter: MangaChapter, page: Int, pageCount: Int, chapterLabel: String) {
        val manga = _state.value.manga ?: return
        appScope.launch { library.saveProgress(manga, chapter, chapterLabel, page, pageCount) }
    }

    fun setReaderMode(mode: ReaderMode) = viewModelScope.launch {
        library.updatePreference(mangaId) { it.copy(readerMode = mode.name) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(FlowPreview::class)
@Composable
fun MangaReaderRoute(mangaId: String, chapterId: String, onBack: () -> Unit) {
    val vm: MangaReaderViewModel = fuwaViewModel(key = "reader-$mangaId-$chapterId") { c, _ ->
        MangaReaderViewModel(mangaId, chapterId, c.mangaRepository, c.mangaLibrary, c.settingsStore, c.mangaDownloads, c.appScope)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val readerMode by vm.readerMode.collectAsStateWithLifecycle()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Immersive reading: system bars follow the reader's own bars.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, barsVisible) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsVisible) controller?.show(WindowInsetsCompat.Type.systemBars()) else controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val chapter = state.chapter
        val pages = state.pages
        when {
            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorState(state.error!!, onRetry = { vm.open(state.requestedId.ifEmpty { chapterId }) })
            }
            chapter == null || pages == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            else -> key(chapter.id) {
                val label = chapterLabel(chapter)
                // A source change can shrink the page list; never open past its last page.
                val startPage = state.startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                // From the start, show the credit above page 1; when resuming, jump straight to the saved page.
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (startPage == 0) 0 else startPage + 1)
                val pagerState = rememberPagerState(initialPage = startPage) { pages.size }
                // External (official-site) chapters can't be shown here, so prev/next skip them.
                val readable = remember(state.chapters) { state.chapters.filter { it.externalUrl == null && it.pages > 0 } }
                val next = nextChapter(readable, chapter.id)
                val previous = previousChapter(readable, chapter.id)
                val currentPage by remember(readerMode, pages.size) {
                    derivedStateOf {
                        if (readerMode == ReaderMode.WEBTOON) {
                            (listState.firstVisibleItemIndex - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                        } else pagerState.currentPage
                    }
                }
                // Switching mode keeps the reader on the same page instead of jumping back to the start.
                var lastPage by remember { mutableIntStateOf(startPage) }
                var modeInitialized by remember { mutableStateOf(false) }
                LaunchedEffect(readerMode) {
                    // The first composition already starts at the saved page; only a real mode
                    // change needs to carry the page across layouts.
                    if (modeInitialized) {
                        if (readerMode == ReaderMode.WEBTOON) listState.scrollToItem(lastPage + 1)
                        else pagerState.scrollToPage(lastPage)
                    }
                    modeInitialized = true
                    snapshotFlow {
                        if (readerMode == ReaderMode.WEBTOON) {
                            (listState.firstVisibleItemIndex - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                        } else pagerState.currentPage
                    }.drop(1).collect { lastPage = it }
                }
                LaunchedEffect(chapter.id, readerMode) {
                    snapshotFlow { currentPage }.distinctUntilChanged().debounce(1_000).collect { vm.saveProgress(chapter, it, pages.size, label) }
                }
                // The debounce may not fire when the reader closes quickly, so save the latest page on exit.
                val latestPage by rememberUpdatedState(currentPage)
                DisposableEffect(Unit) { onDispose { vm.saveProgress(chapter, latestPage, pages.size, label) } }

                if (readerMode == ReaderMode.WEBTOON) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { barsVisible = !barsVisible },
                    ) {
                        item(key = "credit-top") { Credit(chapter, Modifier.statusBarsPadding().padding(top = 56.dp, bottom = 8.dp)) }
                        itemsIndexed(pages, key = { i, _ -> "${chapter.id}-$i" }) { i, url -> Page(url, i, state.generation, vm::refreshPages) }
                        item(key = "end") { EndCard(label, chapter, next, onNext = { next?.let { vm.open(it.id) } }) }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        reverseLayout = readerMode == ReaderMode.RIGHT_TO_LEFT,
                        modifier = Modifier.fillMaxSize(),
                        key = { "${chapter.id}-$it" },
                    ) { page ->
                        Box(
                            Modifier.fillMaxSize().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { barsVisible = !barsVisible },
                            contentAlignment = Alignment.Center,
                        ) { ReaderPage(pages[page], page, state.generation, vm::refreshPages) }
                    }
                }

                AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    Row(
                        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).statusBarsPadding().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mine_back), tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(state.manga?.title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Chapter picker: the current chapter with a drop-down arrow, opening the full list.
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = stringResource(R.string.manga_reader_pick)) { pickerOpen = true }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                Icon(Icons.Rounded.ArrowDropDown, null, tint = Color.White)
                            }
                        }
                        IconButton(onClick = { previous?.let { vm.open(it.id) } }, enabled = previous != null) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.manga_reader_prev), tint = if (previous != null) Color.White else Color.White.copy(alpha = 0.3f))
                        }
                        IconButton(onClick = { next?.let { vm.open(it.id) } }, enabled = next != null) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.manga_reader_next), tint = if (next != null) Color.White else Color.White.copy(alpha = 0.3f))
                        }
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Rounded.Settings, stringResource(R.string.ux_reader_settings), tint = Color.White)
                        }
                    }
                }
                if (pickerOpen) ChapterPicker(readable, chapter.id, onPick = { pickerOpen = false; if (it != chapter.id) vm.open(it) }, onDismiss = { pickerOpen = false })
                AnimatedVisibility(barsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.75f)).navigationBarsPadding().padding(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { previous?.let { vm.open(it.id) } }, enabled = previous != null) {
                            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.manga_reader_prev), tint = if (previous != null) Color.White else Color.White.copy(alpha = 0.3f))
                        }
                        Text(
                            stringResource(R.string.manga_reader_page, currentPage + 1, pages.size),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { next?.let { vm.open(it.id) } }, enabled = next != null) {
                            Icon(Icons.Rounded.SkipNext, stringResource(R.string.manga_reader_next), tint = if (next != null) Color.White else Color.White.copy(alpha = 0.3f))
                        }
                    }
                    if (pages.size > 1) {
                        Slider(
                            value = currentPage.toFloat(),
                            onValueChange = { page ->
                                scope.launch {
                                    if (readerMode == ReaderMode.WEBTOON) listState.scrollToItem(page.toInt() + 1)
                                    else pagerState.scrollToPage(page.toInt())
                                }
                            },
                            valueRange = 0f..(pages.size - 1).toFloat(),
                            steps = (pages.size - 2).coerceAtLeast(0),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    }
                }
                if (settingsOpen) ReaderSettingsSheet(readerMode, vm::setReaderMode) { settingsOpen = false }
            }
        }
        // Loading and error states have no reader bar; keep a way out.
        if (state.error != null || state.chapter == null || state.pages == null) {
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mine_back), tint = Color.White)
            }
        }
    }
}

/** Every readable chapter in a sheet, opened at the current one (ticked); a tap jumps there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterPicker(chapters: List<MangaChapter>, currentId: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val at = chapters.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (at - 3).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.manga_reader_pick),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
            items(chapters, key = { it.id }) { c ->
                val current = c.id == currentId
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        listOfNotNull(chapterLabel(c), c.title?.takeIf { it.isNotBlank() && c.number != null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (current) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Credit(chapter: MangaChapter, modifier: Modifier = Modifier) {
    Text(
        when {
            isShinigamiId(chapter.id) -> stringResource(R.string.manga_reader_source_shinigami)
            isKomikuId(chapter.id) -> stringResource(R.string.manga_reader_source_komiku)
            chapter.group != null -> stringResource(R.string.manga_reader_scanlation, chapter.group)
            else -> stringResource(R.string.manga_reader_scanlation_unknown)
        },
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun Page(url: String, index: Int, generation: Int, onFailed: () -> Unit) {
    var status by remember(url, generation) { mutableStateOf<AsyncImagePainter.State?>(null) }
    val description = stringResource(R.string.manga_reader_page_cd, index + 1)
    Box(
        Modifier.fillMaxWidth().then(if (status is AsyncImagePainter.State.Success) Modifier else Modifier.aspectRatio(1f / 1.4f)),
        contentAlignment = Alignment.Center,
    ) {
        // A new generation recreates the request, so "Muat ulang" retries even when the refetched URL is identical.
        key(generation) { AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = ContentScale.FillWidth,
            onState = { status = it },
            modifier = Modifier.fillMaxWidth(),
        ) }
        when (status) {
            is AsyncImagePainter.State.Error -> TextButton(onClick = onFailed, modifier = Modifier.semantics { contentDescription = description }) {
                Icon(Icons.Rounded.Refresh, null, tint = Color.White)
                Text(stringResource(R.string.manga_reader_reload), color = Color.White, modifier = Modifier.padding(start = 6.dp))
            }
            is AsyncImagePainter.State.Success -> Unit
            else -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ReaderPage(url: String, index: Int, generation: Int, onFailed: () -> Unit) {
    var status by remember(url, generation) { mutableStateOf<AsyncImagePainter.State?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(generation) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.manga_reader_page_cd, index + 1),
                contentScale = ContentScale.Fit,
                onState = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
        when (status) {
            is AsyncImagePainter.State.Error -> TextButton(onClick = onFailed) { Text(stringResource(R.string.manga_reader_reload), color = Color.White) }
            is AsyncImagePainter.State.Success -> Unit
            else -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(selected: ReaderMode, onSelect: (ReaderMode) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.ux_reader_mode), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
        ReaderMode.entries.forEach { mode ->
            val label = when (mode) {
                ReaderMode.WEBTOON -> R.string.ux_reader_webtoon
                ReaderMode.SINGLE_PAGE -> R.string.ux_reader_single
                ReaderMode.RIGHT_TO_LEFT -> R.string.ux_reader_rtl
            }
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(mode); onDismiss() }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                if (mode == selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EndCard(label: String, chapter: MangaChapter, next: MangaChapter?, onNext: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.manga_reader_end, label), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Credit(chapter)
        if (next != null) {
            Button(onClick = onNext) { Text(stringResource(R.string.manga_reader_next)) }
        } else {
            Text(stringResource(R.string.manga_reader_last), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
