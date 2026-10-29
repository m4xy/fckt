# fckt.parser

fckt's source read as fckt's compiler reads it, written in fckt: the start of a compiler that
compiles itself. So far it splits a source into tokens:

```kotlin
import fckt.parser.*

kinds(source: String): Result<List<Kind>, SyntaxError> = Lexer.tokens(source).map { tokens -> tokens.map { it.kind } }
```

A `Token` is its `Kind`, its text, the `Position` it starts at, and, for a string, its `parts`: a
`Text` as it is, or the `Code` of a `$name` or `${expression}`, with where that starts. A line
break is a `Newline` only where it can end a statement, and the last token is the `End`. A source
it can't split is a `SyntaxError`, where and why, as `Rejecting`'s `reject` says it.

fckt's own tests, `LexerTest`, hold it to the compiler's lexer, token for token. `kiln build` here
builds it, and `kiln publishLocal` publishes it as `fckt:parser`.
