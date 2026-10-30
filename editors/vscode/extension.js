// Starts fckt --language-server for .fk files, which says what's wrong as you type, and lays a file
// out as fckt --format does. fckt.path says which fckt, if it isn't the one on PATH.
const vscode = require("vscode");
const { LanguageClient } = require("vscode-languageclient/node");

let client;

function activate() {
  const command = vscode.workspace.getConfiguration("fckt").get("path") || "fckt";
  client = new LanguageClient(
    "fckt",
    "fckt",
    { command, args: ["--language-server"] },
    { documentSelector: [{ scheme: "file", language: "fckt" }] },
  );
  return client.start();
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };
