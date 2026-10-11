import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Serves exactly ONE connected client. The server creates one thread per client and
 * runs this class's {@link #run()} method on it.
 *
 * Protocol (plain text, one message per line):
 *   1. The client's first line is its chat name.
 *   2. Every following line is a chat message, or a command: /users, /quit.
 */
public class ClientHandler implements Runnable {

    private static final int NAME_TIMEOUT_MS = 30_000; // don't let a silent connection hold a thread forever
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,20}");

    private final Socket socket;
    private final ChatServer server;

    // Written by this handler's thread, read by OTHER threads that broadcast to us -> volatile.
    private volatile PrintWriter out;
    private volatile String clientName = "(unregistered)";
    private volatile boolean closedByServer = false;

    public ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        // try-with-resources closes the reader, writer and socket automatically,
        // whether we leave normally, by "return", or through an exception.
        try (Socket clientSocket = socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out = writer;
            clientSocket.setSoTimeout(NAME_TIMEOUT_MS);   // only while waiting for the name
            if (!performHandshake(reader)) {
                return;
            }
            clientSocket.setSoTimeout(0);                 // 0 = wait for chat messages as long as needed
            chatLoop(reader);

        } catch (SocketTimeoutException e) {
            server.log("Connection closed: no name received within " + (NAME_TIMEOUT_MS / 1000) + " seconds.");
        } catch (IOException e) {
            // Typical cause: the client crashed / lost its network ("Connection reset").
            if (!closedByServer) {
                server.log(clientName + " connection lost unexpectedly: " + e.getMessage());
            }
        } finally {
            cleanup(); // ALWAYS runs: removes this client from the shared list and tells the others
        }
    }

    /** Reads the name, registers this client and announces it. Returns false if the client was rejected. */
    private boolean performHandshake(BufferedReader reader) throws IOException {
        String requestedName = reader.readLine();
        if (requestedName == null) {
            server.log("A client connected and disconnected without sending a name.");
            return false;
        }
        requestedName = requestedName.trim();

        if (!VALID_NAME.matcher(requestedName).matches()) {
            send("ERROR: Name must be 1-20 characters: letters, digits, _ or -.");
            return false;
        }

        clientName = requestedName;
        if (!server.register(this)) {
            send("ERROR: The name '" + requestedName + "' is already taken. Please reconnect with another name.");
            return false;
        }

        server.log(clientName + " joined from " + socket.getRemoteSocketAddress()
                + ". Active clients: " + server.getClientCount());
        send("Welcome, " + clientName + "! Currently online: " + String.join(", ", server.getOnlineUsers()));
        send("Commands: /users (list who is online), /quit (leave the chat)");
        server.broadcast("*** " + clientName + " joined the chat ***", this);
        return true;
    }

    /** Reads this client's lines until it quits or disconnects. Blocks in readLine() most of the time. */
    private void chatLoop(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) { // null = the client closed the connection
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("/quit")) {
                send("Goodbye!");
                return;
            }
            if (line.equalsIgnoreCase("/users")) {
                List<String> users = server.getOnlineUsers();
                send("Online users (" + users.size() + "): " + String.join(", ", users));
                continue;
            }
            server.broadcast("[" + clientName + "] " + line, this);
        }
    }

    /** Called from OTHER threads (during a broadcast). PrintWriter.println() is internally synchronized,
     *  so two threads writing to the same client cannot mix up the characters of their lines. */
    void send(String message) {
        PrintWriter writer = out;
        if (writer != null) {
            writer.println(message); // PrintWriter never throws; a dead connection is detected by our read loop
        }
    }

    String getClientName() {
        return clientName;
    }

    /** Closes the socket. Used by the server on shutdown; safe to call several times. */
    void close() {
        closedByServer = true;
        try {
            socket.close(); // also unblocks readLine() in this handler's thread
        } catch (IOException e) {
            // ignore
        }
    }

    private void cleanup() {
        boolean wasActive = server.unregister(this); // false if we were never registered / already removed
        try {
            socket.close();
        } catch (IOException e) {
            // ignore
        }
        if (wasActive) {
            server.log(clientName + " disconnected. Active clients: " + server.getClientCount());
            server.broadcast("*** " + clientName + " left the chat ***", this);
        }
    }
}
