package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: fckt <file.fk> [-I <dir>]... [-o <file.ml>]
       fckt <task>... [-- <args>]

Builds the project whose build.fk is in this folder: fckt build, test, run, clean, compile, or
tasks to list them. build.fk declares project = Project(name = "...") with import fckt.build.*,
and may declare tasks = standardTasks + [Task(...)]; what follows -- goes to the program, for run.


Transpiles a fckt program to OCaml. A folder is a package, under the program's directory or one
given with -I; a capitalised file is a module, Ratios.fk declaring module Ratios, and a lower-case
one holds its package's declarations. A file uses its package's by name, and others' by importing
them: import geometry.Shapes. Without -o, writes next to the input with a .ml extension; with
-o -, writes to standard output."""

fun main(args: Array<String>) {
    // A task's name, not a file: build the project here.
    if (args.isNotEmpty() && !args[0].startsWith("-") && !args[0].endsWith(".fk")) exitProcess(build(File("."), args.toList()))
    var input: String? = null
    var output: String? = null
    val dirs = mutableListOf<File>()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i++]) {
            "-h", "--help" -> {
                System.err.println(USAGE)
                exitProcess(0)
            }
            "-o" -> output = args.getOrNull(i++) ?: usage()
            "-I" -> dirs += File(args.getOrNull(i++) ?: usage())
            else -> if (arg.startsWith("-") || input != null) usage() else input = arg
        }
    }
    if (input == null) usage()
    val source = File(input)
    if (!source.isFile) {
        System.err.println("fckt: no such file: $input")
        exitProcess(2)
    }
    val ocaml = try {
        compile(source, listOf(source.absoluteFile.parentFile) + dirs)
    } catch (e: CompileError) {
        // Errors in other files name their file; the program's own don't.
        for (error in e.errors) System.err.println(if (error.pos?.file == null) "${source.path}:$error" else error.toString())
        exitProcess(1)
    }
    val target = output ?: (source.path.removeSuffix(".fk") + ".ml")
    if (target == "-") print(ocaml) else File(target).writeText(ocaml)
    val libraries = ocamlLibraries(ocaml)
    if (libraries.isNotEmpty() && target != "-") {
        System.err.println("fckt: $target uses the OCaml librar${if (libraries.size == 1) "y" else "ies"} ${libraries.joinToString(", ")}: " +
            "ocaml ${libraries.joinToString(" ") { "-I +$it $it.cma" }} $target")
    }
}

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

/**
 * Runs the tasks [args] names on the project in [dir]: its build.fk, with a main that hands
 * project to fckt.build's runBuild, compiled to `.fckt/build.ml` when it's changed, and run with
 * ocaml. Gives the exit code.
 */
private fun build(dir: File, args: List<String>): Int {
    val script = File(dir, "build.fk")
    if (!script.isFile) {
        System.err.println("fckt: there's no build.fk here; fckt <file.fk> compiles a single file")
        return 2
    }
    val text = script.readText()
    val declared = try {
        uk.m4xy.fckt.syntax.Parser.parse(text).decls.map { it.name }.toSet()
    } catch (e: CompileError) {
        for (error in e.errors) System.err.println("${script.path}:$error")
        return 1
    }
    if ("main" in declared) {
        System.err.println("${script.path}: build.fk declares project, and fckt runs it: it can't have a main")
        return 1
    }
    if ("project" !in declared) {
        System.err.println("${script.path}: build.fk must declare project = Project(name = \"...\"), with import fckt.build.*")
        return 1
    }
    val tasks = if ("tasks" in declared) ", tasks" else ""
    val program = "$text\n\nmain(args: List<String>) = fckt.build.Builds.runBuild(project, args$tasks)\n"
    val cache = File(dir, ".fckt").apply { mkdirs() }
    val ml = File(cache, "build.ml")
    val stamp = File(cache, "build.stamp")
    // Compiled again when the script or the compiler changes.
    val key = "${program.hashCode()}:${home()?.let { File(it, "lib").listFiles()?.sumOf { f -> f.lastModified() } }}"
    if (!ml.isFile || !stamp.isFile || stamp.readText() != key) {
        val ocaml = try {
            compile(program, script, listOf(dir.absoluteFile))
        } catch (e: CompileError) {
            for (error in e.errors) System.err.println(if (error.pos?.file == null) "${script.path}:$error" else error.toString())
            return 1
        }
        ml.writeText(ocaml)
        stamp.writeText(key)
    }
    val command = listOf("ocaml", "-I", "+unix", "unix.cma", ml.path) + args
    val process = try {
        ProcessBuilder(command).directory(dir).inheritIO()
            .apply { environment()["FCKT_COMPILER"] = home()?.let { File(it, "bin/fckt").path } ?: "fckt" }
            .start()
    } catch (e: java.io.IOException) {
        System.err.println("fckt: can't run ocaml, which runs build.fk: is OCaml installed?")
        return 2
    }
    return process.waitFor()
}

/** Where fckt is installed: the folder with bin/fckt and lib, if it's run from there. */
private fun home(): File? {
    val jar = File(CompileError::class.java.protectionDomain.codeSource.location.toURI())
    return jar.parentFile?.parentFile?.takeIf { File(it, "bin/fckt").isFile }
}
