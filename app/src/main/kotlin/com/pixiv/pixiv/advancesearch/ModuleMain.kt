package com.pixiv.pixiv.advancesearch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface.PRIORITY_LOWEST
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.Serializable
import java.lang.reflect.Field
import java.lang.reflect.Method

class ModuleMain : XposedModule() {

    companion object {
        private const val TAG = "PixivAdvanceSearch"
        private const val PIXIV = "jp.pxv.android"
        private const val MAIN_ACTIVITY = "jp.pxv.android.MainActivity"
        private const val ILLUST_ACTIVITY = "jp.pxv.android.feature.illustviewer.detail.IllustDetailSingleActivity"
        private const val NOVEL_ACTIVITY = "jp.pxv.android.feature.novelviewer.noveltext.NovelTextActivity"
        private const val USER_ACTIVITY = "jp.pxv.android.feature.userprofile.activity.UserProfileActivity"
        private const val ILLUST_SERIES_ACTIVITY =
            "jp.pxv.android.feature.illustseriesdetail.IllustSeriesDetailActivity"
        private const val NOVEL_SERIES_ACTIVITY =
            "jp.pxv.android.feature.novelseriesdetail.NovelSeriesDetailActivity"
        private const val DISPLAY_SETTINGS_MENU_ID = 0x4D4F4455
        private const val MODULE_SETTINGS_MENU_ID = 0x4D4F4456
        private const val SEARCH_SEGMENT_CLASS =
            "jp.pxv.android.feature.component.androidview.segmentedcontrol.SegmentedLayout"
        private const val SEARCH_RESULT_ACTIVITY =
            "jp.pxv.android.feature.search.searchresult.SearchResultActivity"
        private const val THUMBNAIL_VIEW =
            "jp.pxv.android.feature.commonlist.view.ThumbnailView"
        private const val ILLUST_MODEL = "defpackage.ub7"
        private const val RANKING_ITEM = "defpackage.w64"
        private const val ILLUST_CARD_ITEM_VIEW =
            "jp.pxv.android.feature.ranking.common.IllustCardItemView"
        private const val DETAIL_IMAGE_HOLDER =
            "jp.pxv.android.feature.illustviewer.detail.DetailImageViewHolder"
        private const val DETAIL_UGOIRA_HOLDER =
            "jp.pxv.android.feature.illustviewer.detail.DetailUgoiraViewHolder"
        private const val PID_SEGMENT_INDEX = 3
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        init { Log.i(TAG, "===== Module loaded =====") }
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Log.i(TAG, "process: ${param.processName}")

        try {
            System.loadLibrary("dexkit")
            Log.i(TAG, "libdexkit.so loaded OK")
        } catch (e: Throwable) {
            Log.e(TAG, "libdexkit.so load FAILED: ${e.message}", e)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // onPackageLoaded is Q+; Android 9 uses onPackageReady below.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (param.packageName != PIXIV) return
        installForPackage(param.packageName, param.defaultClassLoader, param.applicationInfo.sourceDir)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        if (param.packageName != PIXIV) return
        installForPackage(param.packageName, param.classLoader, param.applicationInfo.sourceDir)
    }

    private var installedPackage: String? = null

    private fun installForPackage(packageName: String, cl: ClassLoader, apk: String) {
        if (installedPackage == packageName) return
        installedPackage = packageName
        Log.i(TAG, "APK: $apk")

        // Display settings — add item to MainActivity's NavigationView sidebar
        installDisplaySetting(cl)

        val bridge = try { DexKitBridge.create(apk) } catch (e: Throwable) {
            Log.e(TAG, "DexKit create failed: ${e.message}", e); null
        }

        // PID search — direct class name first, DexKit fallback if it changes.
        installPidSegment(cl, bridge)

        // R18 / R18G / AI-generated badges on illust covers.
        installSpecialBadge(cl, bridge)
        installDetailBlur(cl)

        if (bridge != null && bridge.isValid) {
            installTrialHook(bridge, cl)
            installAbTestHook(bridge, cl)
            installWorkDetailHook(bridge, cl)
            bridge.close()
            Log.i(TAG, "DexKit hooks installed")
        } else {
            Log.w(TAG, "DexKit unavailable, using fallback")
            installTrialFallback(cl)
            installAbTestFallback(cl)
            installWorkDetailFallback(cl)
        }
    }

    // ================================================================
    // PID Search — add a PID segment to the search page top bar and
    // show a dedicated PID page (direct class name + DexKit fallback)
    // ================================================================

    // View.setTag(int, Object) only accepts application-specific resource ids.
    private val pidPageKey = R.id.pid_page_tag
    private val pidAddedKey = R.id.pid_added_tag
    private val pidWrapperKey = R.id.pid_wrapper_tag

    private class PidPageState(
        val page: View,
        val contentViews: List<View>,
        var previousVisibilities: IntArray = IntArray(0),
        var previousIndex: Int = 0,
        var shown: Boolean = false,
        var skipRestore: Boolean = false
    )

    private fun findSegmentClass(classLoader: ClassLoader, bridge: DexKitBridge?): Class<*>? {
        // SegmentedLayout is unobfuscated in 6.204.0; DexKit is the safety net.
        try {
            return classLoader.loadClass(SEARCH_SEGMENT_CLASS)
        } catch (e: Throwable) {
            Log.w(TAG, "PID: direct class load failed: ${e.message}")
        }

        if (bridge == null || !bridge.isValid) return null
        val candidates = listOf(
            "setOnSelectSegmentListener",
            "getCurrentSelectedIndex",
            "setSelectedSegment"
        )
        for (methodName in candidates) {
            try {
                val results = bridge.findMethod {
                    matcher { name = methodName }
                }
                for (result in results) {
                    val cls = runCatching { result.getClassInstance(classLoader) }
                        .getOrNull() ?: continue
                    if (View::class.java.isAssignableFrom(cls)) {
                        Log.i(TAG, "PID: SegmentedLayout=${cls.name} via DexKit ($methodName)")
                        return cls
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "PID: DexKit search $methodName failed: ${e.message}")
            }
        }
        Log.e(TAG, "PID: SegmentedLayout not found")
        return null
    }

    private fun installPidSegment(classLoader: ClassLoader, bridge: DexKitBridge?) {
        try {
            val segClass = findSegmentClass(classLoader, bridge) ?: return
            val setter = segClass.methods.firstOrNull {
                it.name == "setOnSelectSegmentListener" && it.parameterTypes.size == 1
            } ?: segClass.declaredFields.asSequence()
                .filter {
                    it.type.isInterface &&
                        !it.type.name.startsWith("java.") &&
                        !it.type.name.startsWith("android.")
                }
                .mapNotNull { field ->
                    segClass.methods.firstOrNull {
                        it.parameterTypes.size == 1 && it.parameterTypes[0] == field.type
                    }
                }
                .firstOrNull()
            if (setter == null) {
                Log.e(TAG, "PID: segment listener setter not found")
                return
            }
            val listenerClass = setter.parameterTypes[0]

            hook(setter).intercept { chain ->
                val seg = chain.thisObject as? View
                var proxy: Any? = null
                if (seg != null) {
                    try {
                        proxy = java.lang.reflect.Proxy.newProxyInstance(
                            listenerClass.classLoader,
                            arrayOf(listenerClass),
                            pidSegmentHandler(seg, chain.getArg(0))
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "PID: listener proxy failed: ${e.message}", e)
                    }
                }
                val wrapper = proxy
                if (seg != null && wrapper != null) {
                    chain.proceed(arrayOf<Any>(wrapper))
                    seg.setTag(pidWrapperKey, wrapper)
                    seg.post { ensurePidSegment(seg) }
                } else {
                    chain.proceed()
                }
                null
            }

            // Fallback for R8-inlined setters: wrap the listener directly after attach.
            try {
                val ctor = segClass.getConstructor(
                    Context::class.java, android.util.AttributeSet::class.java
                )
                hook(ctor).intercept { chain ->
                    chain.proceed()
                    try {
                        val seg = chain.thisObject as View
                        seg.post { ensurePidSegment(seg) }
                    } catch (e: Throwable) {
                        Log.w(TAG, "PID: constructor post failed: ${e.message}")
                    }
                    null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "PID: constructor hook failed: ${e.message}")
            }

            // av8.onResume() resets the selected segment to the last content type.
            // While the PID page is shown, report USER (2) so it does not switch away.
            try {
                val getter = segClass.getMethod("getCurrentSelectedIndex")
                hook(getter).intercept { chain ->
                    val seg = chain.thisObject as? View
                    val state = seg?.getTag(pidPageKey) as? PidPageState
                    if (state?.shown == true) 2 else chain.proceed()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "PID: getter hook failed: ${e.message}")
            }
            Log.i(TAG, "PID: segment hook installed")
        } catch (e: Throwable) {
            Log.e(TAG, "PID hook FAILED: ${e.message}", e)
        }
    }

    // ================================================================
    // Special badges — R18 / R18G / AI-generated on illust covers
    // ================================================================

    private data class ThumbnailBind(
        val method: Method,
        val modelClass: Class<*>,
        val methodName: String
    )

    private data class RankingBind(
        val itemClass: Class<*>,
        val method: Method,
        val viewField: String,
        val modelField: String
    )

    private fun installSpecialBadge(classLoader: ClassLoader, bridge: DexKitBridge?) {
        val thumbnail = resolveThumbnailBind(classLoader, bridge)
        if (thumbnail == null) {
            Log.e(TAG, "Badge: ThumbnailView bind not found")
        } else {
            try {
                hook(thumbnail.method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        updateSpecialBadge(chain.thisObject as? View, chain.getArg(0))
                    } catch (e: Throwable) {
                        Log.w(TAG, "Badge: thumbnail update failed: ${e.message}")
                    }
                    result
                }
                Log.i(
                    TAG,
                    "Badge: ThumbnailView hook installed " +
                        "(${thumbnail.methodName}, model=${thumbnail.modelClass.name})"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Badge: ThumbnailView hook failed", e)
            }
        }

        val ranking = resolveRankingBind(classLoader, bridge, thumbnail?.modelClass)
        if (ranking == null) {
            Log.e(TAG, "Badge: ranking bind not found")
        } else {
            try {
                hook(ranking.method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val item = chain.thisObject
                        val view = ranking.itemClass
                            .getDeclaredField(ranking.viewField)
                            .apply { isAccessible = true }
                            .get(item) as? View
                        val model = ranking.itemClass
                            .getDeclaredField(ranking.modelField)
                            .apply { isAccessible = true }
                            .get(item)
                        updateSpecialBadge(view, model)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Badge: ranking update failed: ${e.message}")
                    }
                    result
                }
                Log.i(
                    TAG,
                    "Badge: ranking hook installed " +
                        "(${ranking.itemClass.name}.${ranking.method.name}, " +
                        "view=${ranking.viewField}, model=${ranking.modelField})"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Badge: ranking hook failed", e)
            }
        }
    }

    private fun resolveThumbnailBind(
        classLoader: ClassLoader,
        bridge: DexKitBridge?
    ): ThumbnailBind? {
        if (bridge != null && bridge.isValid) {
            try {
                val results = bridge.findMethod {
                    matcher {
                        declaredClass = THUMBNAIL_VIEW
                        paramCount = 3
                    }
                }
                for (result in results) {
                    val types = result.paramTypeNames
                    if (types.size != 3) continue
                    if (!isBooleanType(types[1]) || !isIntegerType(types[2])) continue
                    val modelClass = runCatching {
                        classLoader.loadClass(types[0])
                    }.getOrNull() ?: continue
                    val method = runCatching {
                        result.getMethodInstance(classLoader)
                    }.getOrNull() ?: continue
                    return ThumbnailBind(method, modelClass, result.methodName)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Badge: DexKit ThumbnailView search failed: ${e.message}")
            }
        }

        return try {
            val thumbClass = classLoader.loadClass(THUMBNAIL_VIEW)
            val modelClass = classLoader.loadClass(ILLUST_MODEL)
            val method = thumbClass.getDeclaredMethod(
                "d",
                modelClass,
                java.lang.Boolean.TYPE,
                Integer::class.java
            )
            ThumbnailBind(method, modelClass, "d")
        } catch (e: Throwable) {
            Log.e(TAG, "Badge: fallback ThumbnailView bind failed", e)
            null
        }
    }

    private fun resolveRankingBind(
        classLoader: ClassLoader,
        bridge: DexKitBridge?,
        modelClass: Class<*>?
    ): RankingBind? {
        val modelName = modelClass?.name
        if (bridge != null && bridge.isValid && modelName != null) {
            try {
                val classes = bridge.findClass {
                    matcher {
                        addFieldForType(ILLUST_CARD_ITEM_VIEW)
                    }
                }
                for (classData in classes) {
                    val viewField = classData.fields
                        .firstOrNull { it.typeName == ILLUST_CARD_ITEM_VIEW }
                        ?.fieldName ?: continue
                    val modelField = classData.fields
                        .firstOrNull { it.typeName == modelName }
                        ?.fieldName ?: continue
                    val methods = classData.methods.filter {
                        it.isMethod &&
                            it.paramCount == 0 &&
                            (it.returnTypeName == "void" || it.returnTypeName == "V")
                    }
                    val methodData = methods.firstOrNull { it.methodName == "a" }
                        ?: methods.firstOrNull() ?: continue
                    val method = runCatching {
                        methodData.getMethodInstance(classLoader)
                    }.getOrNull() ?: continue
                    return RankingBind(
                        method.declaringClass,
                        method,
                        viewField,
                        modelField
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Badge: DexKit ranking search failed: ${e.message}")
            }
        }

        return try {
            val itemClass = classLoader.loadClass(RANKING_ITEM)
            val method = itemClass.getDeclaredMethod("a")
            RankingBind(itemClass, method, "c", "d")
        } catch (e: Throwable) {
            Log.e(TAG, "Badge: fallback ranking bind failed", e)
            null
        }
    }

    private fun isBooleanType(name: String): Boolean =
        name == "boolean" || name == "Z" || name == "java.lang.Boolean"

    private fun isIntegerType(name: String): Boolean =
        name == "java.lang.Integer" || name == "int" || name == "I"

    private fun updateSpecialBadge(view: View?, model: Any?) {
        if (view == null || model == null) return
        val context = view.context
        val restrict = readRestrict(model)
        val aiType = readAiType(model)
        val labels = ArrayList<String>(2)
        if (ModulePrefs.specialBadges(context)) {
            when (restrict) {
                1 -> labels += "R18"
                2 -> labels += "R18G"
            }
            if (aiType == 2) labels += "AI"
        }

        val coverImage = findCoverImage(view)
        applyBlurToView(
            coverImage,
            ModulePrefs.blurCovers(context) && (restrict == 1 || restrict == 2),
            ModulePrefs.blurRadius(context)
        )

        val existing = view.getTag(R.id.special_badge_tag) as? LinearLayout
        if (labels.isEmpty()) {
            existing?.visibility = View.GONE
            return
        }
        val container = existing ?: run {
            if (view !is ViewGroup) return
            LinearLayout(view.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM or Gravity.START
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(
                    WRAP,
                    WRAP,
                    Gravity.BOTTOM or Gravity.START
                ).apply {
                    leftMargin = dp(view.context, 6)
                    bottomMargin = dp(view.context, 6)
                }
            }.also {
                view.addView(it)
                view.setTag(R.id.special_badge_tag, it)
            }
        }

        container.removeAllViews()
        labels.forEach { label -> container.addView(makeSpecialBadge(view.context, label)) }
        container.visibility = View.VISIBLE
        container.bringToFront()
    }

    private fun findCoverImage(root: View): ImageView? {
        var exact: ImageView? = null
        var best: ImageView? = null
        var bestArea = -1
        fun visit(view: View) {
            if (exact != null) return
            if (view is ImageView) {
                val name = runCatching {
                    view.resources.getResourceEntryName(view.id)
                }.getOrNull()
                if (name == "image_view" || name == "illust_image_view") {
                    exact = view
                    return
                }
                val area = view.width * view.height
                if (area > bestArea) {
                    bestArea = area
                    best = view
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(root)
        return exact ?: best
    }

    private fun applyBlurToView(target: View?, enabled: Boolean, radiusDp: Int) {
        if (target == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = radiusDp * target.resources.displayMetrics.density
            target.setRenderEffect(
                if (enabled) {
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                } else {
                    null
                }
            )
            target.alpha = 1f
            if (target is ImageView) target.clearColorFilter()
        } else {
            target.alpha = if (enabled) 0.4f else 1f
            if (target is ImageView) {
                if (enabled) {
                    target.setColorFilter(Color.argb(80, 0, 0, 0))
                } else {
                    target.clearColorFilter()
                }
            }
        }
    }

    private fun installDetailBlur(classLoader: ClassLoader) {
        hookDetailBlur(classLoader, DETAIL_IMAGE_HOLDER, "imageView")
        hookDetailBlur(
            classLoader,
            DETAIL_UGOIRA_HOLDER,
            "previewImageView",
            "ugoiraView"
        )
    }

    private fun hookDetailBlur(
        classLoader: ClassLoader,
        className: String,
        vararg imageFields: String
    ) {
        try {
            val cls = classLoader.loadClass(className)
            val bind = cls.getDeclaredMethod("bind", Any::class.java)
            hook(bind).intercept { chain ->
                val result = chain.proceed()
                try {
                    val holder = chain.thisObject
                    val item = chain.getArg(0)
                    val model = item?.javaClass
                        ?.getDeclaredField("d")
                        ?.apply { isAccessible = true }
                        ?.get(item)
                    val itemView = holder.javaClass
                        .getField("itemView")
                        .get(holder) as? View
                    val context = itemView?.context
                    val restrict = model?.let { readRestrict(it) } ?: 0
                    val enabled = context != null &&
                        ModulePrefs.blurDetail(context) &&
                        (restrict == 1 || restrict == 2)
                    val radius = context?.let { ModulePrefs.blurRadius(it) }
                        ?: ModulePrefs.DEFAULT_BLUR_RADIUS
                    for (fieldName in imageFields) {
                        val target = runCatching {
                            holder.javaClass
                                .getDeclaredField(fieldName)
                                .apply { isAccessible = true }
                                .get(holder)
                        }.getOrNull() as? View
                        applyBlurToView(target, enabled, radius)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Blur: detail update failed: ${e.message}")
                }
                result
            }
            Log.i(TAG, "Blur: $className hook installed")
        } catch (e: Throwable) {
            Log.e(TAG, "Blur: $className hook failed", e)
        }
    }

    private fun readRestrict(model: Any): Int {
        readModelInt(model, "B0", "q")?.let { return it }
        return Regex("xRestrict=(\\d+)")
            .find(model.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun readAiType(model: Any): Int {
        readModelInt(model, null, "x")?.let { return it }
        return Regex("illustAiType=(\\d+)")
            .find(model.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun readModelInt(model: Any, methodName: String?, fieldName: String?): Int? {
        if (methodName != null) {
            try {
                val method = model.javaClass.getMethod(methodName)
                (method.invoke(model) as? Int)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        if (fieldName != null) {
            try {
                return model.javaClass
                    .getDeclaredField(fieldName)
                    .apply { isAccessible = true }
                    .getInt(model)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun makeSpecialBadge(context: Context, label: String): TextView {
        val color = when (label) {
            "R18" -> 0xFFE53935.toInt()
            "R18G" -> 0xFF8E24AA.toInt()
            else -> 0xFF1E88E5.toInt()
        }
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(context, 5), dp(context, 2), dp(context, 5), dp(context, 2))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 4).toFloat()
                setColor(color)
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginEnd = dp(context, 4)
            }
        }
    }

    private fun pidSegmentHandler(seg: View, original: Any?): java.lang.reflect.InvocationHandler {
        return java.lang.reflect.InvocationHandler { proxy, method, args ->
            // Do not rely on the obfuscated method name; the segment callback is
            // the listener method taking a single int.
            val isSelectCallback = method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == java.lang.Integer.TYPE
            if (isSelectCallback) {
                val index = args?.getOrNull(0) as? Int
                if (index == PID_SEGMENT_INDEX) {
                    Log.i(TAG, "PID: segment callback index=$index added=${seg.getTag(pidAddedKey) == true}")
                }
                if (index == PID_SEGMENT_INDEX && seg.getTag(pidAddedKey) == true) {
                    showPidPage(seg)
                    // Fallback if getCurrentSelectedIndex was inlined by R8:
                    // keep av8.onResume() from resetting the selected segment.
                    seg.post {
                        val state = seg.getTag(pidPageKey) as? PidPageState
                        if (state?.shown == true) setSegmentIndexField(seg, 2)
                    }
                    null
                } else {
                    hidePidPage(seg)
                    invokeOriginal(original, method, args)
                }
            } else when (method.name) {
                "equals" -> proxy === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PidSegmentListener"
                else -> invokeOriginal(original, method, args)
            }
        }
    }

    private fun invokeOriginal(original: Any?, method: Method, args: Array<out Any?>?): Any? {
        if (original == null) return null
        return try {
            method.invoke(original, *args.orEmpty())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun ensurePidSegment(seg: View) {
        if (!seg.isAttachedToWindow) return
        // The search-result screen has its own 3-segment layout. PID search belongs
        // to the main search tab only, so do not touch that screen.
        if (seg.context.findActivity()?.javaClass?.name == SEARCH_RESULT_ACTIVITY) {
            Log.i(TAG, "PID: skip SearchResultActivity segment")
            return
        }
        val inner = (seg as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        if (inner.childCount != 3) return
        if (!isSearchSegmentedLayout(inner, seg.context)) {
            Log.w(TAG, "PID: 3-segment layout ignored (labels do not match search)")
            return
        }
        if (!wrapPidListener(seg)) {
            Log.e(TAG, "PID: listener not wrapped, segment skipped")
            return
        }

        val label = str(seg.context, R.string.pid_button, "PID")
        try {
            val add = seg.javaClass.getMethod(
                "a", Array<String>::class.java, java.lang.Integer.TYPE
            )
            val before = inner.childCount
            add.invoke(seg, arrayOf(label), 0)
            if (inner.childCount > before) {
                seg.setTag(pidAddedKey, true)
                seg.requestLayout()
                Log.i(TAG, "PID: segment added")
                return
            }
        } catch (e: Throwable) {
            Log.w(TAG, "PID: SegmentedLayout.a failed: ${e.message}, using manual view")
        }
        if (appendPidSegmentManually(seg, inner, label)) {
            seg.setTag(pidAddedKey, true)
            seg.requestLayout()
            Log.i(TAG, "PID: segment added manually")
        }
    }

    private fun wrapPidListener(seg: View): Boolean {
        val existing = seg.getTag(pidWrapperKey)
        try {
            val field = seg.javaClass.getField("c")
            field.isAccessible = true
            val current = field.get(seg) ?: return false
            if (current === existing) return true
            val listenerClass = field.type
            val wrapper = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
                pidSegmentHandler(seg, current)
            )
            field.set(seg, wrapper)
            seg.setTag(pidWrapperKey, wrapper)
            Log.i(TAG, "PID: listener wrapped via field")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "PID: listener wrap failed: ${e.message}")
            return existing != null
        }
    }

    private fun isSearchSegmentedLayout(inner: ViewGroup, context: Context): Boolean {
        val arrayId = context.resources.getIdentifier(
            "core_string_illustmanga_novel_user", "array", PIXIV
        )
        if (arrayId == 0) return false
        val expected = context.resources.getStringArray(arrayId)
        if (expected.size != inner.childCount) return false
        for (i in expected.indices) {
            val actual = segmentText(inner, i) ?: return false
            if (!actual.equals(expected[i], ignoreCase = true)) return false
        }
        return true
    }

    private fun segmentText(inner: ViewGroup, index: Int): String? {
        val item = inner.getChildAt(index) as? ViewGroup ?: return null
        return (item.getChildAt(0) as? TextView)?.text?.toString()
    }

    private fun appendPidSegmentManually(seg: View, inner: ViewGroup, label: String): Boolean {
        if (inner.childCount == 0) return false
        val template = inner.getChildAt(0) as? ViewGroup ?: return false
        val templateText = template.getChildAt(0) as? TextView ?: return false
        val context = seg.context

        val item = FrameLayout(context)
        val lp = template.layoutParams
        item.layoutParams = if (lp is LinearLayout.LayoutParams)
            LinearLayout.LayoutParams(lp.width, lp.height, lp.weight)
        else
            LinearLayout.LayoutParams(lp.width, lp.height)
        item.setOnClickListener(seg as? View.OnClickListener)

        val text = TextView(context)
        text.layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.CENTER
        }
        text.setPadding(
            templateText.paddingLeft, templateText.paddingTop,
            templateText.paddingRight, templateText.paddingBottom
        )
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, templateText.textSize)
        text.isSingleLine = true
        text.gravity = Gravity.CENTER
        text.text = label

        val styleSource = (0 until inner.childCount)
            .mapNotNull { (inner.getChildAt(it) as? ViewGroup)?.getChildAt(0) as? TextView }
            .firstOrNull { it.typeface?.isBold != true } ?: templateText
        text.setTextColor(styleSource.currentTextColor)
        text.typeface = styleSource.typeface

        item.addView(text)
        inner.addView(item)
        return true
    }

    private fun showPidPage(seg: View) {
        try {
            val state = getOrCreatePidPage(seg) ?: return
            if (state.shown) return
            (seg.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(seg.windowToken, 0)
            seg.rootView.findFocus()?.clearFocus()
            state.previousIndex = selectedSegmentIndex(seg).coerceIn(0, 2)
            state.previousVisibilities = IntArray(state.contentViews.size) {
                state.contentViews[it].visibility
            }
            state.contentViews.forEach { it.visibility = View.GONE }
            state.shown = true
            state.page.visibility = View.VISIBLE
        } catch (e: Throwable) {
            Log.e(TAG, "PID: show page failed: ${e.message}", e)
        }
    }

    private fun hidePidPage(seg: View) {
        try {
            val state = seg.getTag(pidPageKey) as? PidPageState ?: return
            if (!state.shown) return
            state.shown = false
            state.page.findFocus()?.clearFocus()
            (seg.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(seg.windowToken, 0)
            state.page.visibility = View.GONE
            if (state.skipRestore) {
                // The app already changed the content visibility (e.g. a new search).
                state.skipRestore = false
            } else {
                state.contentViews.forEachIndexed { i, view ->
                    if (i < state.previousVisibilities.size) {
                        view.visibility = state.previousVisibilities[i]
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "PID: hide page failed: ${e.message}", e)
        }
    }

    private fun selectedSegmentIndex(seg: View): Int {
        return try {
            seg.javaClass.getMethod("getCurrentSelectedIndex").invoke(seg) as? Int
                ?: segmentIndexField(seg)
        } catch (e: Throwable) {
            segmentIndexField(seg)
        }
    }

    private fun segmentIndexField(seg: View): Int {
        return try {
            seg.javaClass.getField("b").getInt(seg)
        } catch (e: Throwable) {
            0
        }
    }

    private fun setSegmentIndexField(seg: View, index: Int) {
        try {
            val field = seg.javaClass.getField("b")
            field.isAccessible = true
            field.setInt(seg, index)
        } catch (e: Throwable) {
            // getCurrentSelectedIndex hook is the primary mechanism.
        }
    }

    private fun exitPidPage(seg: View, state: PidPageState) {
        if (!state.shown || !seg.isAttachedToWindow) return
        state.skipRestore = true
        try {
            seg.javaClass.getMethod("setSelectedSegment", java.lang.Integer.TYPE)
                .invoke(seg, state.previousIndex)
            state.skipRestore = false
        } catch (e: Throwable) {
            Log.e(TAG, "PID: exit page failed: ${e.message}", e)
            hidePidPage(seg)
            state.skipRestore = false
        }
    }

    private fun findHostRoot(seg: View, id: Int): ViewGroup? {
        var parent = seg.parent
        while (parent is ViewGroup) {
            if (parent.findViewById<View>(id) != null) return parent
            parent = parent.parent
        }
        return null
    }

    private fun getOrCreatePidPage(seg: View): PidPageState? {
        (seg.getTag(pidPageKey) as? PidPageState)?.let { return it }
        val context = seg.context

        val typeId = idOf(context, "type_fragment_container")
        if (typeId != 0) {
            val root = findHostRoot(seg, typeId)
            val type = root?.findViewById<ViewGroup>(typeId)
            if (root != null && type != null) {
                val page = buildPidPage(context)
                val lp = if (root is LinearLayout)
                    LinearLayout.LayoutParams(MATCH, 0, 1f)
                else
                    ViewGroup.LayoutParams(MATCH, MATCH)
                root.addView(page, lp)
                Log.i(TAG, "PID: page host=type")
                return PidPageState(page, listOf(type)).also { seg.setTag(pidPageKey, it) }
            }
        }

        val contentId = idOf(context, "content_container")
        if (contentId != 0) {
            val root = findHostRoot(seg, contentId)
            val content = root?.findViewById<ViewGroup>(contentId)
            if (root != null && content != null) {
                val page = buildPidPage(context)
                // Keep it at the bottom of the stack; search overlays are hidden in PID mode.
                content.addView(page, 0, FrameLayout.LayoutParams(MATCH, MATCH))
                val viewPager = viewById(root, context, "view_pager")
                val userContainer = viewById(root, context, "search_user_result_fragment_container")
                val historyContainer = viewById(root, context, "history_fragment_container")
                val autoCompleteContainer = viewById(root, context, "auto_complete_fragment_container")
                val tabLayout = viewById(root, context, "tab_layout")
                val state = PidPageState(
                    page,
                    listOfNotNull(
                        viewPager, userContainer, historyContainer, autoCompleteContainer, tabLayout
                    )
                )
                // If any content overlay comes back (new search, search history, or
                // autocomplete), leave PID mode instead of covering it.
                listOfNotNull(
                    viewPager, userContainer, historyContainer, autoCompleteContainer
                ).forEach { contentView ->
                    contentView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        if (state.shown && contentView.visibility == View.VISIBLE) {
                            contentView.post { exitPidPage(seg, state) }
                        }
                    }
                }
                seg.setTag(pidPageKey, state)
                Log.i(TAG, "PID: page host=content")
                return state
            }
        }

        Log.e(TAG, "PID: content host not found")
        return null
    }

    private fun buildPidPage(context: Context): View {
        return try {
            val activity = context.findActivity()
            if (activity == null) {
                Log.w(TAG, "PID: no activity for Compose page, using legacy")
                return buildPidLegacyPage(context)
            }
            val resources = moduleResources(context)
            if (resources == null) {
                Log.w(TAG, "PID: no module resources for Compose page, using legacy")
                return buildPidLegacyPage(context)
            }
            val view = createPidComposeView(
                activity = activity,
                hostContext = context,
                moduleResources = resources,
                strings = PidUiStrings(
                    title = str(context, R.string.pid_search_title, "PID Search"),
                    description = str(
                        context,
                        R.string.pid_page_desc,
                        "Search a PID across illust/manga, novel, user, and series."
                    ),
                    hint = str(context, R.string.pid_hint, "Enter PID (work/user/series ID)"),
                    search = str(context, R.string.pid_search_action, "Search"),
                    searching = str(context, R.string.pid_searching, "Searching…"),
                    empty = str(
                        context,
                        R.string.pid_search_empty,
                        "No matching PID found."
                    ),
                    failed = str(
                        context,
                        R.string.pid_search_failed,
                        "Search failed: %1\$s"
                    ),
                    fallbackHint = str(
                        context,
                        R.string.pid_fallback_hint,
                        "You can still open a type directly:"
                    ),
                    typeIllust = str(
                        context,
                        R.string.pid_type_illust,
                        "Illust / Manga"
                    ),
                    typeNovel = str(context, R.string.pid_type_novel, "Novel"),
                    typeUser = str(context, R.string.pid_type_user, "User"),
                    typeIllustSeries = str(
                        context,
                        R.string.pid_type_illust_series,
                        "Manga series"
                    ),
                    typeNovelSeries = str(
                        context,
                        R.string.pid_type_novel_series,
                        "Novel series"
                    )
                ),
                onOpen = { target, pid, userId -> openPidTarget(activity, target, pid, userId) },
                onInvalid = { toast(activity, str(activity, R.string.invalid_pid, "Invalid PID")) }
            )
            view.visibility = View.GONE
            Log.i(TAG, "PID: Compose page created")
            view
        } catch (e: Throwable) {
            Log.e(TAG, "PID: Compose page failed, fallback to legacy", e)
            buildPidLegacyPage(context)
        }
    }

    private fun buildPidLegacyPage(context: Context): View {
        val bg = themeColor(context, android.R.attr.colorBackground, Color.WHITE)
        val fg = themeColor(context, android.R.attr.textColorPrimary, Color.BLACK)
        val secondary = themeColor(context, android.R.attr.textColorSecondary, Color.GRAY)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(bg)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 32), dp(context, 24), dp(context, 24))
        }
        scroll.addView(column, ViewGroup.LayoutParams(MATCH, WRAP))

        column.addView(TextView(context).apply {
            text = str(context, R.string.pid_search_title, "PID Search")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(fg)
        })

        column.addView(TextView(context).apply {
            text = str(
                context,
                R.string.pid_page_desc,
                "Search a PID across illust/manga works, novels, users, and series."
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(secondary)
            setPadding(0, dp(context, 8), 0, dp(context, 24))
        })

        val edit = EditText(context).apply {
            hint = str(context, R.string.pid_hint, "Enter PID (work/user/series ID)")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(fg)
            setHintTextColor(secondary)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        column.addView(edit)

        val targets = listOf(
            Triple(R.string.pid_open_illust, ILLUST_ACTIVITY, "ILLUST_ID"),
            Triple(R.string.pid_open_novel, NOVEL_ACTIVITY, "NOVEL_ID"),
            Triple(R.string.pid_open_user, USER_ACTIVITY, "USER_ID"),
            Triple(
                R.string.pid_type_illust_series,
                ILLUST_SERIES_ACTIVITY,
                "ILLUST_SERIES_ID"
            ),
            Triple(
                R.string.pid_type_novel_series,
                NOVEL_SERIES_ACTIVITY,
                "NOVEL_SERIES_ID"
            )
        )
        for ((labelId, cls, key) in targets) {
            column.addView(Button(context).apply {
                text = str(context, labelId, "Open")
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(context, 52)).apply {
                    topMargin = dp(context, 12)
                }
                setOnClickListener { launchPid(context, edit, cls, key) }
            })
        }
        scroll.visibility = View.GONE
        return scroll
    }

    private fun launchPid(context: Context, edit: EditText, cls: String, key: String) {
        val activity = context.findActivity() ?: return
        val pid = edit.text.toString().trim().toLongOrNull()
        if (pid == null || pid <= 0) {
            toast(activity, str(activity, R.string.invalid_pid, "Invalid PID"))
            return
        }
        try {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(edit.windowToken, 0)
            activity.startActivity(
                Intent(activity, Class.forName(cls, false, activity.classLoader)).apply {
                    putExtra(key, pid)
                }
            )
        } catch (e: Throwable) {
            toast(activity, str(activity, R.string.failed_format, "Failed: %1\$s", e.message ?: ""))
        }
    }

    private fun openPidTarget(activity: Activity, target: PidTarget, pid: Long, userId: Long?) {
        when (target) {
            PidTarget.ILLUST -> openPid(activity, pid, ILLUST_ACTIVITY, "ILLUST_ID")
            PidTarget.NOVEL -> openPid(activity, pid, NOVEL_ACTIVITY, "NOVEL_ID")
            PidTarget.USER -> openPid(activity, pid, USER_ACTIVITY, "USER_ID")
            PidTarget.ILLUST_SERIES ->
                openPid(activity, pid, ILLUST_SERIES_ACTIVITY, "ILLUST_SERIES_ID")
            PidTarget.NOVEL_SERIES -> openNovelSeries(activity, pid, userId)
        }
    }

    private fun openNovelSeries(activity: Activity, seriesId: Long, userId: Long?) {
        try {
            activity.startActivity(
                Intent(
                    activity,
                    Class.forName(NOVEL_SERIES_ACTIVITY, false, activity.classLoader)
                ).apply {
                    putExtra("NOVEL_SERIES_ID", seriesId)
                    if (userId != null && userId > 0L) {
                        putExtra("NOVEL_SERIES_USER_ID", userId)
                    }
                }
            )
        } catch (e: Throwable) {
            toast(activity, str(activity, R.string.failed_format, "Failed: %1\$s", e.message ?: ""))
        }
    }

    private fun openPid(activity: Activity, pid: Long, cls: String, key: String) {
        try {
            activity.startActivity(
                Intent(activity, Class.forName(cls, false, activity.classLoader)).apply {
                    putExtra(key, pid)
                }
            )
        } catch (e: Throwable) {
            toast(activity, str(activity, R.string.failed_format, "Failed: %1\$s", e.message ?: ""))
        }
    }

    private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        return try {
            val value = TypedValue()
            if (context.theme.resolveAttribute(attr, value, true)) {
                if (value.resourceId != 0) {
                    context.resources.getColor(value.resourceId, context.theme)
                } else {
                    value.data
                }
            } else fallback
        } catch (e: Throwable) {
            fallback
        }
    }

    private fun idOf(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "id", PIXIV)

    private fun viewById(root: View, context: Context, name: String): View? {
        val id = idOf(context, name)
        return if (id == 0) null else root.findViewById<View>(id)
    }

    private fun toast(a: Activity, m: String) { Toast.makeText(a, m, Toast.LENGTH_SHORT).show() }
    private fun dp(c: android.content.Context, d: Int) = (d * c.resources.displayMetrics.density).toInt()

    // ================================================================
    // Module resources / i18n
    // ================================================================

    private var moduleRes: Resources? = null
    private var moduleResConfig: Configuration? = null

    private fun createAssetManager(): AssetManager {
        return HiddenApiBypass.newInstance(AssetManager::class.java) as AssetManager
    }

    private fun addAssetPath(assets: AssetManager, path: String): Int {
        return HiddenApiBypass.invoke(
            AssetManager::class.java,
            assets,
            "addAssetPath",
            path
        ) as? Int ?: 0
    }

    /**
     * Load the module APK resources (not the host app's) so R.string.* follows
     * the host app's current locale. Falls back to the public PackageManager API.
     */
    private fun moduleResources(context: Context?): Resources? {
        if (context == null) return moduleRes
        val config = context.resources.configuration
        moduleRes?.let { if (moduleResConfig == config) return it }

        val info = try {
            getModuleApplicationInfo()
        } catch (e: Throwable) {
            Log.e(TAG, "module info failed: ${e.message}", e)
            return null
        }

        return try {
            val assets = createAssetManager()
            val cookie = addAssetPath(assets, info.sourceDir)
            if (cookie == 0) throw IllegalStateException("addAssetPath returned 0")
            Resources(assets, context.resources.displayMetrics, Configuration(config)).also {
                moduleRes = it
                moduleResConfig = Configuration(config)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "load module resources failed: ${e.message}", e)
            try {
                context.packageManager.getResourcesForApplication(info).also {
                    moduleRes = it
                    moduleResConfig = Configuration(config)
                }
            } catch (e2: Throwable) {
                Log.e(TAG, "getResourcesForApplication failed: ${e2.message}", e2)
                null
            }
        }
    }

    private fun str(context: Context?, id: Int, fallback: String, vararg args: Any): String {
        return try {
            val res = moduleResources(context)
            when {
                res == null -> if (args.isEmpty()) fallback else String.format(fallback, *args)
                args.isEmpty() -> res.getString(id)
                else -> res.getString(id, *args)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "get string $id failed: ${e.message}", e)
            if (args.isEmpty()) fallback else String.format(fallback, *args)
        }
    }

    // ================================================================
    // Work Detail — fully DexKit-based discovery + hook
    //
    // Chain: layout_inflater → builder params (pe/he) → items (p) / item listener (r)
    // → builder.create() (te/le). Hook create() BEFORE proceed: append the localized
    // detail label, wrap the item listener, then show the work info dialog.
    // ================================================================

    private fun installWorkDetailHook(bridge: DexKitBridge, classLoader: ClassLoader) {
        if (!tryInstallWorkDetailDexKit(bridge, classLoader)) {
            Log.w(TAG, "WD: DexKit failed, trying fallback")
            installWorkDetailFallback(classLoader)
        }
    }

    private fun tryInstallWorkDetailDexKit(bridge: DexKitBridge, classLoader: ClassLoader): Boolean {
        try {
            // Step 1: find pe class — search for constructor string, then verify it has the right fields
            val peResults = bridge.findMethod {
                matcher { addUsingString("layout_inflater", StringMatchType.Equals) }
            }
            var peClass: Class<*>? = null
            var itemsField: Field? = null
            var listenerField: Field? = null
            for (r in peResults) {
                val cls = r.getClassInstance(classLoader)
                var foundItems: Field? = null
                var foundListener: Field? = null
                for (f in cls.declaredFields) {
                    f.isAccessible = true
                    val type = f.type
                    if (type.isArray && CharSequence::class.java.isAssignableFrom(type.componentType))
                        foundItems = f
                    if (DialogInterface.OnClickListener::class.java.isAssignableFrom(type))
                        foundListener = f
                }
                if (foundItems != null && foundListener != null) {
                    peClass = cls; itemsField = foundItems; listenerField = foundListener
                    Log.i(TAG, "WD: pe=${peClass.name} via DexKit")
                    break
                }
            }
            if (peClass == null) { Log.e(TAG, "WD: pe class not found"); return false }

            // Step 3: find te class — the one that has a pe-typed field
            val teClass = findOwningClass(bridge, classLoader, peClass)
            if (teClass == null) { Log.e(TAG, "WD: te class not found"); return false }
            Log.i(TAG, "WD: te=${teClass.name}")

            // Step 4+5: Hook create() before it reads the builder's items/listener.
            val peField = teClass.declaredFields.find { it.type == peClass }
            if (peField == null) { Log.e(TAG, "WD: pe field not found in te"); return false }
            peField.isAccessible = true
            val peContextField = peClass.declaredFields
                .firstOrNull { Context::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
            val safeItems = itemsField!!
            val safeListener = listenerField!!
            for (createMethod in teClass.declaredMethods) {
                // Only hook create(); b() delegates to it and would otherwise inject twice.
                if (createMethod.name != "create" || createMethod.parameterTypes.isNotEmpty() ||
                    createMethod.returnType == Void.TYPE) continue
                hook(createMethod).intercept { chain ->
                    val teObj = chain.thisObject
                    val peObj = peField.get(teObj)
                    @Suppress("UNCHECKED_CAST")
                    val items = safeItems.get(peObj) as? Array<CharSequence>
                    val peContext = try {
                        peContextField?.get(peObj) as? Context
                    } catch (_: Exception) { null }
                    val detailLabel = str(peContext, R.string.view_details, "View details")

                    if (items != null && items.none { it?.toString() == detailLabel }) {
                        val origListener = safeListener.get(peObj) as? DialogInterface.OnClickListener
                        val workData = if (origListener != null) extractWorkFromListener(origListener) else null

                        // Do not touch unrelated list dialogs (profile edit, settings, ...).
                        if (origListener != null && workData != null && isWorkItem(workData)) {
                            val newItems = items + detailLabel
                            safeItems.set(peObj, newItems)

                            val detailIdx = newItems.size - 1
                            safeListener.set(peObj, DialogInterface.OnClickListener { dlg, which ->
                                if (which == detailIdx)
                                    showWorkDetailDialog(dlg, workData)
                                else
                                    origListener.onClick(dlg, which)
                            })
                        }
                    }
                    chain.proceed()
                }
            }
            Log.i(TAG, "WD: hook on ${teClass.name} installed")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "WD FAIL: ${e.message}", e)
            return false
        }
    }

    /**
     * Find the class that owns a pe-typed field (i.e., te class).
     * Strategy 1: search for methods that invoke pe's constructor via addInvoke.
     * Strategy 2: fall back to direct class name "te" (current version).
     */
    private fun findOwningClass(bridge: DexKitBridge, cl: ClassLoader, peClass: Class<*>): Class<*>? {
        // Strategy 1: find methods that invoke pe constructor
        val peCtor = peClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
        if (peCtor != null) {
            val desc = getMethodDescriptor(peCtor)
            Log.i(TAG, "WD: pe ctor descriptor = $desc")
            val results = bridge.findMethod { matcher { addInvoke(desc) } }
            Log.i(TAG, "WD: addInvoke found ${results.size} methods")
            for (r in results) {
                val cls = r.getClassInstance(cl)
                Log.i(TAG, "WD: checking ${cls.name}")
                for (f in cls.declaredFields) {
                    if (f.type == peClass) {
                        Log.i(TAG, "WD: found owning class ${cls.name}")
                        return cls
                    }
                }
            }
        }
        // Strategy 2: fallback to known class name
        return try {
            val cls = cl.loadClass("te")
            if (cls.declaredFields.any { it.type == peClass }) cls else null
        } catch (_: Exception) { null }
    }

    /** Build dex descriptor like Lpe;-><init>(Landroid/view/ContextThemeWrapper;)V */
    private fun getMethodDescriptor(m: java.lang.reflect.Executable): String {
        val sb = StringBuilder()
        sb.append(typeToDescriptor(m.declaringClass))
        sb.append("->")
        sb.append(if (m is java.lang.reflect.Constructor<*>) "<init>" else m.name)
        sb.append("(")
        for (p in m.parameterTypes) sb.append(typeToDescriptor(p))
        sb.append(")")
        sb.append(if (m is java.lang.reflect.Constructor<*>) "V" else typeToDescriptor((m as Method).returnType))
        return sb.toString()
    }

    private fun typeToDescriptor(t: Class<*>): String = when (t) {
        java.lang.Void.TYPE -> "V"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Character.TYPE -> "C"
        java.lang.Short.TYPE -> "S"
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        else -> if (t.isArray) "[" + typeToDescriptor(t.componentType)
                 else "L" + t.name.replace('.', '/') + ";"
    }

    private fun extractWorkFromListener(listener: DialogInterface.OnClickListener): Any? {
        // 6.204.0 listener z: a(byte) b(Object/w89) c(Object/v89) d(Serializable/ng7)
        // The work item is the only Serializable-typed field.
        for (f in listener.javaClass.declaredFields) {
            // Skip String payloads used by unrelated dialogs; the work model is a
            // Serializable entity (ng7 in 6.204.0).
            if (Serializable::class.java.isAssignableFrom(f.type) && f.type != String::class.java) {
                f.isAccessible = true
                return f.get(listener)
            }
        }
        return null
    }

    private fun showWorkDetailDialog(dialog: DialogInterface, work: Any) {
        try {
            val workPid = callS(work, "getId") ?: firstLong(work) ?: "—"
            val title = callS(work, "getTitle") ?: firstString(work) ?: "—"
            val user = findUser(work)
            val userName = if (user != null) findUserName(user) else "—"
            val userPid = if (user != null) findUserId(user) else "—"

            Log.i(TAG, "WD: work=${work.javaClass.name} user=${user?.javaClass?.name} " +
                    "title=$title name=$userName workPid=$workPid userPid=$userPid")

            val ctx = (dialog as? android.app.Dialog)?.context ?: return
            val body = str(
                ctx, R.string.work_details_body,
                "Title: %1\$s\nAuthor: %2\$s\nWork PID: %3\$s\nUser PID: %4\$s",
                title, userName, workPid, userPid
            )

            val tv = TextView(ctx).apply {
                setTextIsSelectable(true)
                text = body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.BLACK)
                setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8))
            }

            AlertDialog.Builder(ctx)
                .setTitle(str(ctx, R.string.work_details_title, "Work details"))
                .setView(tv)
                .setPositiveButton(str(ctx, R.string.ok, "OK"), null)
                .show()
        } catch (e: Exception) { Log.e(TAG, "showDetail: ${e.message}", e) }
    }

    /** Work models expose the user as getUser() (older) or f0() (6.204.0). */
    private fun findUser(work: Any): Any? {
        for (name in arrayOf("getUser", "f0")) {
            try {
                val value = work.javaClass.getMethod(name).invoke(work)
                if (value != null) return value
            } catch (_: Exception) { }
        }
        return null
    }

    /** 6.204.0: PixivUser(id=a, name=b, account=c, comment=d). */
    private fun findUserName(user: Any): String {
        for (name in arrayOf("name", "userName")) {
            try {
                val f = user.javaClass.getDeclaredField(name)
                f.isAccessible = true
                val value = f.get(user)?.toString()
                if (!value.isNullOrBlank()) return value
            } catch (_: Exception) { }
        }
        for (method in arrayOf("getName", "getUserName")) {
            try {
                val value = user.javaClass.getMethod(method).invoke(user)?.toString()
                if (!value.isNullOrBlank()) return value
            } catch (_: Exception) { }
        }
        // Fallback: the first non-empty String field is the display name.
        return firstString(user) ?: "—"
    }

    /** 6.204.0: PixivUser.a is the numeric user id. */
    private fun findUserId(user: Any): String {
        for (name in arrayOf("id", "userId")) {
            try {
                val f = user.javaClass.getDeclaredField(name)
                f.isAccessible = true
                val value = f.get(user)?.toString()
                if (!value.isNullOrBlank()) return value
            } catch (_: Exception) { }
        }
        for (method in arrayOf("getId", "getUserId")) {
            try {
                val value = user.javaClass.getMethod(method).invoke(user)?.toString()
                if (!value.isNullOrBlank()) return value
            } catch (_: Exception) { }
        }
        return firstLong(user) ?: "—"
    }

    private fun isWorkItem(obj: Any): Boolean {
        return try {
            obj.javaClass.getMethod("getId")
            obj.javaClass.getMethod("getTitle")
            findUser(obj) != null
        } catch (_: Exception) { false }
    }

    private fun firstLong(obj: Any): String? {
        for (f in obj.javaClass.declaredFields) {
            if (f.type == java.lang.Long.TYPE) {
                try {
                    f.isAccessible = true
                    return f.get(obj)?.toString()
                } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun firstString(obj: Any): String? {
        for (f in obj.javaClass.declaredFields) {
            if (f.type == String::class.java) {
                try {
                    f.isAccessible = true
                    val value = f.get(obj) as? String
                    if (!value.isNullOrBlank()) return value
                } catch (_: Exception) { }
            }
        }
        return null
    }

    private fun callS(obj: Any, method: String): String? {
        return try { obj.javaClass.getMethod(method).invoke(obj)?.toString() }
        catch (_: Exception) { null }
    }

    // ================================================================
    // Display Setting — add an item to Pixiv's NavigationView sidebar
    // ================================================================

    private fun installDisplaySetting(classLoader: ClassLoader) {
        try {
            val mainClass = classLoader.loadClass(MAIN_ACTIVITY)
            val onCreate = mainClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            hook(onCreate).setPriority(PRIORITY_LOWEST).intercept { chain ->
                chain.proceed()
                val activity = chain.thisObject as Activity
                val nav = findNavigationView(activity.window.decorView)
                if (nav == null) {
                    Log.e(TAG, "Display: NavigationView not found")
                } else {
                    addDisplayMenuItem(activity, nav)
                }
            }
            Log.i(TAG, "Display: sidebar item hook installed")
        } catch (e: Throwable) {
            Log.e(TAG, "Display hook FAILED: ${e.message}", e)
        }
    }

    private fun findNavigationView(root: View?): View? {
        if (root == null) return null
        // BottomNavigationView also contains "NavigationView" in its class name.
        if (root.javaClass.name == "com.google.android.material.navigation.NavigationView") return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findNavigationView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun addDisplayMenuItem(activity: Activity, nav: View) {
        try {
            val menu = nav.javaClass.getMethod("getMenu").invoke(nav) as? Menu ?: return
            if (menu.findItem(MODULE_SETTINGS_MENU_ID) == null) {
                val item: MenuItem = menu.add(
                    Menu.NONE,
                    MODULE_SETTINGS_MENU_ID,
                    999,
                    str(activity, R.string.module_settings, "Module settings")
                )
                item.setIcon(android.R.drawable.ic_menu_manage)
                item.setOnMenuItemClickListener {
                    closeDrawer(nav)
                    val resources = moduleResources(activity)
                    if (resources == null) {
                        toast(
                            activity,
                            str(activity, R.string.failed_format, "Failed: %1\$s", "resources")
                        )
                    } else {
                        showModuleSettings(activity, activity, resources)
                    }
                    true
                }
            }
            if (menu.findItem(DISPLAY_SETTINGS_MENU_ID) == null) {
                val item: MenuItem = menu.add(
                    Menu.NONE,
                    DISPLAY_SETTINGS_MENU_ID,
                    1000,
                    str(activity, R.string.display_settings, "Display settings")
                )
                item.setIcon(android.R.drawable.ic_menu_preferences)
                item.setOnMenuItemClickListener {
                    openViewingSettings(activity)
                    closeDrawer(nav)
                    true
                }
            }
            nav.invalidate()
            Log.i(TAG, "Display: sidebar items added")
        } catch (e: Throwable) {
            Log.e(TAG, "Display: add sidebar item failed: ${e.message}", e)
        }
    }

    private fun closeDrawer(nav: View) {
        var parent = nav.parent
        while (parent != null) {
            if (parent.javaClass.name == "androidx.drawerlayout.widget.DrawerLayout") {
                try {
                    parent.javaClass.getMethod("closeDrawers").invoke(parent)
                } catch (e: Throwable) {
                    Log.e(TAG, "close drawer failed: ${e.message}", e)
                }
                return
            }
            parent = (parent as? View)?.parent
        }
    }

    private fun openViewingSettings(context: Context) {
        val activity = context.findActivity() ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.pixiv.net/settings/viewing")))
        } catch (e: Throwable) {
            Log.e(TAG, "open display settings failed: ${e.message}", e)
        }
    }

    private fun Context.findActivity(): Activity? {
        var context: Context = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

    // ================================================================
    // Trial bypass hooks
    // ================================================================

    private fun installTrialHook(bridge: DexKitBridge, cl: ClassLoader) {
        // Strategy 1: string is more unique, try first
        var m = findMethodByStr(bridge, cl,
            "core_local_preference_key_first_launch_time_millis", "long", 0)
        // Strategy 2: number search, filter out SDK noise
        if (m == null) {
            val results = bridge.findMethod {
                excludePackages("com.bytedance", "com.google", "com.android",
                    "androidx", "kotlin", "okhttp3", "retrofit2", "io", "org")
                matcher {
                    addUsingNumber(86400000L)
                    this.returnType = "long"
                    this.paramCount = 0
                }
            }
            for (r in results) {
                val cls = r.declaredClassName
                // Default-package class has no dots; also match defpackage classes
                if (!cls.contains(".")) {
                    m = r.getMethodInstance(cl)
                    break
                }
            }
        }
        // Strategy 3: direct lookup
        if (m == null) m = findMethodDirect(cl, "um9", "w", 0)

        if (m != null) { hook(m).intercept { 0L }; deoptimize(m);
            Log.i(TAG, "Trial: ${m.declaringClass.name}.${m.name}") }
        else Log.e(TAG, "Trial FAILED")
    }

    private fun installAbTestHook(bridge: DexKitBridge, cl: ClassLoader) {
        var m = findMethodByStr(bridge, cl,
            "android_ab_test_search_result_trial", "boolean", 0)
        if (m == null) m = findMethodByStr(bridge, cl,
            "] cannot be converted to a boolean.", "boolean", 0, StringMatchType.EndsWith)
        if (m == null) m = findMethodDirect(cl, "i23", "a", 0)

        if (m != null) { hook(m).intercept { true }; deoptimize(m); Log.i(TAG, "AB test: ${m.declaringClass.name}.${m.name}") }
        else Log.e(TAG, "AB test FAILED")
    }

    private fun installWorkDetailFallback(classLoader: ClassLoader) {
        try {
            val peClass = classLoader.loadClass("pe")
            var itemsField: Field? = null
            var listenerField: Field? = null
            for (f in peClass.declaredFields) {
                f.isAccessible = true
                val type = f.type
                if (type.isArray && CharSequence::class.java.isAssignableFrom(type.componentType))
                    itemsField = f
                if (DialogInterface.OnClickListener::class.java.isAssignableFrom(type))
                    listenerField = f
            }
            if (itemsField == null || listenerField == null) {
                Log.e(TAG, "WD(fb): pe fields not found"); return
            }
            val teClass = classLoader.loadClass("te")
            val peField = teClass.declaredFields.find { it.type == peClass }
            if (peField == null) { Log.e(TAG, "WD(fb): pe field not in te"); return }
            peField.isAccessible = true
            val peContextField = peClass.declaredFields
                .firstOrNull { Context::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }

            for (m in teClass.declaredMethods) {
                if (m.parameterTypes.isNotEmpty() || m.returnType == Void.TYPE) continue
                hook(m).intercept { chain ->
                    val teObj = chain.thisObject
                    val peObj = peField.get(teObj)
                    @Suppress("UNCHECKED_CAST")
                    val items = itemsField.get(peObj) as? Array<CharSequence>
                    val peContext = try {
                        peContextField?.get(peObj) as? Context
                    } catch (_: Exception) { null }
                    val detailLabel = str(peContext, R.string.view_details, "View details")
                    if (items != null && items.none { it?.toString() == detailLabel }) {
                        val origListener = listenerField.get(peObj) as? DialogInterface.OnClickListener
                        val workData = if (origListener != null) extractWorkFromListener(origListener) else null
                        if (origListener != null && workData != null && isWorkItem(workData)) {
                            val newItems = items + detailLabel
                            itemsField.set(peObj, newItems)
                            val idx = newItems.size - 1
                            listenerField.set(peObj, DialogInterface.OnClickListener { dlg, which ->
                                if (which == idx) showWorkDetailDialog(dlg, workData)
                                else origListener.onClick(dlg, which)
                            })
                        }
                    }
                    chain.proceed()
                }
            }
            Log.i(TAG, "WD(fb): hook installed")
        } catch (e: Exception) {
            Log.e(TAG, "WD(fb) FAIL: ${e.message}", e)
        }
    }

    private fun installTrialFallback(cl: ClassLoader) {
        findMethodDirect(cl, "um9", "w", 0)?.let {
            hook(it).intercept { 0L }; deoptimize(it); Log.i(TAG, "Trial(fb): um9.w()")
        } ?: Log.e(TAG, "Trial FB FAILED")
    }

    private fun installAbTestFallback(cl: ClassLoader) {
        findMethodDirect(cl, "i23", "a", 0)?.let {
            hook(it).intercept { true }; deoptimize(it); Log.i(TAG, "AB test(fb): i23.a()")
        } ?: Log.e(TAG, "AB test FB FAILED")
    }

    // ================================================================
    // DexKit helpers
    // ================================================================

    private fun findMethodByNumber(bridge: DexKitBridge, cl: ClassLoader,
        num: Number, ret: String, pc: Int): Method? {
        return bridge.findMethod {
            matcher { addUsingNumber(num); this.returnType = ret; this.paramCount = pc }
        }.firstOrNull()?.getMethodInstance(cl)
    }

    private fun findMethodByStr(bridge: DexKitBridge, cl: ClassLoader,
        str: String, ret: String, pc: Int, mt: StringMatchType = StringMatchType.Equals): Method? {
        return bridge.findMethod {
            matcher { addUsingString(str, mt); this.returnType = ret; this.paramCount = pc }
        }.firstOrNull()?.getMethodInstance(cl)
    }

    private fun findMethodDirect(cl: ClassLoader, cls: String, name: String, pc: Int): Method? {
        return try { cl.loadClass(cls).declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == pc } }
        catch (_: Exception) { null }
    }
}
