package `in`.shraddha.ai.operator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePickerPackagePolicyTest {
    @Test fun allowsKnownAndroidAndOemFilePickers() {
        assertTrue(FilePickerPackagePolicy.allows("com.android.documentsui"))
        assertTrue(FilePickerPackagePolicy.allows("com.google.android.documentsui"))
        assertTrue(FilePickerPackagePolicy.allows("com.google.android.providers.media.module"))
        assertTrue(FilePickerPackagePolicy.allows("com.coloros.filemanager"))
    }

    @Test fun rejectsArbitraryAppsAndBlankPackages() {
        assertFalse(FilePickerPackagePolicy.allows("com.whatsapp"))
        assertFalse(FilePickerPackagePolicy.allows("com.instagram.android"))
        assertFalse(FilePickerPackagePolicy.allows(""))
    }
}
