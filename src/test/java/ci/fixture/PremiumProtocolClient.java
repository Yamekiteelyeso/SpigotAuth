package ci.fixture;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.InflaterInputStream;

/**
 * Unauthenticated CI network test client, standard JDK only.
 * Exercises the complete TCP/RSA/AES/login-success transition against the
 * CI-only fake session service, NOT Microsoft authentication.
 */
public final class PremiumProtocolClient {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 25579;

    private PremiumProtocolClient() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("Usage: <name> <uuid> <send-uuid:true|false>");

        String name = args[0];
        UUID identity = UUID.fromString(args[1]);
        boolean sendUuidHint = Boolean.parseBoolean(args[2]);

        try (Socket socket = new Socket(HOST, PORT)) {
            socket.setSoTimeout(20_000);
            InputStream rawInput = socket.getInputStream();
            OutputStream rawOutput = socket.getOutputStream();

            ByteArrayOutputStream hello = new ByteArrayOutputStream();
            writeVarInt(hello, 0x00);
            writeVarInt(hello, 763);
            writeString(hello, "127.0.0.1");
            hello.write((PORT >>> 8) & 0xff);
            hello.write(PORT & 0xff);
            writeVarInt(hello, 2);
            sendPacket(rawOutput, hello.toByteArray());

            ByteArrayOutputStream start = new ByteArrayOutputStream();
            writeVarInt(start, 0x00);
            writeString(start, name);
            // Protocol 777 (26.3) requires a fixed 16-byte UUID; there
            // is no Optional<UUID> boolean used by the old 1.20.1 client.
            UUID advertised = sendUuidHint ? identity
                    : UUID.nameUUIDFromBytes(
                            ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            DataOutputStream d = new DataOutputStream(start);
            d.writeLong(advertised.getMostSignificantBits());
            d.writeLong(advertised.getLeastSignificantBits());
            sendPacket(rawOutput, start.toByteArray());

            byte[] request = readPacket(rawInput, false);
            ByteArrayInputStream req = new ByteArrayInputStream(request);
            int id = readVarInt(req);
            if (id != 0x01)
                throw new IllegalStateException(
                        "Expected encryption request 0x01, got 0x" + Integer.toHexString(id)
                                + " body=" + Arrays.toString(request));
            String serverId = readString(req);
            byte[] publicKeyBytes = readSizedBytes(req);
            byte[] token = readSizedBytes(req);

            if (!serverId.isEmpty() || token.length != 4)
                throw new IllegalStateException("Incorrect 1.20.1 encryption request.");

            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);

            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encryptedSecret = rsa.doFinal(secret);
            byte[] encryptedToken = rsa.doFinal(token);

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            writeVarInt(response, 0x01);
            writeSizedBytes(response, encryptedSecret);
            writeSizedBytes(response, encryptedToken);
            sendPacket(rawOutput, response.toByteArray());

            SecretKeySpec key = new SecretKeySpec(secret, "AES");
            IvParameterSpec iv = new IvParameterSpec(secret);
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, key, iv);
            InputStream encryptedInput = new CipherInputStream(rawInput, decrypt);

            boolean compression = false;
            for (int received = 0; received < 12; received++) {
                byte[] packet = readPacket(encryptedInput, compression);
                ByteArrayInputStream body = new ByteArrayInputStream(packet);
                int packetId = readVarInt(body);
                if (packetId == 0x03 && !compression) {
                    int threshold = readVarInt(body);
                    compression = true;
                    System.out.println("CI_CLIENT compression threshold=" + threshold);
                    continue;
                }
                if (packetId == 0x00) {
                    throw new IllegalStateException(
                            "Server disconnected " + name + ": " + readString(body));
                }
                if (packetId == 0x02) {
                    if (packet.length < 20)
                        throw new IllegalStateException("Truncated Login Success");
                    System.out.println("CI_CLIENT_LOGIN_SUCCESS name=" + name + " uuid=" + identity);
                    // A 1.20.1 player is normally placed shortly after Login
                    // Success. Stay online for a few ticks for PlayerJoinEvent.
                    Thread.sleep(2500);
                    return;
                }
                throw new IllegalStateException(
                        "Unexpected login packet 0x" + Integer.toHexString(packetId));
            }
            throw new IllegalStateException("No Login Success received.");
        }
    }

    private static byte[] readPacket(InputStream input, boolean compression) throws IOException {
        int length = readVarInt(input);
        if (length < 1 || length > 2_097_152)
            throw new IOException("Invalid packet length: " + length);
        byte[] raw = input.readNBytes(length);
        if (raw.length != length) throw new EOFException();
        if (!compression) return raw;

        ByteArrayInputStream packet = new ByteArrayInputStream(raw);
        int uncompressed = readVarInt(packet);
        byte[] payload = packet.readAllBytes();
        if (uncompressed == 0) return payload;
        try (InflaterInputStream inflater =
                     new InflaterInputStream(new ByteArrayInputStream(payload))) {
            byte[] expanded = inflater.readAllBytes();
            if (expanded.length != uncompressed)
                throw new IOException("Incorrect uncompressed packet size.");
            return expanded;
        }
    }

    private static void sendPacket(OutputStream stream, byte[] packet) throws IOException {
        writeVarInt(stream, packet.length);
        stream.write(packet);
        stream.flush();
    }

    private static void writeSizedBytes(OutputStream out, byte[] bytes) throws IOException {
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static byte[] readSizedBytes(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > 16384)
            throw new IOException("Invalid byte array size: " + length);
        byte[] data = in.readNBytes(length);
        if (data.length != length) throw new EOFException();
        return data;
    }

    private static String readString(InputStream in) throws IOException {
        return new String(readSizedBytes(in), StandardCharsets.UTF_8);
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        writeSizedBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        do {
            int b = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? b : b | 0x80);
        } while (value != 0);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("VarInt too big");
    }
}
