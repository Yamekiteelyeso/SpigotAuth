package ci.fixture;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
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
            writeVarInt(hello, 777);
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
                throw new IllegalStateException("Incorrect 26.3 encryption request.");

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
            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
            OutputStream encryptedOutput = new CipherOutputStream(rawOutput, encrypt);

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
                    // Minecraft 26.3 does not enter PLAY after Login Success.
                    // It must acknowledge LOGIN and finish CONFIGURATION.
                    sendEncrypted(encryptedOutput, new byte[]{0x03}, compression);

                    ByteArrayOutputStream info = new ByteArrayOutputStream();
                    writeVarInt(info, 0x00); // config: client_information
                    writeString(info, "en_us");
                    info.write(6);          // view distance
                    writeVarInt(info, 0);    // chat mode
                    info.write(1);          // chat colors
                    info.write(0x7f);       // all skin parts
                    writeVarInt(info, 1);    // right-handed
                    info.write(0);          // text filtering
                    info.write(1);          // server listings
                    writeVarInt(info, 0);    // all particles
                    sendEncrypted(encryptedOutput, info.toByteArray(), compression);

                    boolean finishedConfig = false;
                    for (int step = 0; step < 120; step++) {
                        byte[] cfg = readPacket(encryptedInput, compression);
                        ByteArrayInputStream configBody = new ByteArrayInputStream(cfg);
                        int configId = readVarInt(configBody);
                        if (configId == 0x0f) { // clientbound Known Packs
                            ByteArrayOutputStream ack = new ByteArrayOutputStream();
                            writeVarInt(ack, 0x07); // serverbound Known Packs
                            writeVarInt(ack, 0);    // send all registry data
                            sendEncrypted(encryptedOutput, ack.toByteArray(), compression);
                        } else if (configId == 0x04) { // keep-alive
                            ByteArrayOutputStream ack = new ByteArrayOutputStream();
                            writeVarInt(ack, 0x04);
                            ack.write(configBody.readNBytes(8));
                            sendEncrypted(encryptedOutput, ack.toByteArray(), compression);
                        } else if (configId == 0x05) { // ping
                            ByteArrayOutputStream ack = new ByteArrayOutputStream();
                            writeVarInt(ack, 0x05);
                            ack.write(configBody.readNBytes(4));
                            sendEncrypted(encryptedOutput, ack.toByteArray(), compression);
                        } else if (configId == 0x03) { // finish configuration
                            sendEncrypted(encryptedOutput, new byte[]{0x03}, compression);
                            finishedConfig = true;
                            break;
                        } else if (configId == 0x02) { // disconnect
                            throw new IllegalStateException(
                                    "Disconnected during configuration: " + readString(configBody));
                        }
                    }
                    if (!finishedConfig) {
                        throw new IllegalStateException("26.3 configuration did not finish.");
                    }
                    System.out.println("CI_CLIENT_CONFIG_FINISHED name=" + name);
                    Thread.sleep(2500);
                    return;
                }
                throw new IllegalStateException(
                        "Unexpected login packet 0x" + Integer.toHexString(packetId));
            }
            throw new IllegalStateException("No Login Success received.");
        }
    }

    private static void sendEncrypted(OutputStream stream, byte[] packet,
                                      boolean compression) throws IOException {
        if (!compression) {
            sendPacket(stream, packet);
            return;
        }
        // Small configuration response packets use compression dataLength=0.
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, 0);
        framed.write(packet);
        sendPacket(stream, framed.toByteArray());
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
