package com.socialpause.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/** Only launchable packages visible to this app; package name is the stable identity. */
data class LaunchableApp(val packageName: String, val label: String)

interface AppCatalog {
    suspend fun load(): List<LaunchableApp>
}

class AndroidAppCatalog(private val context: Context) : AppCatalog {
    @Suppress("DEPRECATION")
    override suspend fun load(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm)?.toString().orEmpty().ifBlank { it.activityInfo.packageName }) }
            .distinctBy { it.packageName }
            .toList()
            .sortedWith(compareBy<LaunchableApp, String>(Collator.getInstance()) { it.label })
    }
}
