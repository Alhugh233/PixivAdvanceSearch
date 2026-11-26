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
import android.os.Build
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
        private const val DISPLAY_SETTINGS_MENU_ID = 0x4D4F4455
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

        // PID search doesn't need DexKit, install it first
        installPidSearch(cl)

        // Display settings — add item to MainActivity's NavigationView sidebar
        installDisplaySetting(cl)

        val bridge = try { DexKitBridge.create(apk) } catch (e: Throwable) {
            Log.e(TAG, "DexKit create failed: ${e.message}", e); null
        }

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
    // PID Search — toolbar button via view-tree walk (no DexKit needed)
    // ================================================================

    private val buttonInjected = hashSetOf<Int>()

    private fun installPidSearch(classLoader: ClassLoader) {
        try {
            val mainClass = classLoader.loadClass(MAIN_ACTIVITY)
            val onCreate = mainClass.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            hook(onCreate).setPriority(PRIORITY_LOWEST).intercept { chain ->
                chain.proceed()
                val a = chain.thisObject as Activity
                android.os.Handler(android.os.Looper.getMainLooper()).post { injectPidButton(a) }
            }
            Log.i(TAG, "PID: MainActivity.onCreate()")
        } catch (e: Exception) { Log.e(TAG, "PID FAIL: ${e.message}", e) }
    }

    private fun injectPidButton(activity: Activity) {
        if (!buttonInjected.add(System.identityHashCode(activity))) return
        try {
            val toolbar = findToolbar(activity.window.decorView) ?: return
            val btn = TextView(activity).apply {
                text = str(activity, R.string.pid_button, "PID")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#1565C0"))
                gravity = Gravity.CENTER
                setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
                setOnClickListener { showPidDialog(activity) }
            }
            toolbar.addView(btn, android.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL))
            Log.i(TAG, "PID button injected")
        } catch (e: Exception) { Log.e(TAG, "injectBtn: ${e.message}", e) }
    }

    private fun findToolbar(root: View?): ViewGroup? {
        if (root == null) return null
        if (root.javaClass.name.contains("Toolbar") && root is ViewGroup) return root
        if (root is ViewGroup)
            for (i in 0 until root.childCount) findToolbar(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun showPidDialog(activity: Activity) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 8))
        }
        val editText = EditText(activity).apply {
            hint = str(activity, R.string.pid_hint, "Enter PID (work/user ID)")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(activity, 12) }
        }
        val b1 = makeBtn(activity, str(activity, R.string.illust, "Illust"))
        val b2 = makeBtn(activity, str(activity, R.string.novel, "Novel"))
        val b3 = makeBtn(activity, str(activity, R.string.user, "User"))
        b1.setOnClickListener { go(activity, editText, ILLUST_ACTIVITY, "ILLUST_ID") }
        b2.setOnClickListener { go(activity, editText, NOVEL_ACTIVITY, "NOVEL_ID") }
        b3.setOnClickListener { go(activity, editText, USER_ACTIVITY, "USER_ID") }
        row.addView(b1); row.addView(b2); row.addView(b3)
        panel.addView(editText); panel.addView(row)

        AlertDialog.Builder(activity)
            .setTitle(str(activity, R.string.pid_search_title, "PID Search"))
            .setView(panel)
            .setNegativeButton(str(activity, R.string.cancel, "Cancel"), null).create().apply {
                show()
                getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(Color.GRAY)
            }
    }

    private fun go(a: Activity, e: EditText, cls: String, key: String) {
        val pid = e.text.toString().trim().toLongOrNull()
        if (pid == null) { toast(a, str(a, R.string.invalid_pid, "Invalid PID")); return }
        try {
            a.startActivity(Intent(a, Class.forName(cls, false, a.classLoader)).apply {
                putExtra(key, pid); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (ex: Exception) {
            toast(a, str(a, R.string.failed_format, "Failed: %1\$s", ex.message ?: ""))
        }
    }

    private fun makeBtn(a: Activity, t: String) = Button(a).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        layoutParams = LinearLayout.LayoutParams(0, dp(a, 44), 1f).apply { setMargins(dp(a, 4), 0, dp(a, 4), 0) }
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
            val navClass = classLoader.loadClass(
                "com.google.android.material.navigation.NavigationView")
            val setter = navClass.declaredMethods.firstOrNull {
                it.name == "setNavigationItemSelectedListener" && it.parameterTypes.size == 1
            } ?: run {
                Log.e(TAG, "Display: NavigationView setter not found")
                return
            }
            val listenerClass = setter.parameterTypes[0]

            hook(setter).intercept { chain ->
                val nav = chain.thisObject as View
                val original = chain.getArg(0)
                val wrapper = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                    java.lang.reflect.InvocationHandler { proxy, method, args ->
                        when (method.name) {
                            "onNavigationItemSelected" -> {
                                val item = args?.getOrNull(0) as? MenuItem
                                if (item?.itemId == DISPLAY_SETTINGS_MENU_ID) {
                                    openViewingSettings(nav.context)
                                    true
                                } else if (original != null) {
                                    method.invoke(original, item)
                                } else {
                                    false
                                }
                            }
                            "equals" -> proxy === args?.getOrNull(0)
                            "hashCode" -> System.identityHashCode(proxy)
                            "toString" -> "DisplaySettingsNavigationListener"
                            else -> null
                        }
                    }
                )
                chain.proceed(arrayOf<Any>(wrapper))
                val activity = nav.context.findActivity()
                if (activity != null && activity.javaClass.name == MAIN_ACTIVITY) {
                    addDisplayMenuItem(activity, nav)
                }
            }

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
            Log.i(TAG, "Display: sidebar hooks installed")
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
            if (menu.findItem(DISPLAY_SETTINGS_MENU_ID) != null) return
            val item: MenuItem = menu.add(
                Menu.NONE,
                DISPLAY_SETTINGS_MENU_ID,
                1000,
                str(activity, R.string.display_settings, "Display settings")
            )
            item.setIcon(android.R.drawable.ic_menu_preferences)
            nav.invalidate()
            Log.i(TAG, "Display: sidebar item added")
        } catch (e: Throwable) {
            Log.e(TAG, "Display: add sidebar item failed: ${e.message}", e)
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
