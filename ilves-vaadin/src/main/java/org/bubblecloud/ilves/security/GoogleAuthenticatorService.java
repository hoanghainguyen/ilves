package org.bubblecloud.ilves.security;

import org.apache.commons.codec.binary.Base32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

/**
 * Google authenticator service for two factor authentication with
 * Google Authenticator app.
 * @author Tommi S.E. Laukkanen
 */
public class GoogleAuthenticatorService {
    /**
     * Cryptographic hash function used to calculate the HMAC (Hash-based
     * Message Authentication Code). This implementation uses the SHA1 hash
     * function.
     */
    private static final String HMAC_HASH_FUNCTION = "HmacSHA1";

    /**
     * Generates secret key and MIME encodes it.
     *
     * @return the MIME encoded secret key
     */
    public static String generateSecretKey() {
        final int secretSize = 10;

        final byte[] buffer = new byte[secretSize];
        new Random().nextBytes(buffer);

        // Getting the key and converting it to Base32
        final Base32 codec = new Base32();
        final byte[] secretKey = Arrays.copyOf(buffer, secretSize);
        final byte[] bEncodedKey = codec.encode(secretKey);
        final String encodedKey = new String(bEncodedKey);
        return encodedKey;
    }

    /**
     * Get QR Code URL
     * @param user the user
     * @param host the host
     * @param secretKey the secret key
     * @return
     */
    public static String getQRBarcodeURL(
            final String user,
            final String host,
            final String secretKey) {
        String format = "https://chart.googleapis.com/chart?chs=200x200&chld=M%%7C0&cht=qr&chl=otpauth://totp/%s@%s%%3Fsecret%%3D%s";
        return String.format(format, user, host, secretKey);
    }

    /**
     * This method implements the algorithm specified in RFC 6238 to check if a
     * validation code is valid in a given instant of time for the given secret
     * key.
     *
     * @param secret    the Base32 encoded secret key.
     * @param codeString      the code to validate.
     * @return <code>true</code> if the validation code is valid,
     * <code>false</code> otherwise.
     */
    public static boolean checkCode(final String secret, final String codeString) {
        final long code;
        try {
            code = Long.parseLong(codeString);
        } catch(final NumberFormatException e) {
            return false;
        }
        final Base32 codec32 = new Base32();
        final byte[] decodedKey = codec32.decode(secret);
        final long timeWindow = System.currentTimeMillis() / 30000;
        final int window = 0;
        for (int i = -((window - 1) / 2); i <= window / 2; ++i) {
            final long hash = calculateCode(decodedKey, timeWindow + i);
            if (hash == code) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculates the verification code of the provided key at the specified
     * instant of time using the algorithm specified in RFC 6238.
     *
     * @param key the secret key in binary format.
     * @param tm  the instant of time.
     * @return the validation code for the provided key at the specified instant
     * of time.
     */
    private static int calculateCode(final byte[] key, final long tm) {
        final byte[] data = new byte[8];
        long value = tm;
        for (int i = 8; i-- > 0; value >>>= 8) {
            data[i] = (byte) value;
        }

        final SecretKeySpec signKey = new SecretKeySpec(key, HMAC_HASH_FUNCTION);
        try {
            final Mac mac = Mac.getInstance(HMAC_HASH_FUNCTION);
            mac.init(signKey);
            final byte[] hash = mac.doFinal(data);
            final int offset = hash[hash.length - 1] & 0xF;

            long truncatedHash = 0;
            for (int i = 0; i < 4; ++i) {
                truncatedHash <<= 8;
                truncatedHash |= (hash[offset + i] & 0xFF);
            }

            truncatedHash &= 0x7FFFFFFF;
            truncatedHash %= 1000000;

            return (int) truncatedHash;
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new RuntimeException(ex);
        }
    }

}