package uk.m4xy.fckt.intellij

import java.io.File

/**
 * Where a file's packages are found: its own folder, as the compiler's is for a program, then, in
 * a kiln project, the project's source folders and its libraries' interfaces. kiln writes those to
 * the project's `.kiln/paths` when it compiles it, and, for a file in its tests' folder, what only
 * the tests use, as a test library, to `.kiln/test-paths` when it tests it.
 */
class Paths(val roots: List<File>, val libraries: List<File>)

/** The [Paths] for [file], from the nearest folder above it with a `build.fk`. */
fun pathsOf(file: File?): Paths {
    val own = listOfNotNull(file?.parentFile)
    val project = generateSequence(file?.absoluteFile?.parentFile) { it.parentFile }.firstOrNull { File(it, "build.fk").isFile }
        ?: return Paths(own, emptyList())
    val paths = File(project, ".kiln/paths").takeIf { it.isFile } ?: return Paths(own, emptyList())
    val (roots, libraries) = cached(project, paths)
    val testPaths = File(project, ".kiln/test-paths").takeIf { it.isFile }
    val tests = testPaths?.let { cached(project, it) }?.takeIf { (folders, _) -> folders.any { file != null && isIn(file, it) } }?.second.orEmpty()
    // The file's folder first, as its siblings are its own package's; a root that is that folder isn't twice.
    return Paths((own + roots).distinctBy { it.canonicalPath }, (libraries + tests).distinctBy { it.canonicalPath })
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
    // A test-paths file names the tests' folder as its source.
    return (under("source ") + under("tests ")).filter { it.isDirectory } to under("library ").filter { File(it, "unit").isFile }
}
