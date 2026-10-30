# fckt.lowering

Turning what `fckt.checker` has checked into OCaml, written in fckt: the last part of a compiler
that compiles itself. It's meant to be read, as the checker is.

| Package | What it's for |
|---|---|
| `fckt.ocaml` | OCaml as a tree, named as OCaml's manual names it: `Expr`, `Pat`, `Typexpr` and `Item`; and `Printer`, its text, with parentheses where OCaml needs them, by how tightly each construct binds and whether one that runs on to the right, as `let` and `match` do, has something after it. |
| `fckt.lowering` | A checked program's core, as OCaml. `Programs` puts a program together: the helpers it calls, the standard library's packages by the names its code writes them by, its own functions, group by group, each after what it uses, and `main`, run in each handled effect's default handler. `Expressions` turns core into OCaml, running arguments in the order they're written; `Shows` shows a value of each type as text; `Constructors` builds and matches records and cases; `TypeItems` declares the program's types and their show functions; `OcamlTypes` writes fckt's types as OCaml's; `Externals` gives an external's OCaml its arguments; `HandledEffects` performs operations and makes handlers; `Naming` names everything as fckt's own compiler does, so what each writes can be linked with the other's. |

## Where it's got to

It lowers a program and its own project's packages, to be linked with the core and the standard
library as fckt's own compiler compiles them on their own: its types, with a show function for each,
cases shared between types as polymorphic variants; its handled effects; its specs, as module types;
its modules, and each module a functor makes, as a copy of the functor's with the modules given;
modules as values of their specs; and its functions, with constants, names, calls, externals,
extensions, operations, handlers, operators, string templates, lists, tuples, lambdas, if, when and
its patterns, blocks, vars, changing fields, copies, pipes and assertions. Every conformance case
that prints prints what it should, and it compiles itself: see `fckt.compiler`. What it can't lower
yet, it fails on, saying what, as a module's block, Options { ... }.

## Tests

`test/` holds unit tests of the printer, which `kiln build` runs. fckt's `LoweringTest` holds it to
the compiler: each conformance case that prints is lowered by `src/test/fckt/lowered.fk`, compiled
with the core and the standard library this compiler compiles on its own, and run, and must print
what the case says; a case it can't lower yet is skipped, saying why.
