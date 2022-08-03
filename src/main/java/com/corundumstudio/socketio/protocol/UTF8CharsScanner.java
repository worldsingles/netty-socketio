/**
 * Copyright (c) 2012-2019 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio.protocol;

import io.netty.buffer.ByteBuf;

public class UTF8CharsScanner {

    /**
     * Lookup table used for determining which input characters need special
     * handling when contained in text segment.
     */
    static final int[] sInputCodes;
    static {
        /*
         * 96 would do for most cases (backslash is ascii 94) but if we want to
         * do lookups by raw bytes it's better to have full table
         */
        int[] table = new int[256];
        // Control chars and non-space white space are not allowed unquoted
        for (int i = 0; i < 32; ++i) {
            table[i] = -1;
        }
        // And then string end and quote markers are special too
        table['"'] = 1;
        table['\\'] = 1;
        sInputCodes = table;
    }

    /**
     * Additionally we can combine UTF-8 decoding info into similar data table.
     */
    static final int[] sInputCodesUtf8;
    static {
        int[] table = new int[sInputCodes.length];
        System.arraycopy(sInputCodes, 0, table, 0, sInputCodes.length);
        for (int c = 128; c < 256; ++c) {
            int code;

            // We'll add number of bytes needed for decoding
            if ((c & 0xE0) == 0xC0) { // 2 bytes (0x0080 - 0x07FF)
                code = 2;
            } else if ((c & 0xF0) == 0xE0) { // 3 bytes (0x0800 - 0xFFFF)
                code = 3;
            } else if ((c & 0xF8) == 0xF0) {
                // 4 bytes; double-char with surrogates and all...
                code = 4;
            } else {
                // And -1 seems like a good "universal" error marker...
                code = -1;
            }
            table[c] = code;
        }
        sInputCodesUtf8 = table;
    }

    private int getCharTailIndex(ByteBuf inputBuffer, int i) {
        int c = (int) inputBuffer.getByte(i) & 0xFF;
        switch (sInputCodesUtf8[c]) {
        case 2: // 2-byte UTF
            i += 2;
            break;
        case 3: // 3-byte UTF
            i += 3;
            break;
        case 4: // 4-byte UTF
            i += 4;
            break;
        default:
            i++;
            break;
        }
        return i;
    }

    public int getLength(ByteBuf inputBuffer, int start) {
        int len = 0;
        for (int i = start; i < inputBuffer.writerIndex();) {
            i = getCharTailIndex(inputBuffer, i);
            len++;
        }
        return len;
    }

    // public int getActualLength(ByteBuf inputBuffer, int length) {
    //     int len = 0;
    //     int start = inputBuffer.readerIndex();
    //     for (int i = inputBuffer.readerIndex(); i < inputBuffer.readableBytes() + inputBuffer.readerIndex();) {
    //         i = getCharTailIndex(inputBuffer, i);
    //         len++;
    //         if (length == len) {
    //             return i-start;
    //         }
    //     }
    //     throw new IllegalStateException();
    // }

    public enum Op {
        READ,
        DISPATCH,
        COUNT1,
        COUNT2,
        COUNT3,
        COUNT4,
        END
    }
    
    public int getActualLength(ByteBuf inputBuffer, int characterCount) {
        // Uses an abstract machine to scan inputBuffer to find the
        // byte length of characterCount characters.

        // The registers of the machine are:
        // stack: a 4 byte stack of bytes read from inputBuffer
        // control: a stack of operations to perform
        // i: the current byte index into inputBuffer for reading
        // c: the number of characters left to count

        // The operations of the machine are:
        // READ: reads a byte and pushes it on to stack
        // DISPATCH: examines the byte on the top of stack to determine how large this character is
        // COUNT1: handles a 1 byte character
        // COUNT2: handles a 2 byte character
        // COUNT3: handles a 3 byte character
        // COUNT4: handles a 4 byte character (which is two characters)
        // END: the end of the control stack, if there are more characters to count, does that, otherwise exits

        int stack = 0;
        // top most stack element for convience
        byte b = 0;
        java.util.LinkedList<Op> control = new java.util.LinkedList();
        control.addFirst(Op.END);
        int i = 0;
        int c = characterCount;
        
        while(true) {
            switch(control.remove()) {
            case END:
                if(c > 0){
                    control.addFirst(Op.END);
                    control.addFirst(Op.DISPATCH);
                    control.addFirst(Op.READ);
                } else if (c == 0) {
                    // i is the next byte after the last
                    return i - 1;
                } else {
                    throw new IllegalStateException("Too many characters");
                }
                break;
            case READ:
                if((inputBuffer.readerIndex() + 1) > inputBuffer.readableBytes())
                    throw new IllegalStateException("Not enough bytes");
                // acts like reading a byte and pushing it on to the stack
                b=inputBuffer.getByte(inputBuffer.readerIndex() + i);
                stack=(stack << 8) | b;
                break;
            case DISPATCH:
                // looks at the byte on the top of the stack (b0) and
                // determines how many bytes are in the character
                // based on the number of high bits
                // https://en.m.wikipedia.org/wiki/UTF-8#Description
                if( 0 == (b & (byte)-128)) {
                    control.addFirst(Op.COUNT1);
                } else if(-16 == (b & (byte) -16)) {
                    control.addFirst(Op.COUNT4);
                    control.addFirst(Op.READ);
                    control.addFirst(Op.READ);
                    control.addFirst(Op.READ);
                } else if(-32 == (b & (byte) -32)) {
                    control.addFirst(Op.COUNT3);
                    control.addFirst(Op.READ);
                    control.addFirst(Op.READ);
                } else if(-64 == (b & (byte)-64)) {
                    control.addFirst(Op.COUNT2);
                    control.addFirst(Op.READ);
                }
                break;
            case COUNT1:
                i++;
                c=c-java.lang.Character.charCount((int)b);
                break;
            case COUNT2:
                i=i+2;
                c=c-java.lang.Character.charCount(stack & 0xffff);
                break;
            case COUNT3:
                i=i+3;
                c=c-java.lang.Character.charCount(stack & 0xffffff);
                break;
            case COUNT4:
                i=i+4;
                // x 2 for surrogates
                c=c-(java.lang.Character.charCount(stack) * 2);
                break;
            }
        }
    }

}
