package uk.m4xy.fckt.intellij

import java.io.File

/**
 * Where a file's packages are found: its own folder, as the compiler's is for a program, then, in
 * a kiln project, the project's source folders and its libraries' interfaces. kiln writes those to
 * the project's `.kiln/paths` when it compiles it; and, in `.kiln/scopes`, a file each, folders
 * whose files see libraries too, as a plugin's tests see what they're written with.
 */
class Paths(val roots: List<File>, val libraries: List<File>, val others: List<File> = emptyList())

/** The [Paths] for [file], from the nearest folder above it with a `build.fk`. */
fun pathsOf(file: File?): Paths = withStd(ownPaths(file))

/**
 * [paths], with the standard library, fckt:std, as another project's source: unless kiln gives it
 * already, compiled, as one of the libraries, as it does in a project it's built; or it's the
 * standard library's own project, whose source has its packages.
 */
private fun withStd(paths: Paths): Paths {
    val compiled = paths.libraries.any { File(it, "unit").takeIf { u -> u.isFile }?.readText()?.trim() == "Fckt_std" }
    val own = paths.roots.any { File(it, "fckt").isDirectory }
    return if (compiled || own) paths else Paths(paths.roots, paths.libraries, paths.others + listOfNotNull(bundledStd()))
}

/** The standard library's source, which the plugin has beside it, or, in its tests, the fckt.std property says. */
fun bundledStd(): File? =
    (System.getProperty("fckt.std")?.let(::File) ?: pluginFolder()?.resolve("std"))?.takeIf { it.isDirectory }

private fun pluginFolder(): File? = try {
    com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("uk.m4xy.fckt"))?.pluginPath?.toFile()
} catch (e: Throwable) {
    null
}

private fun ownPaths(file: File?): Paths {
    val own = listOfNotNull(file?.parentFile)
    val project = generateSequence(file?.absoluteFile?.parentFile) { it.parentFile }.firstOrNull { File(it, "build.fk").isFile }
        ?: return Paths(own, emptyList())
    val paths = File(project, ".kiln/paths").takeIf { it.isFile } ?: return Paths(own, emptyList())
    val (roots, libraries) = cached(project, paths)
    val scopes = File(project, ".kiln/scopes").listFiles().orEmpty().filter { it.isFile }.map { cached(project, it) }
    val scoped = scopes.filter { (folders, _) -> folders.any { file != null && isIn(file, it) } }.flatMap { it.second }
    // The file's folder first, as its siblings are its own package's; a root that is that folder isn't twice.
    return Paths((own + roots).distinctBy { it.canonicalPath }, (libraries + scoped).distinctBy { it.canonicalPath })
}

private fun isIn(file: File, folder: File) = file.canonicalPath.startsWith(folder.canonicalPath + File.separator)

private fun cached(project: File, paths: File) = cache.compute(paths.path) { _, cached ->
    if (cached != null && cached.first == paths.lastModified()) cached else paths.lastModified() to read(project, paths)
}!!.second

/** `.kiln/paths`, read once for each time it changes. */
private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<List<File>, List<File>>>>()

private fun read(project: File, paths: File): Pair<List<File>, List<File>> {
    val lines = paths.readLines().map { it.trim() }
    fun under(prefix: String) = lines.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        .map { File(it).let { f -> if (f.isAbsolute) f else File(project, it) } }
    // An interface that's gone, as when kiln's cache was cleared, until kiln compiles it again.
    // A scope's file names its folder as its source.
    return (under("source ") + under("folder ")).filter { it.isDirectory } to under("library ").filter { File(it, "unit").isFile }
}
