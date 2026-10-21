package com.gdhapp.client.i18n

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.gdhapp.client.domain.model.AppLanguage
import java.util.Locale

/** Shared Android string resources for Compose, notifications and background errors. */
@SuppressLint("StaticFieldLeak") // Only the application and its locale configuration context are cached.
object AppText {
    @Volatile private var application: Context? = null
    @Volatile private var language = AppLanguage.SYSTEM
    private var cachedTag: String? = null
    private var cachedContext: Context? = null
    val locale: Locale get() = language.tag.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()

    @Synchronized fun initialize(context: Context, selected: AppLanguage) {
        val base = context.applicationContext
        if (application !== base || language != selected) { cachedContext = null; cachedTag = null }
        application = base; language = selected
    }
    @Synchronized fun setLanguage(selected: AppLanguage) {
        if (selected != language) { language = selected; cachedContext = null; cachedTag = null }
    }
    fun wrap(context: Context): Context {
        if (language == AppLanguage.SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
    @Synchronized fun text(@StringRes id: Int, vararg args: Any?): String {
        // Pure JVM parser tests do not create an Android Application.
        val base = application ?: return "[$id]"
        val tag = locale.toLanguageTag()
        if (cachedContext == null || cachedTag != tag) { cachedContext = wrap(base); cachedTag = tag }
        val resources = checkNotNull(cachedContext).resources
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }
}
