package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: fckt <file.fk> [-I <dir>]... [-o <file.ml>]

Transpiles a fckt program to OCaml. A folder is a package, under the program's directory or one
given with -I; a capitalised file is a module, Ratios.fk declaring module Ratios, and a lower-case
one holds its package's declarations. A file uses its package's by name, and others' by importing
them: import geometry.Shapes. Without -o, writes next to the input with a .ml extension; with
-o -, writes to standard output."""

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
