package com.pixiv.pixiv.advancesearch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.Locale

private val PidLightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9E6FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757780)
)

private val PidDarkColors = darkColorScheme(
    primary = Color(0xFF96CCFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF004A75),
    onPrimaryContainer = Color(0xFFC9E6FF),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDDE1F9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F909A)
)

private val PidShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

internal data class PidUiStrings(
    val title: String,
    val description: String,
    val hint: String,
    val search: String,
    val searching: String,
    val empty: String,
    val failed: String,
    val fallbackHint: String,
    val typeIllust: String,
    val typeNovel: String,
    val typeUser: String,
    val typeIllustSeries: String,
    val typeNovelSeries: String,
    val userStats: String,
    val premium: String,
    val itemCount: String
)

private class ModuleComposeContext(
    base: Context,
    private val moduleRes: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = moduleRes
    override fun getAssets() = moduleRes.assets
}

private class HostComposeOwner(private val activity: Activity) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity === this@HostComposeOwner.activity) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        override fun onActivityStarted(activity: Activity) {
            if (activity === this@HostComposeOwner.activity) {
                registry.currentState = Lifecycle.State.STARTED
            }
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity === this@HostComposeOwner.activity) {
                registry.currentState = Lifecycle.State.RESUMED
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity === this@HostComposeOwner.activity) {
                registry.currentState = Lifecycle.State.STARTED
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity === this@HostComposeOwner.activity) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (activity === this@HostComposeOwner.activity) {
                registry.currentState = Lifecycle.State.DESTROYED
                activity.application.unregisterActivityLifecycleCallbacks(this)
            }
        }
    }

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        activity.application.registerActivityLifecycleCallbacks(callbacks)
    }
}

internal fun createPidComposeView(
    activity: Activity,
    hostContext: Context,
    moduleResources: Resources,
    strings: PidUiStrings,
    onOpen: (PidTarget, Long, Long?) -> Unit,
    onInvalid: () -> Unit
): ComposeView {
    val moduleContext = ModuleComposeContext(hostContext, moduleResources)
    val owner = HostComposeOwner(activity)

    // Compose 1.13 resolves its window recomposer from the top of the host view
    // tree. Tag the host decor view with the module-owned lifecycle/savedstate
    // owners so the module classloader can find them.
    activity.window?.decorView?.let { root ->
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
    }

    android.util.Log.i(
        "PixivAdvanceSearch",
        "PID: ComposeView classloader=${ComposeView::class.java.classLoader}"
    )

    return ComposeView(moduleContext).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent {
            CompositionLocalProvider(
                LocalContext provides moduleContext,
                LocalResources provides moduleResources
            ) {
                PidComposeTheme {
                    PidScreen(
                        strings = strings,
                        searchContext = moduleContext,
                        onOpen = onOpen,
                        onInvalid = onInvalid
                    )
                }
            }
        }
    }
}

@Composable
private fun PidComposeTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) PidDarkColors else PidLightColors,
        motionScheme = MotionScheme.expressive(),
        typography = Typography(),
        shapes = PidShapes,
        content = content
    )
}

@Composable
private fun PidScreen(
    strings: PidUiStrings,
    searchContext: Context,
    onOpen: (PidTarget, Long, Long?) -> Unit,
    onInvalid: () -> Unit
) {
    var pid by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PidSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    fun search() {
        val value = pid.trim().toLongOrNull()
        if (value == null || value <= 0L) {
            onInvalid()
            return
        }
        if (loading) return
        loading = true
        searched = false
        error = null
        results = emptyList()
        Thread {
            val outcome = runCatching { PidSearchClient.search(searchContext, value) }
            handler.post {
                loading = false
                searched = true
                outcome.onSuccess { results = it }
                    .onFailure {
                        android.util.Log.e("PixivAdvanceSearch", "PID search failed", it)
                        error = it.message ?: it.javaClass.simpleName
                    }
            }
        }.start()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Text(
                text = strings.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = pid,
                onValueChange = { input -> pid = input.filter { it.isDigit() } },
                label = { Text(strings.hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { search() },
                enabled = !loading,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(strings.searching)
                } else {
                    Text(strings.search)
                }
            }
            Spacer(Modifier.height(20.dp))

            val currentError = error
            val pidValue = pid.trim().toLongOrNull()
            if (currentError != null) {
                Text(
                    text = String.format(Locale.getDefault(), strings.failed, currentError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings.fallbackHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (pidValue != null && pidValue > 0L) {
                    PidFallbackButtons(pidValue, strings, onOpen)
                }
            } else if (searched && !loading && results.isEmpty()) {
                Text(
                    text = strings.empty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings.fallbackHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (pidValue != null && pidValue > 0L) {
                    PidFallbackButtons(pidValue, strings, onOpen)
                }
            } else {
                results.forEach { result ->
                    PidResultCard(result, strings, onOpen)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun PidResultCard(
    result: PidSearchResult,
    strings: PidUiStrings,
    onOpen: (PidTarget, Long, Long?) -> Unit
) {
    val info = result.userInfo
    val isUser = result.target == PidTarget.USER
    Card(
        onClick = { onOpen(result.target, result.id, result.userId) },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            PidThumbnail(
                url = if (isUser) info?.avatarUrl else result.thumbnailUrl,
                size = if (isUser) 72.dp else 88.dp,
                shape = if (isUser) CircleShape else RoundedCornerShape(16.dp),
                fallback = (if (isUser) info?.name else result.title)
                    ?.trim()
                    ?.take(1)
                    ?.ifBlank { "?" }
                    ?: "?"
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = targetLabel(result.target, strings),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isUser && info?.isPremium == true) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = strings.premium,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(
                                    horizontal = 6.dp,
                                    vertical = 2.dp
                                )
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = result.title.ifBlank { "PID ${result.id}" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (result.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = result.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isUser && info != null) {
                    if (info.comment.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = info.comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            strings.userStats,
                            info.totalIllusts,
                            info.totalManga,
                            info.totalNovels,
                            info.totalFollowUsers
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val itemCount = result.itemCount
                if (!isUser && itemCount != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            strings.itemCount,
                            itemCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "PID ${result.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PidThumbnail(
    url: String?,
    size: Dp,
    shape: Shape,
    fallback: String
) {
    val density = LocalDensity.current
    val targetPx = with(density) { size.roundToPx() }
    var bitmap by remember(url, targetPx) {
        mutableStateOf(PidImageLoader.peek(url))
    }
    var loading by remember(url, targetPx) {
        mutableStateOf(url != null && bitmap == null)
    }
    LaunchedEffect(url, targetPx) {
        if (url != null && bitmap == null) {
            bitmap = PidImageLoader.load(url, targetPx)
        }
        loading = false
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        when {
            image != null -> Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            else -> Text(
                text = fallback,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PidFallbackButtons(
    pid: Long,
    strings: PidUiStrings,
    onOpen: (PidTarget, Long, Long?) -> Unit
) {
    val targets = listOf(
        PidTarget.ILLUST to strings.typeIllust,
        PidTarget.NOVEL to strings.typeNovel,
        PidTarget.USER to strings.typeUser,
        PidTarget.ILLUST_SERIES to strings.typeIllustSeries,
        PidTarget.NOVEL_SERIES to strings.typeNovelSeries
    )
    targets.forEach { (target, label) ->
        OutlinedButton(
            onClick = { onOpen(target, pid, null) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(label)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun targetLabel(target: PidTarget, strings: PidUiStrings): String = when (target) {
    PidTarget.ILLUST -> strings.typeIllust
    PidTarget.NOVEL -> strings.typeNovel
    PidTarget.USER -> strings.typeUser
    PidTarget.ILLUST_SERIES -> strings.typeIllustSeries
    PidTarget.NOVEL_SERIES -> strings.typeNovelSeries
}
