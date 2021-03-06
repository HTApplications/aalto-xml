/* Woodstox Lite ("wool") XML processor
 *
 * Copyright (c) 2006- Tatu Saloranta, tatu.saloranta@iki.fi
 *
 * Licensed under the License specified in the file LICENSE which is
 * included with the source code.
 * You may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.fasterxml.aalto.out;

import java.io.*;

import javax.xml.stream.*;

import com.fasterxml.aalto.IoStreamException;
import com.fasterxml.aalto.XmlConsts;

/**
 * This is the generic implementation of {@link XmlWriter}, used if
 * the destination is byte-based {@link java.io.OutputStream}, and
 * encoding is UTF-8.
 */
public final class Utf8XmlWriter
    extends ByteXmlWriter
{

    /*
    ////////////////////////////////////////////////
    // Life-cycle
    ////////////////////////////////////////////////
     */

    public Utf8XmlWriter(WriterConfig cfg, OutputStream out)
    {
        super(cfg, out, OutputCharTypes.getUtf8CharTypes());
    }

    public int getHighestEncodable()
    {
        return XmlConsts.MAX_UNICODE_CHAR;
    }

    public void writeRaw(char[] cbuf, int offset, int len)
        throws IOException, XMLStreamException
    {
        if (_out == null || len == 0) {
            return;
        }
        if (mSurrogate != 0) {
            outputSurrogates(mSurrogate, cbuf[offset]);
            ++offset;
            --len;
        }

        len += offset; // now marks the end

        // !!! TODO: combine input+output length checks into just one

        main_loop:
        while (offset < len) {
            inner_loop:
            while (true) {
                int ch = (int) cbuf[offset];
                if (ch >= 0x80) {
                    break inner_loop;
                }
                // !!! TODO: fast writes
                if (_outputPtr >= _outputBufferLen) {
                    flushBuffer();
                }
                _outputBuffer[_outputPtr++] = (byte) ch;
                if (++offset >= len) {
                    break main_loop;
                }
            }
            char ch = cbuf[offset++];
            if (ch < 0x800) { // 2-byte?
                output2ByteChar(ch);
            } else {
                offset = outputMultiByteChar(ch, cbuf, offset, len);
            }
            if (_outputPtr >= _outputBufferLen) {
                flushBuffer();
            }
            _outputBuffer[_outputPtr++] = (byte)ch;
        }
    }

    protected WName doConstructName(String localName)
        throws XMLStreamException
    {
        // !!! TODO: optimize:
        try {
            byte[] b = localName.getBytes("UTF-8");
            return new ByteWName(localName, b);
        } catch (IOException ioe) {
            throw new IoStreamException(ioe);
        }
    }

    protected WName doConstructName(String prefix, String localName)
        throws XMLStreamException
    {
        // !!! TODO: optimize:
        try {
            byte[] b = (prefix+":"+localName).getBytes("UTF-8");
            return new ByteWName(prefix, localName, b);
        } catch (IOException ioe) {
            throw new IoStreamException(ioe);
        }
    }

    /*
    ////////////////////////////////////////////////////
    // Internal methods, low-level write
    ////////////////////////////////////////////////////
     */

    protected final void outputSurrogates(int surr1, int surr2)
        throws IOException, XMLStreamException
    {
        int c = calcSurrogate(surr1, surr2, " in content");
        if ((_outputPtr + 4) > _outputBufferLen) {
            flushBuffer();
        }
        _outputBuffer[_outputPtr++] = (byte) (0xf0 | (c >> 18));
        _outputBuffer[_outputPtr++] = (byte) (0x80 | ((c >> 12) & 0x3f));
        _outputBuffer[_outputPtr++] = (byte) (0x80 | ((c >> 6) & 0x3f));
        _outputBuffer[_outputPtr++] = (byte) (0x80 | (c & 0x3f));
    }

    final protected void output2ByteChar(int ch)
        throws IOException, XMLStreamException
    {
        if ((_outputPtr + 2) > _outputBufferLen) {
            flushBuffer();
        }
        byte[] bbuf = _outputBuffer;
        bbuf[_outputPtr++] = (byte) (0xc0 | (ch >> 6));
        bbuf[_outputPtr++] = (byte) (0x80 | (ch & 0x3f));
    }

    /**
     * Method called to output a character that is beyond range of
     * 1- and 2-byte UTF-8 encodings. This means it's either invalid
     * character, or needs to be encoded using 3- or 4-byte encoding.
     *
     * @param inputOffset Input pointer after character has been handled;
     *   either same as one passed in, or one more if a surrogate character
     *   was succesfully handled
     */
    final protected int outputMultiByteChar(int ch, char[] cbuf, int inputOffset, int inputLen)
        throws IOException, XMLStreamException
    {
        if (ch >= SURR1_FIRST) {
            if (ch <= SURR2_LAST) { // yes, outside of BMP
                // Do we have second part?
                if (inputOffset >= inputLen) { // nope... have to note down
                    mSurrogate = ch;
                } else {
                    outputSurrogates(ch, cbuf[inputOffset]);
                    ++inputOffset;
                }
                return inputOffset;
            }
            // Nope... but may be invalid
            if (ch >= 0xFFFE) { // 0xFFFE, 0xFFFF are invalid
                reportInvalidChar(ch);
            }
        } 
        if ((_outputPtr + 3) > _outputBufferLen) {
            flushBuffer();
        }
        byte[] bbuf = _outputBuffer;
        bbuf[_outputPtr++] = (byte) (0xe0 | (ch >> 12));
        bbuf[_outputPtr++] = (byte) (0x80 | ((ch >> 6) & 0x3f));
        bbuf[_outputPtr++] = (byte) (0x80 | (ch & 0x3f));
        return inputOffset;
    }

    final protected int outputStrictMultiByteChar(int ch, char[] cbuf, int inputOffset, int inputLen)
        throws IOException, XMLStreamException
    {
        if (ch >= SURR1_FIRST) {
            if (ch <= SURR2_LAST) { // yes, outside of BMP
                // Do we have second part?
                if (inputOffset >= inputLen) { // nope... have to note down
                    mSurrogate = ch;
                } else {
                    outputSurrogates(ch, cbuf[inputOffset]);
                    ++inputOffset;
                }
                return inputOffset;
            }
            // Nope... but may be invalid
            if (ch >= 0xFFFE) { // 0xFFFE, 0xFFFF are invalid
                reportInvalidChar(ch);
            }
        } 
        if ((_outputPtr + 3) > _outputBufferLen) {
            flushBuffer();
        }
        byte[] bbuf = _outputBuffer;
        bbuf[_outputPtr++] = (byte) (0xe0 | (ch >> 12));
        bbuf[_outputPtr++] = (byte) (0x80 | ((ch >> 6) & 0x3f));
        bbuf[_outputPtr++] = (byte) (0x80 | (ch & 0x3f));
        return inputOffset;
    }
}
