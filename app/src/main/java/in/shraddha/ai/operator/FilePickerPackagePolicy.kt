package `in`.shraddha.ai.operator

/** Allow file selection only while a known Android document/photo picker owns the foreground UI. */
object FilePickerPackagePolicy {
    private val allowedPackages = setOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.google.android.providers.media.module",
        "com.google.android.photopicker",
        "com.coloros.filemanager",
        "com.oplus.filemanager"
    )

    fun allows(packageName: String): Boolean =
        packageName.trim().lowercase() in allowedPackages
}
