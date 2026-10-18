// Prints each token of a .fk file with its most specific scope: npm run tokens -- <file.fk>
const fs = require('fs');
const path = require('path');
const base = path.join(__dirname, '..', 'node_modules');
const vsctm = require(path.join(base, 'vscode-textmate'));
const oniguruma = require(path.join(base, 'vscode-oniguruma'));

const wasm = fs.readFileSync(path.join(base, 'vscode-oniguruma', 'release', 'onig.wasm')).buffer;
const onigLib = oniguruma.loadWASM(wasm).then(() => ({
  createOnigScanner: (p) => new oniguruma.OnigScanner(p),
  createOnigString: (s) => new oniguruma.OnigString(s),
}));
const grammarPath = path.join(__dirname, '..', 'syntaxes', 'fckt.tmLanguage.json');
const registry = new vsctm.Registry({
  onigLib,
  loadGrammar: async () => vsctm.parseRawGrammar(fs.readFileSync(grammarPath, 'utf8'), grammarPath),
});
registry.loadGrammar('source.fckt').then((grammar) => {
  let state = vsctm.INITIAL;
  fs.readFileSync(process.argv[2], 'utf8').split('\n').forEach((line, i) => {
    const r = grammar.tokenizeLine(line, state);
    const parts = r.tokens
      .map((t) => [line.substring(t.startIndex, t.endIndex), t.scopes[t.scopes.length - 1]])
      .filter(([text, s]) => text.trim() && s !== 'source.fckt')
      .map(([text, s]) => `${text.trim()}=${s.replace(/\.fckt$/, '')}`);
    console.log(`${i + 1}: ${parts.join('  ')}`);
    state = r.ruleStack;
  });
});
