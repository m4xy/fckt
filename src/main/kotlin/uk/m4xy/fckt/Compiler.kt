package uk.m4xy.fckt

import uk.m4xy.fckt.ocaml.Lowering
import uk.m4xy.fckt.ocaml.Printer
import uk.m4xy.fckt.syntax.Parser
import uk.m4xy.fckt.types.Checker
import uk.m4xy.kastor.resolve

/** fckt source to OCaml source. Throws [uk.m4xy.fckt.syntax.CompileError]. */
fun compile(source: String): String {
    val program = Checker(Parser.parse(source)).check()
    val tree = Lowering(program).lower()
    return Printer.print(tree.resolve(Unit))
}
