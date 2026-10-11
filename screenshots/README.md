# Screenshot Checklist

Take **real screenshots on your own computer** while following `docs/demo-script.md` and save them in this folder with the file names below. Do not use screenshots from anywhere else and do not edit them.

| # | File name | What must be visible |
|---|-----------|----------------------|
| 1 | `01-java-version.png` | Output of `java -version` and `javac -version`. |
| 2 | `02-compile.png` | The `javac -d out ...` command with no errors (and optionally `dir out` showing the three `.class` files). |
| 3 | `03-server-started.png` | Server terminal: `Chat server started on ...`. |
| 4 | `04-three-clients.png` | All four terminals (server + Alice, Bob, Carol) visible, after the three clients joined; server shows `Active clients: 3` and `client-handler-1/2/3`. |
| 5 | `05-broadcast.png` | A message typed in one client and received in the other two (all terminals visible). |
| 6 | `06-users-command.png` | `/users` output. |
| 7 | `07-client-left.png` | Carol typed `/quit`; the others show `*** Carol left the chat ***`; server shows `Active clients: 2`. |
| 8 | `08-chat-continues.png` | Alice and Bob still exchanging messages after Carol left. |
| 9 | `09-abrupt-disconnect.png` (optional) | After Ctrl+C in a client: the server log and the other client's notice. |
| 10 | `10-automated-tests.png` | Output of `java -cp out ChatServerTest` ending with `RESULT: ... 0 failed`. |
| 11 | `11-architecture-diagram.png` | The diagram `docs/images/architecture.png` (you can use the file itself; no screenshot needed). |
| 12 | `12-vscode-project.png` | VS Code Explorer showing the project structure. |
| 13 | `13-github-repo.png` | Your GitHub repository page after pushing. |

## Other evidence to keep for the internship report

* The screen recording of the demo.
* The link to your GitHub repository.
* The source files `ChatServer.java`, `ClientHandler.java`, `ChatClient.java` (you can include the important parts in the report appendix).
* `docs/concurrency-model.md` content (summary for the report) and the architecture diagram (`docs/images/architecture.png`).
* The result table of `docs/test-plan.md` filled in with your own results, with the date and your Java version.
