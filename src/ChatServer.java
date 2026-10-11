import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-threaded TCP chat server (educational demonstration).
 *
 * Concurrency model: "thread-per-client".
 *   - The MAIN thread runs the accept loop in {@link #start()}. It only accepts
 *     new connections; it never talks to clients itself.
 *   - Every accepted connection gets its OWN {@link ClientHandler} thread that
 *     reads that client's messages.
 *   - The list of connected clients ({@link #clients}) is shared by all of those
 *     threads, so every access to it is guarded by synchronized (clients).
 */
public class ChatServer {

    public static final int DEFAULT_PORT = 5000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServerSocket serverSocket;

    // SHARED STATE: touched by the accept thread and by every client-handler thread.
    // Rule: only read or modify it inside synchronized (clients) { ... }.
    private final List<ClientHandler> clients = new ArrayList<ClientHandler>();

    private volatile boolean running = true;

    /**
     * Opens the listening socket.
     *
     * @param port     TCP port to listen on (0 = let the OS pick a free port, used by the tests)
     * @param bindHost "localhost" = only this computer can connect; "0.0.0.0" = other computers too
     */
    public ChatServer(int port, String bindHost) throws IOException {
        this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName(bindHost));
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String bindHost = "localhost";

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "'. Example: java -cp out ChatServer 5000");
                System.exit(1);
            }
            if (port < 1 || port > 65535) {
                System.err.println("Port must be between 1 and 65535.");
                System.exit(1);
            }
        }
        if (args.length > 1) {
            bindHost = args[1];
        }

        try {
            ChatServer server = new ChatServer(port, bindHost);
            // Runs when you press Ctrl+C so clients are told the server is going away.
            Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown-hook"));
            server.start();
        } catch (IOException e) {
            System.err.println("Could not start the server on port " + port + ": " + e.getMessage());
            System.err.println("The port may already be in use. Try another one, e.g.: java -cp out ChatServer 6000");
            System.exit(1);
        }
    }

    /** The accept loop. Runs on the calling (main) thread and blocks until {@link #shutdown()}. */
    public void start() {
        log("Chat server started on " + serverSocket.getInetAddress().getHostAddress()
                + ":" + serverSocket.getLocalPort() + ". Waiting for clients... (Ctrl+C to stop)");
        int handlerNumber = 0;

        while (running && !serverSocket.isClosed()) {
            Socket clientSocket = null;
            try {
                clientSocket = serverSocket.accept(); // blocks until a client connects
                handlerNumber++;
                ClientHandler handler = new ClientHandler(clientSocket, this);
                Thread handlerThread = new Thread(handler, "client-handler-" + handlerNumber);
                handlerThread.start(); // from now on this client is served by its own thread
                // The loop immediately goes back to accept() -> the next client is never kept waiting.
            } catch (IOException e) {
                if (running && !serverSocket.isClosed()) {
                    log("Error while accepting a connection: " + e.getMessage());
                }
                closeQuietly(clientSocket); // no-op if accept() itself failed
            }
        }
        log("Accept loop stopped.");
    }

    /** Registers a handler under its chosen name. Returns false if that name is already in use. */
    boolean register(ClientHandler handler) {
        synchronized (clients) {
            for (ClientHandler existing : clients) {
                if (existing.getClientName().equalsIgnoreCase(handler.getClientName())) {
                    return false;
                }
            }
            clients.add(handler);
            return true;
        }
    }

    /** Removes a handler. Returns true only if it was really in the list (safe to call twice). */
    boolean unregister(ClientHandler handler) {
        synchronized (clients) {
            return clients.remove(handler);
        }
    }

    /**
     * Sends a message to every connected client except {@code sender} (pass null to send to all).
     *
     * We copy the list while holding the lock and send AFTER releasing it. That way:
     *  - nobody can modify the list while we iterate over our private copy (no
     *    ConcurrentModificationException), and
     *  - one slow client cannot keep the lock and freeze joins/leaves of other clients.
     */
    void broadcast(String message, ClientHandler sender) {
        List<ClientHandler> recipients = snapshotOfClients();
        int delivered = 0;
        for (ClientHandler client : recipients) {
            if (client != sender) {
                client.send(message);
                delivered++;
            }
        }
        if (sender != null) {
            log("Broadcast from " + sender.getClientName() + " to " + delivered
                    + " other client(s): " + message);
        }
    }

    /** Names of everybody currently online (a consistent copy taken under the lock). */
    List<String> getOnlineUsers() {
        List<String> names = new ArrayList<String>();
        synchronized (clients) {
            for (ClientHandler client : clients) {
                names.add(client.getClientName());
            }
        }
        return names;
    }

    public int getClientCount() {
        synchronized (clients) {
            return clients.size();
        }
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Stops accepting connections and disconnects everybody. Safe to call more than once. */
    public void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        log("Server shutting down...");
        broadcast("*** Server is shutting down ***", null);
        try {
            serverSocket.close(); // makes the blocked accept() throw, which ends the accept loop
        } catch (IOException e) {
            // nothing useful to do
        }
        for (ClientHandler client : snapshotOfClients()) {
            client.close();
        }
    }

    /** Prints a timestamped line that also shows WHICH thread produced it (handy for the demo). */
    void log(String message) {
        String line = "[" + LocalTime.now().format(TIME_FORMAT) + "] ["
                + Thread.currentThread().getName() + "] " + message;
        System.out.println(line); // one println call keeps each line intact
    }

    private List<ClientHandler> snapshotOfClients() {
        synchronized (clients) {
            return new ArrayList<ClientHandler>(clients);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
