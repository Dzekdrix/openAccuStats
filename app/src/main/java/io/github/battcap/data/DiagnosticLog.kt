package io.github.battcap.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal local des événements du suivi (tâche déclenchée, service démarré, refus d'Android…).
 * Sert à comprendre pourquoi une charge n'a pas été mesurée. Reste sur le téléphone :
 * l'utilisateur peut le copier depuis les Paramètres pour le partager s'il le souhaite.
 */
object DiagnosticLog {
    private const val PREFS = "diagnostic"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 150
    private val format = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun log(context: Context, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lines = prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }
        val updated = (lines + "${format.format(Date())}  $message").takeLast(MAX_ENTRIES)
        prefs.edit().putString(KEY, updated.joinToString("\n")).apply()
    }

    /** Entrées, de la plus récente à la plus ancienne. */
    @Synchronized
    fun entries(context: Context): List<String> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }.reversed()

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** Journalise une exception attrapée (type, message, 3 premières lignes de la pile). */
    fun logError(context: Context, where: String, t: Throwable) {
        val top = t.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        log(context, "ERREUR [$where] ${t.javaClass.simpleName}: ${t.message} — $top")
    }
}
