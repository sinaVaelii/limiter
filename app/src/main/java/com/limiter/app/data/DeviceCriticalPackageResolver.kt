package com.limiter.app.data

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * Production [CriticalPackageResolver] that dynamically resolves
 * protected packages from the actual device state.
 *
 * Default-deny for system/preinstalled packages.
 * Only non-system user-installed apps are eligible targets.
 */
class DeviceCriticalPackageResolver(
    private val context: Context
) : CriticalPackageResolver {

    @Volatile
    private var cachedProtected: Set<String>? = null
    private val cacheLock = Any()

    override fun resolveProtected(): Set<String> {
        val cached = cachedProtected
        if (cached != null) return cached
        synchronized(cacheLock) {
            val cachedInner = cachedProtected
            if (cachedInner != null) return cachedInner
            val resolved = doResolveProtected()
            cachedProtected = resolved
            return resolved
        }
    }

    override fun invalidateCache() {
        cachedProtected = null
    }

    private fun doResolveProtected(): Set<String> {
        val pm = context.packageManager
        val protected = mutableSetOf<String>()

        // Limiter itself
        protected.add(context.packageName)

        // Android framework
        protected.add("android")

        // SystemUI
        protected.add("com.android.systemui")

        // Settings
        protected.add("com.android.settings")
        protected.add("com.xiaomi.misettings")

        // Package installer
        protected.add("com.android.packageinstaller")
        protected.add("com.google.android.packageinstaller")
        protected.add("com.miui.global.packageinstaller")

        // Permission controller
        protected.add("com.google.android.permissioncontroller")
        protected.add("com.android.permissioncontroller")
        protected.add("com.miui.permissioncontroller.overlay")

        // MIUI Security Center & Protection
        protected.add("com.miui.securitycenter")
        protected.add("com.miui.securitycore")
        protected.add("com.miui.securityadd")

        // Active launcher (HOME)
        resolveDefaultActivity(
            pm,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        )?.let { protected.add(it) }

        // Default dialer
        resolveDefaultActivity(
            pm,
            Intent(Intent.ACTION_DIAL)
        )?.let { protected.add(it) }

        // Telecom/phone packages
        protected.add("com.android.server.telecom")
        protected.add("com.android.phone")
        protected.add("com.xiaomi.phone")

        // Emergency services
        protected.add("com.android.emergency")
        protected.add("com.google.android.emergency")

        // Default SMS handler
        try {
            val smsPkg = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
            smsPkg?.let { protected.add(it) }
        } catch (_: Exception) { /* not available on all devices */ }

        // Active IME
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.enabledInputMethodList?.forEach { imi ->
            protected.add(imi.packageName)
        }

        // Enabled accessibility services
        val accessibilityString = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        accessibilityString?.split(":")?.forEach { component ->
            val pkg = component.substringBefore("/")
            if (pkg.isNotEmpty()) protected.add(pkg)
        }

        // Active VPN & registered VPN providers
        try {
            val vpnIntent = Intent(android.net.VpnService.SERVICE_INTERFACE)
            pm.queryIntentServices(vpnIntent, 0).forEach { resolveInfo ->
                resolveInfo.serviceInfo?.packageName?.let { protected.add(it) }
            }
        } catch (_: Exception) { /* queryIntentServices failure fallback */ }

        // WebView provider
        try {
            val webViewPkg = WebView.getCurrentWebViewPackage()
            webViewPkg?.packageName?.let { protected.add(it) }
        } catch (_: Exception) { /* no webview? */ }

        // GMS / GSF
        protected.add("com.google.android.gms")
        protected.add("com.google.android.gsf")

        // Package verifier
        try {
            val verifierIntent = Intent("android.intent.action.PACKAGE_NEEDS_VERIFICATION")
            verifierIntent.type = "application/vnd.android.package-archive"
            pm.queryBroadcastReceivers(verifierIntent, 0).forEach { resolveInfo ->
                resolveInfo.activityInfo?.packageName?.let { protected.add(it) }
            }
        } catch (_: Exception) { /* package verifier query failure fallback */ }

        return protected
    }

    override fun isEligibleTarget(packageName: String): Boolean =
        isEligibleTarget(packageName, allowSystemApps = false)

    override fun isEligibleTarget(packageName: String, allowSystemApps: Boolean): Boolean {
        // Essential recovery and protected packages are strictly NEVER eligible targets
        if (packageName in resolveProtected()) {
            return false
        }

        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            if (!appInfo.enabled) {
                return false
            }

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !allowSystemApps) {
                return false
            }

            // Must expose a user-launchable activity
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            val activities = pm.queryIntentActivities(launcherIntent, 0)
            activities.isNotEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            // Unexpected PackageManager exceptions: safely return false
            false
        }
    }

    private fun resolveDefaultActivity(pm: PackageManager, intent: Intent): String? {
        val resolveInfo = try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            null
        }
        return resolveInfo?.activityInfo?.packageName
    }
}
