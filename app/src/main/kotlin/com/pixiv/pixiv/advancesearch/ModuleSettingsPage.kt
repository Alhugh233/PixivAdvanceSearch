package com.pixiv.pixiv.advancesearch

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt

private const val MODULE_PACKAGE = "com.pixiv.pixiv.advancesearch"

private val SettingsLightColors = lightColorScheme(
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

private val SettingsDarkColors = darkColorScheme(
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

private val SettingsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private class SettingsComposeContext(
    base: Context,
    private val moduleRes: Resources
) : ContextWrapper(base) {
    override fun getResources(): Resources = moduleRes
    override fun getAssets() = moduleRes.assets
}

private class SettingsComposeOwner(private val activity: Activity) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private var disposed = false

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity === this@SettingsComposeOwner.activity) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        override fun onActivityStarted(activity: Activity) {
            if (activity === this@SettingsComposeOwner.activity) {
                registry.currentState = Lifecycle.State.STARTED
            }
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity === this@SettingsComposeOwner.activity) {
                registry.currentState = Lifecycle.State.RESUMED
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity === this@SettingsComposeOwner.activity) {
                registry.currentState = Lifecycle.State.STARTED
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity === this@SettingsComposeOwner.activity) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (activity === this@SettingsComposeOwner.activity) {
                dispose()
            }
        }
    }

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        activity.application.registerActivityLifecycleCallbacks(callbacks)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        runCatching { registry.currentState = Lifecycle.State.DESTROYED }
        runCatching {
            activity.application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }
}

internal fun showModuleSettings(
    activity: Activity,
    hostContext: Context,
    moduleResources: Resources
) {
    val moduleContext = SettingsComposeContext(hostContext, moduleResources)
    val owner = SettingsComposeOwner(activity)
    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)

    val composeView = ComposeView(moduleContext).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setContent {
            CompositionLocalProvider(
                LocalContext provides moduleContext,
                LocalResources provides moduleResources
            ) {
                ModuleSettingsTheme {
                    ModuleSettingsScreen(
                        context = moduleContext,
                        version = moduleVersion(hostContext),
                        onClose = { dialog.dismiss() }
                    )
                }
            }
        }
    }

    dialog.setContentView(composeView)
    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.decorView?.let { root ->
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
            root.setViewTreeViewModelStoreOwner(owner)
        }
    }
    dialog.setOnDismissListener { owner.dispose() }
    dialog.show()
    dialog.window?.setLayout(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
}

private fun moduleVersion(context: Context): String {
    return runCatching {
        context.packageManager.getPackageInfo(MODULE_PACKAGE, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.3"
}

@Composable
private fun ModuleSettingsTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) SettingsDarkColors else SettingsLightColors,
        motionScheme = MotionScheme.expressive(),
        typography = Typography(),
        shapes = SettingsShapes,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleSettingsScreen(
    context: Context,
    version: String,
    onClose: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.module_settings),
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            TextButton(onClick = onClose) {
                                Text(stringResource(R.string.settings_close))
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text(stringResource(R.string.settings_tab_config)) }
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(stringResource(R.string.settings_tab_about)) }
                        )
                    }
                }
            }
        ) { padding ->
            if (tab == 0) {
                ConfigScreen(context, Modifier.padding(padding))
            } else {
                AboutScreen(version, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun ConfigScreen(context: Context, modifier: Modifier = Modifier) {
    var badges by remember { mutableStateOf(ModulePrefs.specialBadges(context)) }
    var blurCovers by remember { mutableStateOf(ModulePrefs.blurCovers(context)) }
    var blurDetail by remember { mutableStateOf(ModulePrefs.blurDetail(context)) }
    var radius by remember { mutableIntStateOf(ModulePrefs.blurRadius(context)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        SectionTitle(stringResource(R.string.settings_section_toggleable))

        FeatureSwitch(
            title = stringResource(R.string.settings_badges_title),
            description = stringResource(R.string.settings_badges_desc),
            checked = badges,
            onCheckedChange = {
                badges = it
                ModulePrefs.setSpecialBadges(context, it)
            }
        )
        FeatureSwitch(
            title = stringResource(R.string.settings_blur_covers_title),
            description = stringResource(R.string.settings_blur_covers_desc),
            checked = blurCovers,
            onCheckedChange = {
                blurCovers = it
                ModulePrefs.setBlurCovers(context, it)
            }
        )
        FeatureSwitch(
            title = stringResource(R.string.settings_blur_detail_title),
            description = stringResource(R.string.settings_blur_detail_desc),
            checked = blurDetail,
            onCheckedChange = {
                blurDetail = it
                ModulePrefs.setBlurDetail(context, it)
            }
        )

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_blur_radius),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${radius}dp",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = radius.toFloat(),
                    onValueChange = {
                        radius = it.roundToInt().coerceIn(
                            ModulePrefs.MIN_BLUR_RADIUS,
                            ModulePrefs.MAX_BLUR_RADIUS
                        )
                        ModulePrefs.setBlurRadius(context, radius)
                    },
                    valueRange = ModulePrefs.MIN_BLUR_RADIUS.toFloat()..ModulePrefs.MAX_BLUR_RADIUS.toFloat(),
                    steps = ModulePrefs.MAX_BLUR_RADIUS - ModulePrefs.MIN_BLUR_RADIUS - 1
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.settings_section_always_on))

        AlwaysOnRow(
            title = stringResource(R.string.settings_pid_title),
            description = stringResource(R.string.settings_pid_desc)
        )
        AlwaysOnRow(
            title = stringResource(R.string.settings_sidebar_title),
            description = stringResource(R.string.settings_sidebar_desc)
        )
        AlwaysOnRow(
            title = stringResource(R.string.settings_work_detail_title),
            description = stringResource(R.string.settings_work_detail_desc)
        )
        AlwaysOnRow(
            title = stringResource(R.string.settings_trial_title),
            description = stringResource(R.string.settings_trial_desc)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AboutScreen(version: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "PixivAdvanceSearch",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_version, version),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_about_desc),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_about_tech),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun FeatureSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun AlwaysOnRow(title: String, description: String) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_on),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}
