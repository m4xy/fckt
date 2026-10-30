# fckt.compiler

fckt's compiler, written in fckt: `fckt.parser`, `fckt.checker` and `fckt.lowering`, as a command.

```
kiln build
build/bin/compiler <file.fk> <out.ml> [<folder>...]
```

compiles the program in the file, with the packages in each folder as its own, to OCaml, which is
linked with the core and the standard library as fckt's compiler compiles them on their own:

```
fckt --core -o fckt_core.ml
fckt --library std.fki -o fckt_std.ml --no-std -I std/src fckt.io fckt.collections ...
ocamlopt -I +unix unix.cmxa fckt_core.cmx fckt_std.cmx out.ml -o program
```

It reads the core and the standard library from the fckt repository it's run in. What's wrong with
the program, it says, as fckt's compiler words it, and ends with exit code 1.

It compiles fckt.checker: the checker it makes checks every conformance case as the one fckt's
compiler makes does, to the byte.
