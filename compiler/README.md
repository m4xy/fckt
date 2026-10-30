# fckt.compiler

fckt's compiler, written in fckt: `fckt.parser`, `fckt.checker` and `fckt.lowering`, as a command.

```
kiln build
build/bin/compiler [--whole] <file.fk> <out.ml> [<folder>...]
```

compiles the program in the file, with the packages in each folder as its own, to OCaml, which is
linked with the core and the standard library as fckt's compiler compiles them on their own:

```
fckt --core -o fckt_core.ml
fckt --library std.fki -o fckt_std.ml --no-std -I std/src fckt.io fckt.collections ...
ocamlopt -I +unix unix.cmxa fckt_core.cmx fckt_std.cmx out.ml -o program
```

With `--whole`, it compiles the core and the standard library too, with the program, which is then
OCaml on its own: `ocamlopt -I +unix unix.cmxa out.ml`. It reads the core and the standard library
from the fckt repository it's run in. What's wrong with
the program, it says, as fckt's compiler words it, and ends with exit code 1.

It compiles itself. Built by fckt's compiler, it compiles its own source whole, and what that makes
compiles it again to the same OCaml, a fixed point that `BootstrapTest` holds it to: fckt's compiler
is needed only to build it the first time. The checker it makes checks every conformance case as
the one fckt's compiler makes does, to the byte, and each example it compiles whole prints what fckt's
compiler's does.
