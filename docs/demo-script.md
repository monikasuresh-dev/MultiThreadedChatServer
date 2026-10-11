# Demo Script (screen recording)

Goal: show the server starting, three clients connecting, each sending a message, a client leaving, and the others continuing. Takes about 3-4 minutes.

## Before you press "record"

1. Close other programs; make the VS Code window large.
2. Open the project folder in VS Code.
3. Open **four terminals** and arrange them so that all four are visible at once (drag them into a 2x2 grid in the terminal panel, or use the split button). Rename them if you like (right-click the terminal tab → Rename): `SERVER`, `Alice`, `Bob`, `Carol`.
4. In every terminal run `cd` into the project folder if the prompt is not already there.
5. Compile once and show the result:

```powershell
javac -d out src\ChatServer.java src\ClientHandler.java src\ChatClient.java
```

6. Test that port 5000 is free. If not, use another port in every command below (see README).

## Recording sequence

| Step | Terminal | Action | What the audience should see |
|------|----------|--------|------------------------------|
| 1 | any | Run `java -version` and `javac -version`. | Your Java version. |
| 2 | SERVER | `java -cp out ChatServer` | `Chat server started on 127.0.0.1:5000. Waiting for clients...` Say: "The main thread is waiting in accept()". |
| 3 | Alice | `java -cp out ChatClient Alice` | Welcome message. SERVER shows `[client-handler-1] Alice joined ... Active clients: 1`. |
| 4 | Bob | `java -cp out ChatClient Bob` | Bob's welcome lists Alice and Bob. Alice sees `*** Bob joined the chat ***`. SERVER shows `[client-handler-2]`. |
| 5 | Carol | `java -cp out ChatClient Carol` | Alice and Bob see Carol joined. SERVER shows `[client-handler-3]` and `Active clients: 3`. Say: "Each client has its own thread." |
| 6 | Alice | Type `Hello everyone, this is Alice` + Enter | Bob and Carol receive `[Alice] Hello everyone...`. SERVER logs the broadcast. |
| 7 | Bob | Type `Hi Alice, Bob here` + Enter | Alice and Carol receive it. |
| 8 | Carol | Type `Carol checking in` + Enter | Alice and Bob receive it. |
| 9 | Bob | Type `/users` + Enter | `Online users (3): Alice, Bob, Carol`. |
| 10 | Carol | Type `/quit` + Enter | Carol's client ends. Alice and Bob see `*** Carol left the chat ***`. SERVER shows `Carol disconnected. Active clients: 2`. |
| 11 | Alice | Type `Bob, can you still read this?` | Bob receives it. |
| 12 | Bob | Type `Yes, still connected` | Alice receives it. Say: "The server keeps working after a client left." |
| 13 (optional) | Bob | Press **Ctrl+C** (crash simulation) | SERVER logs the disconnect, Alice sees `*** Bob left the chat ***`. |
| 14 | Alice | Type `/quit` | Server shows `Active clients: 0`. |
| 15 | SERVER | Press Ctrl+C | Server stops. |

## Things to say while recording (talking points)

* "The main thread only accepts connections; each client gets its own `client-handler` thread" (point at the thread names in the server log).
* "The list of connected clients is shared between all threads, so it is protected with `synchronized`."
* "When a client leaves, the `finally` block removes it from the list and tells the others."

## Tips

* Do a dry run first; do not cut or edit the recording in a way that hides errors.
* If something goes wrong on camera, stop, fix it and record again.
* Save the recording and your screenshots as your evidence (see `screenshots/README.md`).
