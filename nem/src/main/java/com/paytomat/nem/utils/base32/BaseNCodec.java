/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.paytomat.nem.utils.base32;


import java.util.Arrays;

/**
 * Abstract superclass for Base-N encoders and decoders.
 *
 * <p>
 * This class is thread-safe.
 * </p>
 *
 * @version $Id$
 */
public abstract class BaseNCodec {

    /**
     * Holds thread context so classes can be thread-safe.
     * <p>
     * This class is not itself thread-safe; each thread must allocate its own copy.
     *
     * @since 1.7
     */
    static class Context {

        /**
         * Place holder for the bytes we're dealing with for our based logic.
         * Bitwise operations store and extract the encoding or decoding from this variable.
         */
        int ibitWorkArea;

        /**
         * Place holder for the bytes we're dealing with for our based logic.
         * Bitwise operations store and extract the encoding or decoding from this variable.
         */
        long lbitWorkArea;

        /**
         * Buffer for streaming.
         */
        byte[] buffer;

        /**
         * Position where next character should be written in the buffer.
         */
        int pos;

        /**
         * Position where next character should be read from the buffer.
         */
        int readPos;

        /**
         * Boolean flag to indicate the EOF has been reached. Once EOF has been reached, this object becomes useless,
         * and must be thrown away.
         */
        boolean eof;

        /**
         * Variable tracks how many characters have been written to the current line. Only used when encoding. We use
         * it to make sure each encoded line never goes beyond lineLength (if lineLength &gt; 0).
         */
        int currentLinePos;

        /**
         * Writes to the buffer only occur after every 3/5 reads when encoding, and every 4/8 reads when decoding. This
         * variable helps track that.
         */
        int modulus;

        Context() {
        }

        /**
         * Returns a String useful for debugging (especially within a debugger.)
         *
         * @return a String useful for debugging.
         */
        @SuppressWarnings("boxing") // OK to ignore boxing here
        @Override
        public String toString() {
            return String.format("%s[buffer=%s, currentLinePos=%s, eof=%s, ibitWorkArea=%s, lbitWorkArea=%s, " +
                            "modulus=%s, pos=%s, readPos=%s]", this.getClass().getSimpleName(), Arrays.toString(buffer),
                    currentLinePos, eof, ibitWorkArea, lbitWorkArea, modulus, pos, readPos);
        }
    }

    /**
     * EOF
     *
     * @since 1.7
     */
    private static final int EOF = -1;

    private static final int DEFAULT_BUFFER_RESIZE_FACTOR = 2;

    /**
     * Defines the default buffer size - currently {@value}
     * - must be large enough for at least one encoded block+separator
     */
    private static final int DEFAULT_BUFFER_SIZE = 8192;

    /**
     * Mask used to extract 8 bits, used in decoding bytes
     */
    static final int MASK_8BITS = 0xff;

    /**
     * Byte used to pad output.
     */
    static final byte PAD_DEFAULT = '='; // Allow static access to default

    /**
     * @deprecated Use {@link #pad}. Will be removed in 2.0.
     */
    @Deprecated
    protected final byte PAD = PAD_DEFAULT; // instance variable just in case it needs to vary later

    final byte pad; // instance variable just in case it needs to vary later

    /**
     * Chunksize for encoding. Not used when decoding.
     * A value of zero or less implies no chunking of the encoded data.
     * Rounded down to nearest multiple of encodedBlockSize.
     */
    final int lineLength;

    /**
     * Note <code>lineLength</code> is rounded down to the nearest multiple of {@link }
     * If <code>chunkSeparatorLength</code> is zero, then chunking is disabled.
     *
     * @param encodedBlockSize     the size of an encoded block (e.g. Base64 = 4)
     * @param lineLength           if &gt; 0, use chunking with a length <code>lineLength</code>
     * @param chunkSeparatorLength the chunk separator length, if relevant
     * @param pad                  byte used as padding byte.
     */
    BaseNCodec(final int encodedBlockSize, final int lineLength, final int chunkSeparatorLength, final byte pad) {
        final boolean useChunking = lineLength > 0 && chunkSeparatorLength > 0;
        this.lineLength = useChunking ? (lineLength / encodedBlockSize) * encodedBlockSize : 0;

        this.pad = pad;
    }

    /**
     * Returns the amount of buffered data available for reading.
     *
     * @param context the context to be used
     * @return The amount of buffered data available for reading.
     */
    private int available(final Context context) {  // package protected for access from I/O streams
        return context.buffer != null ? context.pos - context.readPos : 0;
    }

    /**
     * Get the default buffer size. Can be overridden.
     *
     * @return {@link #DEFAULT_BUFFER_SIZE}
     */
    private int getDefaultBufferSize() {
        return DEFAULT_BUFFER_SIZE;
    }

    /**
     * Increases our buffer by the {@link #DEFAULT_BUFFER_RESIZE_FACTOR}.
     *
     * @param context the context to be used
     */
    private byte[] resizeBuffer(final Context context) {
        if (context.buffer == null) {
            context.buffer = new byte[getDefaultBufferSize()];
            context.pos = 0;
            context.readPos = 0;
        } else {
            final byte[] b = new byte[context.buffer.length * DEFAULT_BUFFER_RESIZE_FACTOR];
            System.arraycopy(context.buffer, 0, b, 0, context.buffer.length);
            context.buffer = b;
        }
        return context.buffer;
    }

    /**
     * Ensure that the buffer has room for <code>size</code> bytes
     *
     * @param size    minimum spare space required
     * @param context the context to be used
     * @return the buffer
     */
    byte[] ensureBufferSize(final int size, final Context context) {
        if ((context.buffer == null) || (context.buffer.length < context.pos + size)) {
            return resizeBuffer(context);
        }
        return context.buffer;
    }

    /**
     * Extracts buffered data into the provided byte[] array, starting at position bPos, up to a maximum of bAvail
     * bytes. Returns how many bytes were actually extracted.
     * <p>
     * Package protected for access from I/O streams.
     *
     * @param b       byte[] array to extract the buffered data into.
     * @param bAvail  amount of bytes we're allowed to extract. We may extract fewer (if fewer are available).
     * @param context the context to be used
     */
    private void readResults(final byte[] b, final int bAvail, final Context context) {
        if (context.buffer != null) {
            final int len = Math.min(available(context), bAvail);
            System.arraycopy(context.buffer, context.readPos, b, 0, len);
            context.readPos += len;
            if (context.readPos >= context.pos) {
                context.buffer = null; // so hasData() will return false, and this method can return -1
            }
        }
    }

    /**
     * Checks if a byte value is whitespace or not.
     * Whitespace is taken to mean: space, tab, CR, LF
     *
     * @param byteToCheck the byte to check
     * @return true if byte is whitespace, false otherwise
     */
    static boolean isWhiteSpace(final byte byteToCheck) {
        switch (byteToCheck) {
            case ' ':
            case '\n':
            case '\r':
            case '\t':
                return true;
            default:
                return false;
        }
    }

    /**
     * Decodes a byte[] containing characters in the Base-N alphabet.
     *
     * @param pArray A byte array containing Base-N character data
     * @return a byte array containing binary data
     */
    public byte[] decode(final byte[] pArray) {
        if (pArray == null || pArray.length == 0) {
            return pArray;
        }
        final Context context = new Context();
        decode(pArray, 0, pArray.length, context);
        decode(pArray, 0, EOF, context); // Notify decoder of EOF.
        final byte[] result = new byte[context.pos];
        readResults(result, result.length, context);
        return result;
    }

    /**
     * Encodes a byte[] containing binary data, into a byte[] containing characters in the alphabet.
     *
     * @param pArray a byte array containing binary data
     * @return A byte array containing only the base N alphabetic character data
     */
    public byte[] encode(final byte[] pArray) {
        if (pArray == null || pArray.length == 0) {
            return pArray;
        }
        return encode(pArray, pArray.length);
    }

    /**
     * Encodes a byte[] containing binary data, into a byte[] containing
     * characters in the alphabet.
     *
     * @param pArray a byte array containing binary data
     * @param length length of the subarray.
     * @return A byte array containing only the base N alphabetic character data
     * @since 1.11
     */
    private byte[] encode(final byte[] pArray, final int length) {
        if (pArray == null || pArray.length == 0) {
            return pArray;
        }
        final Context context = new Context();
        encode(pArray, 0, length, context);
        encode(pArray, 0, EOF, context); // Notify encoder of EOF.
        final byte[] buf = new byte[context.pos - context.readPos];
        readResults(buf, buf.length, context);
        return buf;
    }

    // package protected for access from I/O streams
    abstract void encode(byte[] pArray, int i, int length, Context context);

    // package protected for access from I/O streams
    abstract void decode(byte[] pArray, int i, int length, Context context);

    /**
     * Returns whether or not the <code>octet</code> is in the current alphabet.
     * Does not allow whitespace or pad.
     *
     * @param value The value to test
     * @return <code>true</code> if the value is defined in the current alphabet, <code>false</code> otherwise.
     */
    protected abstract boolean isInAlphabet(byte value);

    /**
     * Tests a given byte array to see if it contains only valid characters within the alphabet.
     * The method optionally treats whitespace and pad as valid.
     *
     * @param arrayOctet byte array to test
     * @param allowWSPad if <code>true</code>, then whitespace and PAD are also allowed
     * @return <code>true</code> if all bytes are valid characters in the alphabet or if the byte array is empty;
     * <code>false</code>, otherwise
     */
    public boolean isInAlphabet(final byte[] arrayOctet, final boolean allowWSPad) {
        for (final byte octet : arrayOctet) {
            if (!isInAlphabet(octet) &&
                    (!allowWSPad || (octet != pad) && !isWhiteSpace(octet))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests a given byte array to see if it contains any characters within the alphabet or PAD.
     * <p>
     * Intended for use in checking line-ending arrays
     *
     * @param arrayOctet byte array to test
     * @return <code>true</code> if any byte is a valid character in the alphabet or PAD; <code>false</code> otherwise
     */
    boolean containsAlphabetOrPad(final byte[] arrayOctet) {
        if (arrayOctet == null) {
            return false;
        }
        for (final byte element : arrayOctet) {
            if (pad == element || isInAlphabet(element)) {
                return true;
            }
        }
        return false;
    }

}