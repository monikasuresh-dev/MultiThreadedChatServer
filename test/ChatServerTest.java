import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Automated end-to-end tests. No test framework needed: it starts a real ChatServer on a free
 * port, connects real TCP clients and checks what they receive.
 *
 * Run (from the project folder):
 *   javac -d out src\ChatServer.java src\ClientHandler.java src\ChatClient.java
 *   javac -cp out -d out test\ChatServerTest.java
 *   java -cp out ChatServerTest
 */
public class ChatServerTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + description);
        } else {
            failed++;
            System.out.println("  FAIL  " + description);
        }
    }

    /** A minimal raw TCP client that speaks the chat protocol. */
    private static class TestClient {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        TestClient(int port, String name) throws IOException {
            socket = new Socket(InetAddress.getByName("localhost"), port);
            socket.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            if (name != null) {
                out.println(name);
            }
        }

        void send(String text) {
            out.println(text);
        }

        /** Reads lines until one contains {@code text}. Returns every line read (the last one is the match). */
        List<String> readUntil(String text) throws IOException {
            List<String> seen = new ArrayList<String>();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    seen.add(line);
                    if (line.contains(text)) {
                        return seen;
                    }
                }
            } catch (SocketTimeoutException e) {
                throw new IOException("Timed out waiting for '" + text + "'. Lines seen: " + seen);
            }
            throw new IOException("Connection closed while waiting for '" + text + "'. Lines seen: " + seen);
        }

        /** True if the server closes the connection (end of stream) within the timeout. */
        boolean serverClosedConnection() {
            try {
                while (in.readLine() != null) {
                    // skip remaining lines
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private static boolean waitForClientCount(ChatServer server, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (server.getClientCount() == expected) {
                return true;
            }
            Thread.sleep(25);
        }
        return server.getClientCount() == expected;
    }

    private static boolean anyContains(List<String> lines, String text) {
        for (String line : lines) {
            if (line.contains(text)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        ChatServer server = new ChatServer(0, "localhost"); // port 0 = any free port
        final int port = server.getPort();
        Thread acceptThread = new Thread(server::start, "test-accept-loop");
        acceptThread.start();

        try {
            System.out.println("== Test 1: server start and single client ==");
            check("Server is listening on a free port (" + port + ")", port > 0);
            TestClient alice = new TestClient(port, "Alice");
            List<String> aliceWelcome = alice.readUntil("Welcome, Alice");
            check("Alice receives a welcome message", aliceWelcome.size() >= 1);
            check("Server shows 1 active client", waitForClientCount(server, 1));

            System.out.println("== Test 2: multiple simultaneous clients and join notifications ==");
            TestClient bob = new TestClient(port, "Bob");
            List<String> bobWelcome = bob.readUntil("Welcome, Bob");
            check("Bob's welcome lists Alice as already online", anyContains(bobWelcome, "Alice"));
            check("Alice is notified that Bob joined", anyContains(alice.readUntil("Bob joined"), "Bob joined"));
            TestClient carol = new TestClient(port, "Carol");
            carol.readUntil("Welcome, Carol");
            alice.readUntil("Carol joined");
            bob.readUntil("Carol joined");
            check("Server shows 3 active clients", waitForClientCount(server, 3));

            System.out.println("== Test 3: broadcasting ==");
            alice.send("hello from alice");
            check("Bob receives Alice's message", anyContains(bob.readUntil("[Alice] hello from alice"), "hello from alice"));
            check("Carol receives Alice's message", anyContains(carol.readUntil("[Alice] hello from alice"), "hello from alice"));
            bob.send("hi from bob");
            List<String> seenByAlice = alice.readUntil("[Bob] hi from bob");
            check("Alice receives Bob's message", anyContains(seenByAlice, "[Bob] hi from bob"));
            check("A sender does not receive an echo of its own message", !anyContains(seenByAlice, "[Alice] hello"));
            carol.readUntil("[Bob] hi from bob");

            System.out.println("== Test 4: /users command ==");
            alice.send("/users");
            List<String> users = alice.readUntil("Online users");
            String usersLine = users.get(users.size() - 1);
            check("/users lists all three clients",
                    usersLine.contains("(3)") && usersLine.contains("Alice")
                            && usersLine.contains("Bob") && usersLine.contains("Carol"));

            System.out.println("== Test 5: graceful disconnect (/quit) ==");
            carol.send("/quit");
            check("Alice is told Carol left", anyContains(alice.readUntil("Carol left"), "Carol left"));
            check("Bob is told Carol left", anyContains(bob.readUntil("Carol left"), "Carol left"));
            check("Server closed Carol's connection", carol.serverClosedConnection());
            check("Carol was removed from the active client list", waitForClientCount(server, 2));

            System.out.println("== Test 6: remaining clients keep communicating ==");
            alice.send("still here");
            check("Bob still receives Alice's messages", anyContains(bob.readUntil("[Alice] still here"), "still here"));
            carol.close();

            System.out.println("== Test 7: abrupt disconnect (simulated crash / connection reset) ==");
            bob.socket.setSoLinger(true, 0); // close() now sends a TCP reset instead of a polite FIN
            bob.close();
            check("Alice is told Bob left", anyContains(alice.readUntil("Bob left"), "Bob left"));
            check("Bob was removed from the active client list", waitForClientCount(server, 1));

            System.out.println("== Test 8: rejected and half-open connections do not break the server ==");
            TestClient duplicate = new TestClient(port, "alice"); // same name, different case
            check("Duplicate name is rejected with an ERROR", anyContains(duplicate.readUntil("ERROR"), "already taken"));
            check("Server closes the rejected connection", duplicate.serverClosedConnection());
            TestClient invalid = new TestClient(port, "bad name!");
            check("Invalid name is rejected with an ERROR", anyContains(invalid.readUntil("ERROR"), "ERROR"));
            TestClient silent = new TestClient(port, null); // connects, never sends a name
            silent.close();
            Thread.sleep(200);
            check("Rejected/silent connections were not added to the client list", server.getClientCount() == 1);
            TestClient dave = new TestClient(port, "Dave");
            dave.readUntil("Welcome, Dave");
            check("Server still accepts new clients afterwards", waitForClientCount(server, 2));
            alice.readUntil("Dave joined");
            dave.send("dave here");
            check("New client can chat with the old one", anyContains(alice.readUntil("[Dave] dave here"), "dave here"));
            alice.send("/quit");
            dave.send("/quit");
            alice.close();
            dave.close();
            check("All clients removed, no stale entries", waitForClientCount(server, 0));

            System.out.println("== Test 9: stress test - 10 clients sending 20 messages each at the same time ==");
            runStressTest(server, port);

            System.out.println("== Test 10: server shutdown ==");
            TestClient last = new TestClient(port, "Last");
            last.readUntil("Welcome, Last");
            server.shutdown();
            check("Connected client is told about the shutdown", anyContains(last.readUntil("shutting down"), "shutting down"));
            check("Client connection is closed by the server", last.serverClosedConnection());
            acceptThread.join(3000);
            check("Accept loop has ended", !acceptThread.isAlive());
            last.close();

        } catch (Exception e) {
            failed++;
            System.out.println("  FAIL  Unexpected exception: " + e);
            e.printStackTrace(System.out);
        } finally {
            server.shutdown();
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void runStressTest(final ChatServer server, final int port) throws Exception {
        final int clientCount = 10;
        final int messagesEach = 20;
        final TestClient[] clients = new TestClient[clientCount];
        for (int i = 0; i < clientCount; i++) {
            clients[i] = new TestClient(port, "U" + i);
            clients[i].readUntil("Welcome, U" + i);
        }
        check("All " + clientCount + " stress clients are connected", waitForClientCount(server, clientCount));

        final int[] received = new int[clientCount];
        final CountDownLatch startGate = new CountDownLatch(1);
        Thread[] workers = new Thread[clientCount];
        for (int i = 0; i < clientCount; i++) {
            final int index = i;
            workers[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startGate.await();
                        for (int m = 0; m < messagesEach; m++) {
                            clients[index].send("msg-" + m);
                        }
                        int expected = (clientCount - 1) * messagesEach;
                        int count = 0;
                        String line;
                        while (count < expected && (line = clients[index].in.readLine()) != null) {
                            if (line.startsWith("[U") && line.contains("] msg-")) {
                                count++;
                            }
                        }
                        received[index] = count;
                    } catch (Exception e) {
                        received[index] = -1;
                    }
                }
            }, "stress-worker-" + i);
            workers[i].start();
        }
        startGate.countDown(); // everybody starts sending at (almost) the same moment
        for (Thread worker : workers) {
            worker.join(20000);
        }

        boolean allComplete = true;
        for (int count : received) {
            if (count != (clientCount - 1) * messagesEach) {
                allComplete = false;
            }
        }
        check("Every client received all " + ((clientCount - 1) * messagesEach)
                + " messages from the others (none lost)", allComplete);
        check("Server still has " + clientCount + " clients after the burst", server.getClientCount() == clientCount);

        for (TestClient client : clients) {
            client.send("/quit");
            client.close();
        }
        check("All stress clients removed afterwards", waitForClientCount(server, 0));
    }
}
