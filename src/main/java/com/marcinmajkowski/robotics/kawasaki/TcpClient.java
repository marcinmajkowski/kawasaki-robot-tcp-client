package com.marcinmajkowski.robotics.kawasaki;

import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TelnetNotificationHandler;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class TcpClient {
    //TODO add timeouts in while loops
    //TODO maximum number of characters for one command is 128
    //TODO implement save/load
    //TODO add support for scientific notation (?)

    private final String hostname;
    private final int port;
    private final String login;

    private final TelnetClient telnetClient = new TelnetClient();

    public TcpClient() {
        this("localhost");
    }

    public TcpClient(String hostname) {
        this(hostname, 9105);
    }

    public TcpClient(String hostname, int port) {
        this(hostname, port, "as");
    }

    public TcpClient(String hostname, int port, String login) {
        this.hostname = hostname;
        this.port = port;
        this.login = login;

        telnetClient.registerNotifHandler(new TelnetNotificationHandler() {
            public void receivedNegotiation(int negotiation_code, int option_code) {
                String command = null;
                if (negotiation_code == TelnetNotificationHandler.RECEIVED_DO) {
                    command = "DO";
                } else if (negotiation_code == TelnetNotificationHandler.RECEIVED_DONT) {
                    command = "DONT";
                } else if (negotiation_code == TelnetNotificationHandler.RECEIVED_WILL) {
                    command = "WILL";
                } else if (negotiation_code == TelnetNotificationHandler.RECEIVED_WONT) {
                    command = "WONT";
                }
                System.out.println("Received " + command + " for option code " + option_code);
            }
        });
        TerminalTypeOptionHandler terminalTypeOptionHandler =
                new TerminalTypeOptionHandler("VT100", true, false, true, false);
        try {
            telnetClient.addOptionHandler(terminalTypeOptionHandler);
        } catch (InvalidTelnetOptionException e) {
            System.err.println("Error registering option handlers: " + e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getResponse(String command) throws IOException {
        return (getResponses(command)).get(0);
    }

    public synchronized List<String> getResponses(String... commands) throws IOException {
        //TODO how to pass file to load (or where to put it)
        if (!telnetClient.isConnected()) {
            connectToController();
        }
        List<String> responses = new ArrayList<>();

        for (String command : commands) {
            // discard everything from stream
            InputStream in = telnetClient.getInputStream();
            System.out.println("Discarding stream content...");
            in.skip(in.available());

            PrintWriter out = new PrintWriter(telnetClient.getOutputStream(), true);
            out.println(command);

            System.out.println("Reading response...");
            StringBuilder response = new StringBuilder();
            if (command.toLowerCase().startsWith("save")) {
                //TODO extract to method
                // reading file from robot controller
                // reading and skipping everything until 0x17
                long timeout = 500; //TODO
                int skipped = skipUntil(in, new byte[]{0x17}, timeout);
                System.out.println("Skipped " + skipped + " bytes");
                // sending file transfer start sequence
                System.out.println("Sending start sequence");
                //TODO move such sequences to constants
                out.print(new char[]{0x02, 0x42, 0x20, 0x20, 0x20, 0x20, 0x30, 0x17});
                out.flush();
                System.out.println("Start sequence sent");

                // separating messages from file content
                boolean message = true;
                while (true) {
                    byte[] readBytes;
                    if (message) {
                        readBytes = readUntil(in, toBytes(0x05, 0x02), timeout);
                        System.out.print(new String(readBytes));
                        if (in.read() == 0x45) {
                            // skipping 0x17
                            in.skip(1);
                            break;
                        }
                    } else {
                        readBytes = readUntil(in, toBytes(0x17), timeout);
                        response.append(new String(readBytes));
                    }
                    message = !message;
                }

                System.out.println("Sending end sequence");
                out.print(new char[]{0x02, 0x45, 0x20, 0x20, 0x20, 0x20, 0x30, 0x17});
                out.flush();
                System.out.println("End sequence sent");
                // discarding everything until '>'
                skipped = skipUntil(in, new byte[]{'>'}, timeout);
                System.out.println("Skipped " + skipped + " bytes");
            } else if (command.toLowerCase().startsWith("load")) {
                //TODO extracting filename
                String filename = command.split("\\s+")[1];
                //TODO extract to method
                // sending file to robot controller
                // reading and skipping everything until 0x17
                long timeout = 500; //TODO
                int skipped = skipUntil(in, new byte[]{0x17}, timeout);
                System.out.println("Skipped " + skipped + " bytes");
                // sending file transfer start sequence
                System.out.println("Sending start sequence");
                //TODO move such sequences to constants
                out.print(new char[]{0x02, 0x41, 0x20, 0x20, 0x20, 0x20, 0x30, 0x17});
                out.flush();
                System.out.println("Start sequence sent");
                byte[][] terminators = new byte[2][];
                terminators[0] = toBytes(0x05, 0x02, 0x43, 0x17);
                terminators[1] = "Are you sure ? (Yes:1, No:0) \r\n".getBytes();
//                String fileToLoad = ".REALS\r\nfileLoaded = 42\r\n.END";
                String fileToLoad = new String(Files.readAllBytes(Paths.get(filename)));
                int chunkSize = 512;
                byte[][] chunks = splitIntoChunks(fileToLoad.getBytes(), chunkSize);
                for (byte[] chunk : chunks) {
                    int terminatorIndex = -1;
                    while (terminatorIndex != 0) {
                        ReadUntilResult result = readUntil(in, terminators, timeout);
                        System.out.println(new String(result.data)); //TODO
                        terminatorIndex = result.terminatorIndex;
                        if (terminatorIndex == 1) {
                            out.println("1\r\n");
                        }
                    }
                    out.print(new char[]{0x02, 0x43, 0x20, 0x20, 0x20, 0x20, 0x30});
                    out.print((new String(chunk)).toCharArray()); //TODO byte[] to char[]
                    out.print(new char[]{0x17});
                    out.flush();
                }
                out.print(new char[]{0x02, 0x43, 0x20, 0x20, 0x20, 0x20, 0x1a, 0x17});
                out.flush();
                System.out.println(new String(readUntil(in, toBytes(0x05, 0x02, 0x45, 0x17), timeout)));
                out.print(new char[]{0x02, 0x45, 0x20, 0x20, 0x20, 0x20, 0x30, 0x17});
                out.flush();
                System.out.println(new String(readUntil(in, new byte[]{'>'}, timeout)));
            } else {
                int readByte;
                //TODO '>' can be a part of response
                while ((readByte = in.read()) != '>') {
                    response.append((char) readByte);
                }
            }
            System.out.println("Response read!");

            System.out.println("--- Response:");
            System.out.println(response);
            System.out.println("---");

            responses.add(response.toString());
        }

        try {
            telnetClient.disconnect();
            System.out.println("Disconnected!");
        } catch (IOException e) {
            e.printStackTrace();
        }

        return responses;
    }

    private void connectToController() throws IOException {
        telnetClient.connect(hostname, port);
        loginToController();
    }

    private void loginToController() throws IOException {
        PrintWriter out = new PrintWriter(telnetClient.getOutputStream(), true);
        out.println(login);
        System.out.println("Login sent!");
        InputStream in = telnetClient.getInputStream();
        while (in.read() != '>') {
        }
        System.out.println("Logged in!");
        //TODO send "messages off" to disable messages output to the terminal
        //TODO send "screen on/off" (not sure yet) to disable paging of the terminal output
    }

    private void disconnect() {
        //TODO restore messages and screen switches
        try {
            telnetClient.disconnect();
        } catch (IOException e) {
            System.err.println("Error disconnecting from Telnet server: " + e.getMessage());
        }
    }

    /**
     * Skips all bytes from in until (and including) terminator.
     *
     * @param in
     * @param terminator
     * @param timeout
     * @return Number of bytes skipped
     * @throws IOException
     */
    private static int skipUntil(InputStream in, byte[] terminator, long timeout) throws IOException {
        return readUntil(in, terminator, timeout).length;
    }

    private static byte[] readUntil(InputStream in, byte[] terminator, long timeout) throws IOException {
        //TODO implement timeout
        List<Byte> bytes = new ArrayList<>();
        int index = 0;
        int[] buffer = new int[terminator.length];
        // reading until receiving ending sequence
        while (index < terminator.length) {
            int readByte = in.read();
            if (((byte) readByte) == terminator[index]) {
                buffer[index] = readByte;
                index++;
            } else {
                // returning buffered bytes to output list
                for (int i = 0; i < index; i++) {
                    bytes.add((byte) buffer[i]);
                }
                bytes.add((byte) readByte);
                index = 0;
            }
        }
        return toArrayOfPrimitives(bytes);
    }

    private static class ReadUntilResult {
        public byte[] data;
        public int terminatorIndex;
    }

    private static ReadUntilResult readUntil(InputStream in, byte[][] terminators, long timeout) throws IOException {
        //TODO implement timeout (and special value for infinite timeout)
        //TODO what if terminators is empty or one of terminators is empty
        //TODO assert not null input
        List<Byte> bytes = new ArrayList<>();
        int[] indexes = new int[terminators.length];
        int terminatorIndex;
        outerLoop:
        while (true) {
            int readByte = in.read();
            bytes.add((byte) readByte);
            for (int i = 0; i < terminators.length; i++) {
                terminatorIndex = i;
                if (((byte) readByte) == terminators[i][indexes[i]]) {
                    indexes[i]++;
                    if (indexes[i] >= terminators[i].length) {
                        break outerLoop;
                    }
                } else {
                    indexes[i] = 0;
                }
            }
        }

        ReadUntilResult result = new ReadUntilResult();
        result.data = toArrayOfPrimitives(bytes.subList(0, bytes.size() - terminators[terminatorIndex].length));
        result.terminatorIndex = terminatorIndex;

        return result;
    }

    private static byte[] toArrayOfPrimitives(List<Byte> bytes) {
        byte[] result = new byte[bytes.size()];
        Iterator<Byte> iterator = bytes.iterator();
        for (int i = 0; i < result.length; i++) {
            result[i] = iterator.next();
        }
        return result;
    }

    private static byte[] toBytes(int... ints) {
        byte[] result = new byte[ints.length];
        for (int i = 0; i < ints.length; i++) {
            result[i] = (byte) ints[i];
        }
        return result;
    }

    private static byte[][] splitIntoChunks(byte[] data, int chunkSize) {
        // ceiling division, same result as Math.ceil((double) data.length / chunkSize)
        int numberOfChunks = (data.length + chunkSize - 1) / chunkSize;
        byte[][] result = new byte[numberOfChunks][];
        for (int i = 0; i < numberOfChunks; i++) {
            int from = i * chunkSize;
            int to = from + chunkSize;
            if (to > data.length) {
                to = data.length;
            }
            result[i] = Arrays.copyOfRange(data, from, to);
        }
        return result;
    }

    public String getHostname() {
        return hostname;
    }

    public int getPort() {
        return port;
    }

    public String getLogin() {
        return login;
    }
}
