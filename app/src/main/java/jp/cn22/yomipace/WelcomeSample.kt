package jp.cn22.yomipace

import android.app.Application
import jp.cn22.yomipace.core.ZipImporter
import java.io.File
import java.io.InputStream

/** Installs one ordinary, editable/deletable deck only for a new app data directory. */
internal object WelcomeSample {
    const val SETUP_COMPLETE = "welcomeSampleSetupComplete"
    private const val INSTALL_PENDING = "welcomeSampleInstallPending"
    const val ID = "deck-00000000-0000-4000-8000-000000000001"
    const val NAME = "YomiPace はじめてガイド（10枚）"
    const val ASSET = "welcome/YomiPace-welcome.zip"

    /** Record a fresh installation before onStop can persist default display settings. */
    @Synchronized
    fun prepare(application: Application) {
        val prefs = application.getSharedPreferences("yomipace", 0)
        val root = File(application.filesDir, "decks")
        if (prefs.all.isEmpty() && root.listFiles().orEmpty().all { it.name == ID }) {
            check(prefs.edit().putBoolean(INSTALL_PENDING, true).commit()) { "初回設定を保存できませんでした。" }
        }
    }

    @Synchronized
    fun installIfNew(application: Application, openAsset: () -> InputStream = { application.assets.open(ASSET) }) {
        prepare(application)
        val prefs = application.getSharedPreferences("yomipace", 0)
        if (prefs.getBoolean(SETUP_COMPLETE, false)) return
        val root = File(application.filesDir, "decks").apply { mkdirs() }
        val store = DeckStore(root)
        val directory = store.directory(ID)
        // An update must preserve even a deliberately cleared presentation or an archive with no active deck.
        val pending = prefs.getBoolean(INSTALL_PENDING, false)
        val active = prefs.getString("active", null)
        val existingData = (!pending && prefs.all.isNotEmpty()) || root.listFiles().orEmpty().any { it.name != ID } ||
            (active != null && active != ID)
        val sampleInTrash = directory.exists() && runCatching { store.read(ID).location == DeckLocation.TRASH }.getOrDefault(false)
        if (existingData || sampleInTrash) {
            check(prefs.edit().putBoolean(SETUP_COMPLETE, true).remove(INSTALL_PENDING).commit()) { "初回設定を保存できませんでした。" }
            return
        }
        try {
            check(prefs.edit().putBoolean(INSTALL_PENDING, true).commit()) { "初回設定を保存できませんでした。" }
            // A fixed ID lets an interrupted installation retry without making duplicate archive entries.
            val stored = runCatching { store.load(ID) }.getOrNull() ?: run {
                if (directory.exists()) check(directory.deleteRecursively()) { "サンプルの準備を再開できませんでした。" }
                val imported = ZipImporter.read(openAsset(), directory, NAME)
                openAsset().use { input -> File(directory, "source.zip").outputStream().use { input.copyTo(it) } }
                val now = System.currentTimeMillis()
                store.write(DeckEntry(ID, NAME, imported.images.size, now, now, totalSeconds = imported.script.declaredSeconds))
                store.load(ID)
            }
            check(prefs.edit().putBoolean(SETUP_COMPLETE, true).remove(INSTALL_PENDING).putString("active", ID).putString("name", NAME)
                .putString("total", stored.entry.totalSeconds?.toString()).putString("anchor", "h1")
                .putInt("offset", 0).putLong("elapsed", 0).commit()) { "初回設定を保存できませんでした。" }
        } catch (e: Exception) {
            // Only this installer-owned directory is removed; ordinary imported decks are untouched.
            directory.deleteRecursively()
            throw e
        }
    }
}
