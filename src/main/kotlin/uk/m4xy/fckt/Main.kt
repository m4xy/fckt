package uk.m4xy.fckt

import uk.m4xy.fckt.syntax.CompileError
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

private const val USAGE = """usage: fckt <file.fk> [-I <dir>]... [--other-project <dir>]... [-L <interface>]... [--separate] [--import <name>]... [-o <file.ml>]
       fckt --library <interface> -o <file.ml> [-I <dir>]... [--other-project <dir>]... [-L <interface>]... <package>...
       fckt --test <dir> --marker <annotation> --runner <module> [--fixtures <annotation>] [--runner-tests] [-I <dir>]... [--other-project <dir>]... [-L <interface>]... [--separate] [-o <file.ml>]
       fckt --standard -o <file.ml>
       fckt --version

Transpiles a fckt program to OCaml. A folder is a package, under the program's directory or one
given with -I; a capitalised file is a module, Ratios.fk declaring module Ratios, and a lower-case
one holds its package's declarations. A file uses its package's by name, and others' by importing
them: import geometry.Shapes. --import imports into each of the program's files, as if they said
so: --import kiln.* for a build script. --other-project gives a folder as -I does, of another
project's packages, whose internal declarations the program's own files don't see. Without -o, writes next to the input with a .ml
extension; with -o -, writes to standard output.

The OCaml has what the program uses of the standard library in it, unless it's compiled
separately: with --separate, or -L, it's linked with the standard library compiled on its own,
which fckt --standard writes as fckt_std.ml, and with each library -L names. fckt --library
compiles packages as a library, to the OCaml module -o names, and writes the interface that
programs using it are checked against to <interface>; a library's public functions say their
types. fckt --test compiles the tests in a folder, its functions marked with --marker's annotation,
uk.m4xy.fktests.api.Test for fk-tests's, to one program that runs each with --runner's module,
uk.m4xy.fktests.runner.Runner for fk-tests-runner's; in modules, they're in sections. With
--fixtures, a test runs inside the functions that annotation lists on it, and on its sections:
uk.m4xy.fktests.api.Fixtures for fk-tests's. Tests can't use the runner, unless they're its own:
--runner-tests. Building projects is kiln's job."""

fun main(args: Array<String>) {
    var input: String? = null
    var output: String? = null
    var library: String? = null
    var standard = false
    var separate = false
    var test: String? = null
    var runner: String? = null
    var marker: String? = null
    var runnerTests = false
    var fixtures: String? = null
    val dirs = mutableListOf<File>()
    val others = mutableListOf<File>()
    val libraries = mutableListOf<File>()
    val imports = mutableListOf<String>()
    val packages = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (val arg = args[i++]) {
            "-h", "--help" -> {
                System.err.println(USAGE)
                exitProcess(0)
            }
            "--version" -> {
                println("fckt ${version()}")
                exitProcess(0)
            }
            "-o" -> output = args.getOrNull(i++) ?: usage()
            "-I" -> dirs += File(args.getOrNull(i++) ?: usage())
            "--other-project" -> others += File(args.getOrNull(i++) ?: usage())
            "-L" -> libraries += File(args.getOrNull(i++) ?: usage())
            "--import" -> imports += args.getOrNull(i++) ?: usage()
            "--library" -> library = args.getOrNull(i++) ?: usage()
            "--test" -> test = args.getOrNull(i++) ?: usage()
            "--runner" -> runner = args.getOrNull(i++) ?: usage()
            "--marker" -> marker = args.getOrNull(i++) ?: usage()
            "--runner-tests" -> runnerTests = true
            "--fixtures" -> fixtures = args.getOrNull(i++) ?: usage()
            "--standard" -> standard = true
            "--separate" -> separate = true
            else -> when {
                arg.startsWith("-") -> usage()
                library != null -> packages += arg
                input != null -> usage()
                else -> input = arg
            }
        }
    }
    // A library's packages may come before --library.
    if (library != null && input != null) {
        packages.add(0, input)
        input = null
    }
    when {
        standard -> write(output ?: usage(), "the standard library") { compileStandardLibrary() }
        test != null -> {
            val dir = File(test)
            if (!dir.isDirectory) {
                System.err.println("fckt: no such folder: $test")
                exitProcess(2)
            }
            write(output ?: File(dir, "tests.ml").path, dir.path) { compileTests(dir.absoluteFile, marker ?: usage(), runner ?: usage(), dirs, libraries, imports, separate, runnerTests, fixtures, others) }
        }
        library != null -> {
            val target = output ?: usage()
            write(target, "the library") { compileLibrary(packages, dirs, libraries, unitName(File(target)), File(library), others) }
        }
        else -> {
            val source = File(input ?: usage())
            if (!source.isFile) {
                System.err.println("fckt: no such file: $input")
                exitProcess(2)
            }
            val roots = listOf(source.absoluteFile.parentFile) + dirs
            val target = output ?: (source.path.removeSuffix(".fk") + ".ml")
            val ocaml = write(target, source.path) {
                if (separate || libraries.isNotEmpty()) compileSeparately(source, roots, libraries, imports, others) else compile(source, roots, imports, others)
            }
            val needed = ocamlLibraries(ocaml)
            if (needed.isNotEmpty() && target != "-" && !separate && libraries.isEmpty()) {
                System.err.println("fckt: $target uses the OCaml librar${if (needed.size == 1) "y" else "ies"} ${needed.joinToString(", ")}: " +
                    "ocaml ${needed.joinToString(" ") { "-I +$it $it.cma" }} $target")
            }
        }
    }
}

/** Compiles, and writes the OCaml to [target], or standard output for `-`; errors go to standard error, naming their files, or [what]. */
private fun write(target: String, what: String, compile: () -> String): String {
    val ocaml = try {
        compile()
    } catch (e: CompileError) {
        for (error in e.errors) System.err.println(if (error.pos?.file == null) "$what:$error" else error.toString())
        exitProcess(1)
    }
    if (target == "-") print(ocaml) else File(target).writeText(ocaml)
    return ocaml
}

/** The OCaml module a file is: `uk_m4xy_units.ml` is Uk_m4xy_units. */
private fun unitName(file: File): String {
    val name = file.nameWithoutExtension
    if (!Regex("[A-Za-z][A-Za-z0-9_]*").matches(name)) {
        System.err.println("fckt: ${file.name} can't be an OCaml module's file: name it with letters, digits and _")
        exitProcess(2)
    }
    return name.replaceFirstChar { it.uppercaseChar() }
}

/**
 * fckt's version, and its build: a digest of the compiler itself, which changes with it, so what
 * one build compiled is never taken for another's.
 */
private fun version(): String {
    val version = Source::class.java.`package`?.implementationVersion ?: "dev"
    val jar = runCatching { File(Source::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
    val build = jar?.takeIf { it.isFile }?.let { f ->
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }.take(12)
    } ?: "unpackaged"
    return "$version (build $build)"
}

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}
