package jp.cn22.yomipace

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** AndroidX uses '/' for Android canonical paths. Keep the same containment check on Windows hosts.
 * Only this private comparison is adapted: the real provider, XML roots, URI generation and file reads run.
 */
@Implements(className = "androidx.core.content.FileProvider\$SimplePathStrategy", isInAndroidSdk = false)
class HostFileProviderPaths {
    @Implementation
    protected fun belongsToRoot(path: String, root: String): Boolean {
        val child = path.replace('\\', '/').trimEnd('/')
        val parent = root.replace('\\', '/').trimEnd('/')
        return child == parent || child.startsWith("$parent/")
    }
}
