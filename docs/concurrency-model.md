# Concurrency Model

This document explains how the chat server handles many clients at the same time. Everything below was checked against the actual source code; only mechanisms that really exist in the code are described.

Quick facts about what the code uses:

| Mechanism | Used? | Where |
|-----------|-------|-------|
| One thread per connected client (`new Thread(handler).start()`) | Yes | `ChatServer.start()` |
| `synchronized (clients) { ... }` blocks around the shared list | Yes | `register`, `unregister`, `broadcast` (snapshot), `getOnlineUsers`, `getClientCount`, `shutdown` |
| `volatile` fields | Yes | `ChatServer.running`; `ClientHandler.out`, `clientName`, `closedByServer`; `ChatClient.userQuit` |
| try-with-resources / `finally` for cleanup | Yes | `ClientHandler.run()`, `ChatClient.run()` |
| Thread pools (`ExecutorService`), `ReentrantLock`, concurrent collections, NIO | **No** | not used on purpose, to keep the model simple |

## 1. What concurrency means in this project

Concurrency means that several things are *in progress at the same time*. In this project, many people can be connected and typing at the same moment, and the server must serve all of them without making anybody wait for somebody else. The Java Virtual Machine achieves this by running several **threads** (independent paths of execution) inside the one server program.

## 2. Why the server must handle clients simultaneously

Reading from a socket is a **blocking** call: `readLine()` simply waits until the client sends something. If the server had only one thread, it would be stuck waiting for client A and could neither read Client B's message nor even accept Client C's connection. Giving each client its own thread means every client has someone "listening" to it all the time.

## 3. The main thread: accepting connections

The thread that runs `main()` ends up in `ChatServer.start()`. This is the **accept loop**:

```java
while (running && !serverSocket.isClosed()) {
    Socket clientSocket = serverSocket.accept();      // waits for the next client
    ClientHandler handler = new ClientHandler(clientSocket, this);
    new Thread(handler, "client-handler-" + n).start(); // hand the client to a new thread
}
```

The main thread does **only** this. It never reads chat messages. As soon as it has started a handler thread it goes straight back to `accept()`, so a new client never has to wait for an existing one.

## 4. Client-handler threads: one per client

`ClientHandler` implements `Runnable`. Its `run()` method executes on a dedicated thread named `client-handler-1`, `client-handler-2`, ... and does everything for that one client: read the name, register, read messages in a loop, and clean up. Every server log line shows the name of the thread that wrote it, so the demo makes the two kinds of thread visible:

```
[10:15:03] [main]             Chat server started on 127.0.0.1:5000. ...
[10:15:09] [client-handler-1] Alice joined from /127.0.0.1:50321. Active clients: 1
```

| | Main thread | Client-handler thread |
|---|-------------|----------------------|
| How many | exactly 1 | 1 per connected client |
| Job | accept connections, start handler threads | read one client's messages, trigger broadcasts, clean up |
| Blocks in | `serverSocket.accept()` | `reader.readLine()` |
| Ends when | the server is shut down | its client leaves or the connection breaks |

## 5. How the shared client list is stored and accessed safely

The server keeps one list: `private final List<ClientHandler> clients = new ArrayList<ClientHandler>();`

This list is **shared mutable state**: every handler thread adds to it (when its client joins), removes from it (when its client leaves) and reads from it (when broadcasting), possibly at the same moment. `ArrayList` is not thread-safe, so the code follows one simple rule: **the list is only touched inside `synchronized (clients) { ... }`**. All access goes through a few small methods (`register`, `unregister`, `getOnlineUsers`, `getClientCount`, and the snapshot taken by `broadcast`), so the rule is easy to check.

## 6. How synchronization prevents conflicting operations

`synchronized (clients)` is a lock (a "monitor"). Only one thread at a time can be inside a block that uses the same lock object; others wait at the door. So while one thread is adding or removing an entry, no other thread can read or change the list.

An important example is the **name check in `register()`**: "is this name taken?" and "add me" happen inside the *same* synchronized block. If they were two separate steps, two clients joining at the same moment could both pass the check and both be added with the same name (a "check-then-act" race).

Two more small details:

* `PrintWriter.println()` is internally synchronized in the Java library, so if two threads send a line to the same client at the same moment, the two lines are not mixed character by character.
* The fields that one thread writes and *other* threads read (`out`, `clientName`, ...) are `volatile`, so the other threads see the up-to-date value.

## 7. How broadcasting works

When Client A sends a message, A's handler thread calls `ChatServer.broadcast("[Alice] hello", this)`:

1. Inside `synchronized (clients)`, it makes a **copy** of the list.
2. After leaving the synchronized block, it loops over the copy and calls `send(...)` on every handler except the sender's.

Why copy first? (a) Nobody can change the copy while we loop over it, so there is no `ConcurrentModificationException`. (b) The lock is held only for a very short time. Writing to the network can be slow; if we wrote while holding the lock, one slow client would stop all other clients from joining or leaving.

Consequence you should be aware of: a client that disconnects *after* the copy was taken may still be in our copy. Sending to it does nothing harmful (`PrintWriter` ignores the error), and its own handler thread removes it from the real list.

Also note: each line is delivered intact, but if two different people write at exactly the same moment, different clients may see those two messages in a different order. The server does not guarantee one global ordering.

## 8. Disconnection and cleanup

A client can leave in two ways:

* **Politely**: it sends `/quit` (or closes the program). `readLine()` returns `null` or the loop returns after `/quit`.
* **Abruptly**: the program crashes or the network drops. `readLine()` throws an `IOException` (for example "Connection reset"), which `run()` catches and logs.

In both cases the `finally` block of `ClientHandler.run()` runs `cleanup()`:

1. `server.unregister(this)` removes the entry from the shared list. It returns `true` only if the entry was really there, so "left the chat" is announced **exactly once**, even if cleanup were called twice.
2. The socket is closed. The reader, writer and socket are also closed by the try-with-resources statement, whichever way `run()` ends.
3. The remaining clients receive `*** Name left the chat ***`.

Because `cleanup()` is in a `finally` block, a failure of one client can never leave a stale entry behind, and it never affects the other handler threads or the main thread.

## 9. What can go wrong without protection

| Problem | What would happen without synchronization |
|---------|-------------------------------------------|
| `ConcurrentModificationException` | A thread iterates over the list to broadcast while another thread adds or removes a client → the server throws an exception in the middle of a broadcast. |
| Corrupted list | Two threads modify an `ArrayList` at once → lost entries, wrong size, or `ArrayIndexOutOfBoundsException`. |
| Duplicate names | Two clients choose the same name at the same moment and both pass the "is it free?" check. |
| Stale entries | A disconnected client stays in the list; every broadcast then tries to write to a dead connection. |
| Stale values | Another thread keeps seeing an old value of a field that was not `volatile`. |

These are *race conditions*: the result depends on the exact timing of the threads, so the bugs appear randomly and are hard to reproduce. That is why the project test suite includes a stress test (10 clients sending 20 messages each at the same moment).

## 10. Limitations of thread-per-client and possible improvements

Limitations of this design:

* **One thread per client costs memory.** Hundreds of clients are fine; tens of thousands are not.
* **No limit on the number of clients or on the length of a line.** A client sending a huge line without a newline can use a lot of server memory.
* **A slow or frozen client can slow down a broadcast.** `send()` writes directly to the socket; if the client's network buffer is full, the thread that is broadcasting waits.
* **No guaranteed global message order** (see section 7).
* **Educational demo only**: no authentication, no encryption, no message history, no persistence. Anyone who can reach the port can join under any free name. It is not production-ready.

Possible improvements (not implemented):

* Use an `ExecutorService` thread pool to limit the number of threads.
* Use `CopyOnWriteArrayList` or `ConcurrentHashMap` instead of manual `synchronized` blocks.
* Give each client an outgoing message queue and a writer thread so one slow client cannot delay others.
* Limit line length and number of clients; add idle time-outs.
* Switch to Java NIO (`Selector`) or virtual threads (Java 21) for large numbers of clients.
* Add TLS (encryption) and authentication; add private messages and chat rooms.
