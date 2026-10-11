# Test Plan

Run these tests on your own computer and keep screenshots as evidence. "Automated" means `test/ChatServerTest.java` covers the same behaviour (run it with the three commands from the README).

Setup for all manual tests: compile once, then start the server (`java -cp out ChatServer`) in terminal 1 and open one terminal per client.

| # | Requirement | Steps | Expected result | Automated? |
|---|-------------|-------|-----------------|------------|
| 1 | Project compiles | `javac -d out src\ChatServer.java src\ClientHandler.java src\ChatClient.java` | No errors; `out` contains three `.class` files. | (compile step) |
| 2 | Server starts | `java -cp out ChatServer` | Log line `Chat server started on 127.0.0.1:5000 ...`. | Test 1 |
| 3 | One client connects | `java -cp out ChatClient Alice` | Alice sees `Welcome, Alice! Currently online: Alice`; server logs `Alice joined ... Active clients: 1`. | Test 1 |
| 4 | Multiple clients connect at once | Start Bob and Carol in two more terminals. | Server logs `Active clients: 2`, then `3`; each join is handled by a different `client-handler-N` thread. | Test 2 |
| 5 | Join notification | Watch Alice's terminal when Bob joins. | Alice sees `*** Bob joined the chat ***`. The new client itself sees the welcome line listing everybody who is online. | Test 2 |
| 6 | Broadcast | Alice types `hello`. | Bob and Carol see `[Alice] hello`. Alice does not get an echo. Repeat from Bob and Carol. | Test 3 |
| 7 | `/users` | Type `/users` in any client. | `Online users (3): Alice, Bob, Carol`. | Test 4 |
| 8 | Graceful disconnect | Carol types `/quit`. | Alice and Bob see `*** Carol left the chat ***`; server logs `Carol disconnected. Active clients: 2`. | Test 5 |
| 9 | Others keep working | Alice and Bob exchange messages. | Messages still arrive. | Test 6 |
| 10 | Abrupt disconnect | Press **Ctrl+C** in Bob's terminal (kills the client without `/quit`). | Server logs the disconnect and `Active clients: 1`; Alice sees `*** Bob left the chat ***`; the server keeps running. | Test 7 |
| 11 | Duplicate name | Start a second client named `alice`. | `ERROR: The name 'alice' is already taken...`, then `*** Connection to the server was closed. ***`; the first Alice is unaffected. | Test 8 |
| 12 | Connection failure (client) | Stop the server, then start `java -cp out ChatClient Zed`. | `Could not connect to localhost:5000. Is the server running, and is the port number correct?` (no crash, no long stack trace). | not automated (checked once on Linux) |
| 13 | Port already in use | Start the server twice. | The second one prints `Could not start the server on port 5000: Address already in use` and exits. | not automated (checked once on Linux) |
| 14 | Server shutdown | Press Ctrl+C in the server terminal while clients are connected. | Clients print `*** Server is shutting down ***` and then `*** Connection to the server was closed. ***`. | Test 10 |
| 15 | Concurrency under load | Run the automated tests. | `10 clients x 20 messages`: every client receives all 180 messages from the others; no exceptions. | Test 9 |

Note on Windows: test 10 and 14 rely on Ctrl+C. If the JVM prints nothing on Ctrl+C that is normal; the server log line `Server shutting down...` is produced by a shutdown hook and may not always appear before the window closes. If you do not see the clients' shutdown message, just note what you observed; do not edit the screenshot.

Record in your report: date, Java version (`java -version`), and the result (pass/fail) of each row you ran.
