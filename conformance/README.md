# Conformance

What any implementation of fckt does with each program here. A case is one `.fk` file, a program
with nothing imported but what it imports itself, and what's expected of it said in comments at its
end:

- `// prints: <line>`, one for each line it prints, in order: it compiles, runs, and prints them.
- `// error: <text>`, one or more: it doesn't compile, and what the compiler reports has each text
  in it. A position, `line:column`, is the file's own.
- `// compiles`: it compiles; what it does isn't said.

A case with none of them compiles, runs, and prints nothing. The standard library is std's.

fckt's own implementation is held to them by `ConformanceTest`.
