package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: fckt <file.fk> [-o <file.ml>]

Transpiles a fckt source file to OCaml. Without -o, writes next to the input with a .ml extension;
with -o -, writes to standard output."""

fun main(args: Array<String>) {
    val input = args.firstOrNull { !it.startsWith("-") && it != args.getOrNull(args.indexOf("-o") + 1).takeIf { "-o" in args } }
    if (input == null || "-h" in args || "--help" in args) {
        System.err.println(USAGE)
        exitProcess(if (input == null) 2 else 0)
    }
    val source = File(input)
    if (!source.isFile) {
        System.err.println("fckt: no such file: $input")
        exitProcess(2)
    }
    val output = if ("-o" in args) args.getOrNull(args.indexOf("-o") + 1) ?: run {
        System.err.println(USAGE)
        exitProcess(2)
    } else source.path.removeSuffix(".fk") + ".ml"

    val ocaml = try {
        compile(source.readText())
    } catch (e: CompileError) {
        System.err.println("${source.path}:$e")
        exitProcess(1)
    }
    if (output == "-") print(ocaml) else File(output).writeText(ocaml)
}
