package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: fckt <file.fk> [-I <dir>]... [-o <file.ml>]

Transpiles a fckt program to OCaml. Each file is a module named after it: ratios.fk is Ratios. A
name the program uses but doesn't declare brings in its file, from the program's directory or a
directory given with -I. Without -o, writes next to the input with a .ml extension; with -o -,
writes to standard output."""

fun main(args: Array<String>) {
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
        System.err.println(if (e.pos?.file == null) "${source.path}:$e" else e.toString())
        exitProcess(1)
    }
    val target = output ?: (source.path.removeSuffix(".fk") + ".ml")
    if (target == "-") print(ocaml) else File(target).writeText(ocaml)
}

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}
