package uk.m4xy.fckt.intellij

import java.io.File

/**
 * Where a file's packages are found: its own folder, as the compiler's is for a program, then, in
 * a kiln project, the project's source folders and its libraries' interfaces. kiln writes those to
 * the project's `.kiln/paths` when it compiles or tests it.
 */
class Paths(val roots: List<File>, val libraries: List<File>)

/** The [Paths] for [file], from the nearest folder above it with a `build.fk`. */
fun pathsOf(file: File?): Paths {
    val own = listOfNotNull(file?.parentFile)
    val project = generateSequence(file?.absoluteFile?.parentFile) { it.parentFile }.firstOrNull { File(it, "build.fk").isFile }
        ?: return Paths(own, emptyList())
    val paths = File(project, ".kiln/paths").takeIf { it.isFile } ?: return Paths(own, emptyList())
    return cache.compute(paths.path) { _, cached ->
        if (cached != null && cached.first == paths.lastModified()) cached else paths.lastModified() to read(project, paths)
    }!!.second.let { (roots, libraries) ->
        // The file's folder first, as its siblings are its own package's; a root that is that folder isn't twice.
        Paths((own + roots).distinctBy { it.canonicalPath }, libraries)
    }
}

/** `.kiln/paths`, read once for each time it changes. */
private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<List<File>, List<File>>>>()

private fun read(project: File, paths: File): Pair<List<File>, List<File>> {
    val lines = paths.readLines().map { it.trim() }
    fun under(prefix: String) = lines.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        .map { File(it).let { f -> if (f.isAbsolute) f else File(project, it) } }
    // An interface that's gone, as when kiln's cache was cleared, until kiln compiles it again.
    return under("source ").filter { it.isDirectory } to under("library ").filter { File(it, "unit").isFile }
}
