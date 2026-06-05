package com.pixiv.pixiv.advancesearch

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
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
        if (param.packageName != PIXIV) return

        val cl = param.defaultClassLoader
        val apk = param.applicationInfo.sourceDir
        Log.i(TAG, "APK: $apk")

        // PID search doesn't need DexKit, install it first
        installPidSearch(cl)

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
                text = "PID"
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
            hint = "输入 PID (作品/用户 ID)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK); setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(activity, 12) }
        }
        val b1 = makeBtn(activity, "插画"); val b2 = makeBtn(activity, "小说"); val b3 = makeBtn(activity, "用户")
        b1.setOnClickListener { go(activity, editText, ILLUST_ACTIVITY, "ILLUST_ID") }
        b2.setOnClickListener { go(activity, editText, NOVEL_ACTIVITY, "NOVEL_ID") }
        b3.setOnClickListener { go(activity, editText, USER_ACTIVITY, "USER_ID") }
        row.addView(b1); row.addView(b2); row.addView(b3)
        panel.addView(editText); panel.addView(row)

        AlertDialog.Builder(activity).setTitle("PID 搜索").setView(panel)
            .setNegativeButton("取消", null).create().apply {
                show()
                getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(Color.GRAY)
            }
    }

    private fun go(a: Activity, e: EditText, cls: String, key: String) {
        val pid = e.text.toString().trim().toLongOrNull()
        if (pid == null) { toast(a, "无效 PID"); return }
        try {
            a.startActivity(Intent(a, Class.forName(cls, false, a.classLoader)).apply {
                putExtra(key, pid); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (ex: Exception) { toast(a, "失败: ${ex.message}") }
    }

    private fun makeBtn(a: Activity, t: String) = Button(a).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        layoutParams = LinearLayout.LayoutParams(0, dp(a, 44), 1f).apply { setMargins(dp(a, 4), 0, dp(a, 4), 0) }
    }

    private fun toast(a: Activity, m: String) { Toast.makeText(a, m, Toast.LENGTH_SHORT).show() }
    private fun dp(c: android.content.Context, d: Int) = (d * c.resources.displayMetrics.density).toInt()

    // ================================================================
    // Work Detail — fully DexKit-based discovery + hook
    //
    // Chain: layout_inflater → pe class → pe.p / pe.r → te.create()
    // Hook te.create() BEFORE proceed: append "查看详情" to pe.p,
    // wrap pe.r to handle the new item → show work info dialog.
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

            // Step 4+5: Hook all 0-param non-void te methods.
            val peField = teClass.declaredFields.find { it.type == peClass }
            if (peField == null) { Log.e(TAG, "WD: pe field not found in te"); return false }
            peField.isAccessible = true
            val safeItems = itemsField!!
            val safeListener = listenerField!!
            for (createMethod in teClass.declaredMethods) {
                if (createMethod.parameterTypes.isNotEmpty() || createMethod.returnType == Void.TYPE) continue
                hook(createMethod).intercept { chain ->
                    val teObj = chain.thisObject
                    val peObj = peField.get(teObj)
                    @Suppress("UNCHECKED_CAST")
                    val items = safeItems.get(peObj) as? Array<CharSequence>

                    if (items != null) {
                        val origListener = safeListener.get(peObj) as? DialogInterface.OnClickListener
                        val workData = if (origListener != null) extractWorkFromListener(origListener) else null

                        val newItems = items + "查看详情"
                        safeItems.set(peObj, newItems)

                        val detailIdx = newItems.size - 1
                        safeListener.set(peObj, DialogInterface.OnClickListener { dlg, which ->
                            if (which == detailIdx && workData != null)
                                showWorkDetailDialog(dlg, workData)
                            else if (origListener != null)
                                origListener.onClick(dlg, which)
                        })
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
        // c0 has: a(int) b(Object/io8) c(Object/ho8) d(Serializable/rz6)
        // The work item is in field 'd' (the only Serializable-typed field)
        for (f in listener.javaClass.declaredFields) {
            if (Serializable::class.java.isAssignableFrom(f.type)) {
                f.isAccessible = true
                return f.get(listener)
            }
        }
        return null
    }

    private fun showWorkDetailDialog(dialog: DialogInterface, work: Any) {
        try {
            val clz = work.javaClass
            val workPid = callS(work, "getId")
            val title = callS(work, "getTitle")
            val user = callO(work, "getUser")
            val userName = if (user != null) {
                try {
                    user.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(user)?.toString() ?: "—"
                } catch (_: Exception) { "—" }
            } else "—"
            val userPid = if (user != null) {
                try {
                    user.javaClass.declaredFields.find { it.type == java.lang.Long.TYPE }
                        ?.apply { isAccessible = true }?.get(user)?.toString() ?: "—"
                } catch (_: Exception) { "—" }
            } else "—"

            val ctx = (dialog as? android.app.Dialog)?.context ?: return

            val tv = TextView(ctx).apply {
                setTextIsSelectable(true)
                text = "作品名称: $title\n作者名称: $userName\n作品 PID: $workPid\n用户 PID: $userPid"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.BLACK)
                setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8))
            }

            AlertDialog.Builder(ctx)
                .setTitle("作品详情")
                .setView(tv)
                .setPositiveButton("确定", null)
                .show()
        } catch (e: Exception) { Log.e(TAG, "showDetail: ${e.message}", e) }
    }

    private fun callS(obj: Any, method: String): String {
        return try { obj.javaClass.getMethod(method).invoke(obj)?.toString() ?: "—" }
        catch (_: Exception) { "—" }
    }

    private fun callO(obj: Any, method: String): Any? {
        return try { obj.javaClass.getMethod(method).invoke(obj) } catch (_: Exception) { null }
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

            for (m in teClass.declaredMethods) {
                if (m.parameterTypes.isNotEmpty() || m.returnType == Void.TYPE) continue
                hook(m).intercept { chain ->
                    val teObj = chain.thisObject
                    val peObj = peField.get(teObj)
                    @Suppress("UNCHECKED_CAST")
                    val items = itemsField.get(peObj) as? Array<CharSequence>
                    if (items != null) {
                        val origListener = listenerField.get(peObj) as? DialogInterface.OnClickListener
                        val workData = if (origListener != null) extractWorkFromListener(origListener) else null
                        val newItems = items + "查看详情"
                        itemsField.set(peObj, newItems)
                        val idx = newItems.size - 1
                        listenerField.set(peObj, DialogInterface.OnClickListener { dlg, which ->
                            if (which == idx && workData != null) showWorkDetailDialog(dlg, workData)
                            else if (origListener != null) origListener.onClick(dlg, which)
                        })
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
