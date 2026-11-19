package `in`.shraddha.ai.operator.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

/** Finds files only in Android shared media/documents the user has granted this app access to. */
class DeviceFileLocator(private val context: Context) {
    data class LocatedFile(val displayName: String, val mimeType: String, val uri: Uri, val source: String, val relativePath: String = "")
    data class SearchResult(val matches: List<LocatedFile>, val permissionRequired: Boolean, val message: String)

    fun search(fileName: String, folderHint: String = ""): SearchResult {
        val wanted = fileName.substringAfterLast('/').trim()
        if (wanted.isBlank()) return SearchResult(emptyList(), false, "फ़ाइल का नाम दें।")
        val found = linkedMapOf<String, LocatedFile>()
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.RELATIVE_PATH)
        val collection = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        var permissionRequired = false
        try {
            context.contentResolver.query(collection, projection, selection, arrayOf(wanted), null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val pathCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val path = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    if (folderHint.isNotBlank() && !path.contains(folderHint, true)) continue
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    found[uri.toString()] = LocatedFile(name, cursor.getString(mimeCol).orEmpty(), uri, "MediaStore", path)
                }
            }
        } catch (_: SecurityException) {
            permissionRequired = true
        } catch (_: Exception) { }

        // Search only folders explicitly granted by the user through the Storage Access Framework.
        val prefs = context.getSharedPreferences("operator_file_access", Context.MODE_PRIVATE)
        prefs.getStringSet("tree_uris", emptySet()).orEmpty().forEach { raw ->
            try {
                val tree = Uri.parse(raw)
                val root = DocumentFile.fromTreeUri(context, tree) ?: return@forEach
                walk(root, wanted, folderHint, found, 0, intArrayOf(0))
            } catch (_: Exception) { }
        }
        val matches = found.values.toList()
        return SearchResult(matches, permissionRequired, when (matches.size) { 0 -> if (permissionRequired) "Media permission दें या फ़ोल्डर को SAF से अनुमति दें; अभी फ़ाइल नहीं मिली।" else "अनुमति प्राप्त स्थानों में यह फ़ाइल नहीं मिली।"; 1 -> "एक मिलती हुई फ़ाइल मिली।"; else -> "एक से अधिक समान नाम की फ़ाइलें मिलीं; सही फ़ाइल चुनें।" })
    }

    fun rememberGrantedTree(uri: Uri, grantedFlags: Int) {
        val flags = grantedFlags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val prefs = context.getSharedPreferences("operator_file_access", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("tree_uris", emptySet()).orEmpty().toMutableSet(); set.add(uri.toString())
        prefs.edit().putStringSet("tree_uris", set).apply()
    }

    private fun walk(dir: DocumentFile, wanted: String, folderHint: String, out: MutableMap<String, LocatedFile>, depth: Int, visited: IntArray) {
        if (depth > 8 || out.size > 100 || visited[0] > 5000) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        children.forEach { child ->
            visited[0]++
            if (visited[0] > 5000) return@forEach
            if (child.isDirectory) walk(child, wanted, folderHint, out, depth + 1, visited)
            else if (child.name.equals(wanted, true) && (folderHint.isBlank() || child.uri.toString().contains(folderHint, true) || dir.name.orEmpty().contains(folderHint, true))) {
                out[child.uri.toString()] = LocatedFile(child.name.orEmpty(), child.type.orEmpty(), child.uri, "Granted folder", dir.name.orEmpty())
            }
        }
    }
}
