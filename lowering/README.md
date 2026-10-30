# fckt.lowering

Turning what `fckt.checker` has checked into OCaml, written in fckt: the last part of a compiler
that compiles itself. It's meant to be read, as the checker is.

| Package | What it's for |
|---|---|
| `fckt.ocaml` | OCaml as a tree, named as OCaml's manual names it: `Expr`, `Pat`, `Typexpr` and `Item`; and `Printer`, its text, with parentheses where OCaml needs them, by how tightly each construct binds and whether one that runs on to the right, as `let` and `match` do, has something after it. |
| `fckt.lowering` | A checked program's core, as OCaml. `Programs` puts a program together: the helpers it calls, the standard library's packages by the names its code writes them by, its own functions, group by group, each after what it uses, and `main`, run in each handled effect's default handler. `Expressions` turns core into OCaml, running arguments in the order they're written; `Shows` shows a value of each type as text; `Constructors` builds and matches records and cases; `TypeItems` declares the program's types and their show functions; `OcamlTypes` writes fckt's types as OCaml's; `Externals` gives an external's OCaml its arguments; `HandledEffects` performs operations and makes handlers; `Naming` names everything as fckt's own compiler does, so what each writes can be linked with the other's. |

## Where it's got to

It lowers a program's own file, to be linked with the core and the standard library as fckt's own
compiler compiles them on their own: its types, with a show function for each; its handled
effects; its modules; and its functions, with constants, names, calls, externals, extensions,
operations, handlers, operators, string templates, lists, tuples, lambdas, if, when and its
patterns, blocks, vars, changing fields, pipes and assertions. 40 of the 44 conformance cases that
print print what they should. What it can't lower yet, it fails on, saying what: functors and
specs' modules used as values, and cases shared between types, which OCaml has as polymorphic
variants.

## Tests

`test/` holds unit tests of the printer, which `kiln build` runs. fckt's `LoweringTest` holds it to
the compiler: each conformance case that prints is lowered by `src/test/fckt/lowered.fk`, compiled
with the core and the standard library this compiler compiles on its own, and run, and must print
what the case says; a case it can't lower yet is skipped, saying why.
