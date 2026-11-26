package com.pixiv.pixiv.advancesearch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
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

fun createPidComposeView(
    activity: Activity,
    hostContext: Context,
    moduleResources: Resources,
    title: String,
    description: String,
    hint: String,
    illustLabel: String,
    novelLabel: String,
    userLabel: String,
    onOpenIllust: (Long) -> Unit,
    onOpenNovel: (Long) -> Unit,
    onOpenUser: (Long) -> Unit,
    onInvalid: () -> Unit
): ComposeView {
    val moduleContext = ModuleComposeContext(hostContext, moduleResources)
    val owner = HostComposeOwner(activity)

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
                        title = title,
                        description = description,
                        hint = hint,
                        illustLabel = illustLabel,
                        novelLabel = novelLabel,
                        userLabel = userLabel,
                        onOpenIllust = onOpenIllust,
                        onOpenNovel = onOpenNovel,
                        onOpenUser = onOpenUser,
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
    title: String,
    description: String,
    hint: String,
    illustLabel: String,
    novelLabel: String,
    userLabel: String,
    onOpenIllust: (Long) -> Unit,
    onOpenNovel: (Long) -> Unit,
    onOpenUser: (Long) -> Unit,
    onInvalid: () -> Unit
) {
    var pid by remember { mutableStateOf("") }

    fun open(target: (Long) -> Unit) {
        val value = pid.trim().toLongOrNull()
        if (value == null || value <= 0L) {
            onInvalid()
        } else {
            target(value)
        }
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
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = pid,
                onValueChange = { input -> pid = input.filter { it.isDigit() } },
                label = { Text(hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                PidAction(illustLabel) { open(onOpenIllust) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                PidAction(novelLabel) { open(onOpenNovel) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                PidAction(userLabel) { open(onOpenUser) }
            }
        }
    }
}

@Composable
private fun PidAction(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        },
        trailingContent = {
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}
