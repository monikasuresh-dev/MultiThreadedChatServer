import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Console chat client.
 *
 * Two threads run in every client:
 *   - the MAIN thread reads what you type and sends it to the server;
 *   - a "server-listener" thread prints whatever the server sends.
 * We need two because both "wait for keyboard" and "wait for the network" block.
 *
 * Usage: java -cp out ChatClient [name] [host] [port]
 */
public class ChatClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final String name;
    private final String host;
    private final int port;
    private volatile boolean userQuit = false;

    public ChatClient(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    public static void main(String[] args) {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        String host = args.length > 1 ? args[1] : "localhost";
        int port = ChatServer.DEFAULT_PORT;

        if (args.length > 2) {
            try {
                port = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[2] + "'. Usage: java -cp out ChatClient [name] [host] [port]");
                System.exit(1);
            }
        }

        String name = args.length > 0 ? args[0] : null;
        try {
            if (name == null) {
                System.out.print("Enter your name: ");
                name = console.readLine();
            }
        } catch (IOException e) {
            name = null;
        }
        if (name == null || name.trim().isEmpty()) {
            System.err.println("A name is required.");
            System.exit(1);
        }

        new ChatClient(name.trim(), host, port).run(console);
    }

    private void run(BufferedReader console) {
        System.out.println("Connecting to " + host + ":" + port + " ...");

        // The socket is closed automatically when this block ends.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

            PrintWriter toServer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader fromServer = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            toServer.println(name); // protocol: the first line is our chat name

            Thread listener = new Thread(() -> listenToServer(fromServer), "server-listener");
            listener.setDaemon(true); // must not keep the program alive after the main thread ends
            listener.start();

            String line;
            while ((line = console.readLine()) != null) {
                toServer.println(line);
                if (line.trim().equalsIgnoreCase("/quit")) {
                    break;
                }
            }
            userQuit = true; // also reached when the input stream is closed (Ctrl+Z, Enter on Windows)
            System.out.println("You left the chat.");

        } catch (UnknownHostException e) {
            System.err.println("Unknown host '" + host + "'. Check the host name.");
        } catch (SocketTimeoutException e) {
            System.err.println("Connection to " + host + ":" + port + " timed out.");
        } catch (ConnectException e) {
            System.err.println("Could not connect to " + host + ":" + port
                    + ". Is the server running, and is the port number correct?");
        } catch (IOException e) {
            System.err.println("Connection problem: " + e.getMessage());
        }
    }

    /** Runs on the listener thread: prints every line the server sends. */
    private void listenToServer(BufferedReader fromServer) {
        try {
            String line;
            while ((line = fromServer.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            // socket closed while reading: handled below
        }
        if (!userQuit) {
            // The server closed the connection (rejected our name, shut down, or crashed).
            System.out.println("*** Connection to the server was closed. ***");
            System.exit(0); // the main thread is blocked on the keyboard, so end the program here
        }
    }
}
