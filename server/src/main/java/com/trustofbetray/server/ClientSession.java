package com.trustofbetray.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Handles a single client TCP/WebSocket connection (RFC 6455 handshake, frame decoding, and JSON packet writing).
 */
public class ClientSession {
    public final int id;
    public final Socket socket;
    public final String ip;
    public volatile String uuid = "";
    public volatile String name;
    public volatile int partyId = 0;
    public volatile int matchId = 0;
    public volatile PlayerStorage.PlayerProfile profile = null;

    private final TrustOfBetrayServer server;
    private OutputStream out;
    private volatile boolean closed = false;

    public ClientSession(int id, Socket socket, String ip, TrustOfBetrayServer server) {
        this.id = id;
        this.socket = socket;
        this.ip = ip;
        this.name = "Ranger_" + id;
        this.server = server;
    }

    public void runLoop() {
        try {
            InputStream in = socket.getInputStream();
            out = socket.getOutputStream();

            // Read HTTP request header up to \r\n\r\n
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int matchState = 0;
            while (headerBuf.size() < 8192) {
                int b = in.read();
                if (b == -1) return;
                headerBuf.write(b);
                if ((matchState == 0 || matchState == 2) && b == '\r') matchState++;
                else if ((matchState == 1 || matchState == 3) && b == '\n') matchState++;
                else matchState = 0;
                if (matchState == 4) break;
            }

            String headerStr = new String(headerBuf.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = headerStr.split("\r\n");
            String wsKey = null;
            for (String line : lines) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim().toLowerCase();
                    String v = line.substring(idx + 1).trim();
                    if ("sec-websocket-key".equals(k)) {
                        wsKey = v;
                    }
                }
            }

            // If plain HTTP GET (status check), return server status JSON
            if (wsKey == null) {
                String statusJson = server.buildHttpStatusJson();
                byte[] body = statusJson.getBytes(StandardCharsets.UTF_8);
                String resp = "HTTP/1.1 200 OK\r\n" +
                              "Content-Type: application/json\r\n" +
                              "Content-Length: " + body.length + "\r\n" +
                              "Connection: close\r\n\r\n";
                out.write(resp.getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
                return;
            }

            // Complete RFC 6455 WebSocket Handshake
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] acceptHash = sha1.digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
            String acceptKey = Base64.getEncoder().encodeToString(acceptHash);
            String handshakeResp =
                "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(handshakeResp.getBytes(StandardCharsets.UTF_8));
            out.flush();

            server.onClientConnected(this);

            // Read WebSocket frames
            while (!closed && !socket.isClosed()) {
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;

                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;

                if (payloadLen == 126) {
                    int hi = in.read();
                    int lo = in.read();
                    if (hi == -1 || lo == -1) break;
                    payloadLen = ((hi & 0xFF) << 8) | (lo & 0xFF);
                } else if (payloadLen == 127) {
                    long len64 = 0;
                    for (int i = 0; i < 8; i++) {
                        int nb = in.read();
                        if (nb == -1) return;
                        len64 = (len64 << 8) | (nb & 0xFF);
                    }
                    payloadLen = len64;
                }

                if (payloadLen > 65536) break; // safety limit

                byte[] maskKey = null;
                if (masked) {
                    maskKey = readExact(in, 4);
                    if (maskKey == null) break;
                }

                byte[] payload = readExact(in, (int) payloadLen);
                if (payload == null) break;

                if (masked && maskKey != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= maskKey[i % 4];
                    }
                }

                if (opcode == 0x8) { // Close
                    break;
                } else if (opcode == 0x9) { // Ping -> Pong
                    sendRawFrame(0x8A, new byte[0]);
                } else if (opcode == 0x1 || opcode == 0x2) { // Text / Binary JSON
                    String text = new String(payload, StandardCharsets.UTF_8);
                    Map<String, Object> msg = MiniJson.parseObject(text);
                    if (msg != null) {
                        server.handleClientMessage(this, msg);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            close();
            server.onClientDisconnected(this);
        }
    }

    private byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int r = in.read(buf, read, len - read);
            if (r == -1) return null;
            read += r;
        }
        return buf;
    }

    public synchronized void sendJson(Map<String, Object> obj) {
        if (closed || out == null || socket.isClosed()) return;
        byte[] payload = MiniJson.stringify(obj).getBytes(StandardCharsets.UTF_8);
        sendRawFrame(0x81, payload);
    }

    private synchronized void sendRawFrame(int firstByte, byte[] payload) {
        if (closed || out == null || socket.isClosed()) return;
        try {
            int len = payload.length;
            if (len < 126) {
                out.write(firstByte);
                out.write(len);
            } else if (len < 65536) {
                out.write(firstByte);
                out.write(126);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            } else {
                out.write(firstByte);
                out.write(127);
                long l = len;
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((l >> (8 * i)) & 0xFF));
                }
            }
            if (len > 0) {
                out.write(payload);
            }
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
