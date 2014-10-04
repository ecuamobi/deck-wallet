/**
 The MIT License (MIT)

 Copyright (c) 2013-2014 Valentin Konovalov

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */

package com.ecuamobi.deckwallet.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.spongycastle.asn1.sec.SECNamedCurves;
import org.spongycastle.asn1.x9.X9ECParameters;
import org.spongycastle.crypto.digests.RIPEMD160Digest;
import org.spongycastle.crypto.params.ECDomainParameters;
import org.spongycastle.math.ec.ECPoint;

import android.content.Context;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.ecuamobi.deckwallet.R;

public final class Util {
	private static final BigInteger LARGEST_PRIVATE_KEY = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
	private static final char[] BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
	private static final BigInteger BASE58_CHUNK_MOD = BigInteger.valueOf(0x5fa8624c7fba400L); //58^BASE58_CHUNK_DIGITS
	private static final int BASE58_CHUNK_DIGITS = 10;//how many base 58 digits fits in long
	private static final ECDomainParameters EC_PARAMS;
	
	static {
        X9ECParameters params = SECNamedCurves.getByName("secp256k1");
        EC_PARAMS = new ECDomainParameters(params.getCurve(), params.getG(), params.getN(), params.getH());
    }
	
	public static KeyPair generateBrainWifKey(boolean isPublicKeyCompressed, String seed) {
        try {
            MessageDigest digestSha = MessageDigest.getInstance("SHA-256");
            byte[] rawPrivateKey = new byte[isPublicKeyCompressed ? 38 : 37];
            rawPrivateKey[0] = (byte) 0x80;
            if (isPublicKeyCompressed) {
                rawPrivateKey[rawPrivateKey.length - 5] = 1;
            }
            byte[] secret;
            BigInteger privateKeyBigInteger;
            do {
                secret = digestSha.digest(seed.getBytes());
                privateKeyBigInteger = new BigInteger(1, secret);
                System.arraycopy(secret, 0, rawPrivateKey, 1, secret.length);
                digestSha.update(rawPrivateKey, 0, rawPrivateKey.length - 4);
                byte[] check = digestSha.digest(digestSha.digest());
                System.arraycopy(check, 0, rawPrivateKey, rawPrivateKey.length - 4, 4);
            }
            while (privateKeyBigInteger.compareTo(BigInteger.ONE) < 0 || privateKeyBigInteger.compareTo(LARGEST_PRIVATE_KEY) > 0 || !verifyChecksum(rawPrivateKey));
            return new KeyPair(new PrivateKeyInfo(PrivateKeyInfo.TYPE_WIF, encodeBase58(rawPrivateKey), privateKeyBigInteger, isPublicKeyCompressed));
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

	public static String encodeBase58(byte[] input) {
        if (input == null) {
            return null;
        }
        StringBuilder str = new StringBuilder((input.length * 350) / 256 + 1);
        BigInteger bn = new BigInteger(1, input);
        long rem;
        while (true) {
            BigInteger[] divideAndRemainder = bn.divideAndRemainder(BASE58_CHUNK_MOD);
            bn = divideAndRemainder[0];
            rem = divideAndRemainder[1].longValue();
            if (bn.compareTo(BigInteger.ZERO) == 0) {
                break;
            }
            for (int i = 0; i < BASE58_CHUNK_DIGITS; i++) {
                str.append(BASE58[(int) (rem % 58)]);
                rem /= 58;
            }
        }
        while (rem != 0) {
            str.append(BASE58[(int) (rem % 58)]);
            rem /= 58;
        }
        str.reverse();
        int nLeadingZeros = 0;
        while (nLeadingZeros < input.length && input[nLeadingZeros] == 0) {
            str.insert(0, BASE58[0]);
            nLeadingZeros++;
        }
        return str.toString();
    }
	
	public static boolean verifyChecksum(byte[] bytesWithChecksumm) {
        try {
            if (bytesWithChecksumm == null || bytesWithChecksumm.length < 5) {
                return false;
            }
            MessageDigest digestSha = MessageDigest.getInstance("SHA-256");
            digestSha.update(bytesWithChecksumm, 0, bytesWithChecksumm.length - 4);
            byte[] first = digestSha.digest();
            byte[] calculatedDigest = digestSha.digest(first);
            boolean checksumValid = true;
            for (int i = 0; i < 4; i++) {
                if (calculatedDigest[i] != bytesWithChecksumm[bytesWithChecksumm.length - 4 + i]) {
                    checksumValid = false;
                }
            }
            return checksumValid;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
	
	public static class PrivateKeyInfo {
        public static final int TYPE_WIF = 0;
        public static final int TYPE_MINI = 1;
        public static final int TYPE_BRAIN_WALLET = 2;
        public final int type;
        public final String privateKeyEncoded;
        public final BigInteger privateKeyDecoded;
        public final boolean isPublicKeyCompressed;

        public PrivateKeyInfo(int type, String privateKeyEncoded, BigInteger privateKeyDecoded, boolean isPublicKeyCompressed) {
            this.type = type;
            this.privateKeyEncoded = privateKeyEncoded;
            this.privateKeyDecoded = privateKeyDecoded;
            this.isPublicKeyCompressed = isPublicKeyCompressed;
        }
    }
	
	public static byte[] generatePublicKey(BigInteger privateKey, boolean compressed) {
        synchronized (EC_PARAMS) {
            ECPoint uncompressed = EC_PARAMS.getG().multiply(privateKey);
            ECPoint result = compressed ? new ECPoint.Fp(EC_PARAMS.getCurve(), uncompressed.getX(), uncompressed.getY(), true) : uncompressed;
            return result.getEncoded();
        }
    }
	
	public static byte[] sha256ripemd160(byte[] publicKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            //https://en.bitcoin.it/wiki/Technical_background_of_Bitcoin_addresses
            //1 - Take the corresponding public key generated with it (65 bytes, 1 byte 0x04, 32 bytes corresponding to X coordinate, 32 bytes corresponding to Y coordinate)
            //2 - Perform SHA-256 hashing on the public key
            byte[] sha256hash = sha256.digest(publicKey);
            //3 - Perform RIPEMD-160 hashing on the result of SHA-256
            RIPEMD160Digest ripemd160Digest = new RIPEMD160Digest();
            ripemd160Digest.update(sha256hash, 0, sha256hash.length);
            byte[] hashedPublicKey = new byte[20];
            ripemd160Digest.doFinal(hashedPublicKey, 0);
            return hashedPublicKey;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
	
	public static String publicKeyToAddress(byte[] publicKey) {
        try {
            byte[] hashedPublicKey = sha256ripemd160(publicKey);
            //4 - Add version byte in front of RIPEMD-160 hash (0x00 for Main Network)
            byte[] addressBytes = new byte[1 + hashedPublicKey.length + 4];
            addressBytes[0] = 0;
            System.arraycopy(hashedPublicKey, 0, addressBytes, 1, hashedPublicKey.length);
            //5 - Perform SHA-256 hash on the extended RIPEMD-160 result
            //6 - Perform SHA-256 hash on the result of the previous SHA-256 hash
            MessageDigest digestSha = MessageDigest.getInstance("SHA-256");
            digestSha.update(addressBytes, 0, addressBytes.length - 4);
            byte[] check = digestSha.digest(digestSha.digest());
            //7 - Take the first 4 bytes of the second SHA-256 hash. This is the address checksum
            //8 - Add the 4 checksum bytes from point 7 at the end of extended RIPEMD-160 hash from point 4. This is the 25-byte binary Bitcoin Address.
            System.arraycopy(check, 0, addressBytes, hashedPublicKey.length + 1, 4);
            return encodeBase58(addressBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
	
    public static void showToast(Context activity, int message) {
		showToast(activity, activity.getString(message));
	}
	
	public static void showToast(Context activity, CharSequence message) {
		showToast(activity, message.toString());
	}

	public static void showToast(Context activity, String message) {
		showToast(activity, message, Toast.LENGTH_SHORT);
	}

	public static void showToast(Context activity, int message, int duration) {
		showToast(activity, activity.getString(message), duration);
	}

	public static void showToast(Context activity, String message, int duration) {
		if (null == message || 0 == message.trim().length()
				|| message.contains(" null.")) {
			return;
		}

		LayoutInflater layoutInflater = (LayoutInflater) activity
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View layout = layoutInflater.inflate(R.layout.toast, null);

		TextView text = (TextView) layout.findViewById(R.id.texto);
		text.setText(Html.fromHtml(message));

		final Toast toast = new Toast(activity.getApplicationContext());
		toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
		toast.setDuration(duration);
		toast.setView(layout);
		toast.show();
	}
	
	public static void setUrlSpanForAddress(String domain, String address, SpannableStringBuilder builder) {
        int spanBegin = builder.toString().indexOf(domain);
        if (spanBegin >= 0) {
            URLSpan urlSpan = new URLSpan("http://" + domain + "/address/" + address);
            builder.setSpan(urlSpan, spanBegin, spanBegin + domain.length(), SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }

}
