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

package com.fasterxml.aalto.in;

/**
 * Specialized implementation of PName: can be used for short Strings
 * that consists of at most 4 bytes. In general this means ascii-only
 * unqualified names.
 *<p>
 * The reason for such specialized classes is mostly space efficiency;
 * and to a lesser degree performance. Both are achieved for short
 * Strings by avoiding another level of indirection (via quad arrays)
 */
public final class PName1
    extends ByteBasedPName
{
    final int mQuad;

    PName1(String pname, String prefix, String ln, int hash, 
           int quad)
    {
        super(pname, prefix, ln, hash);
        mQuad = quad;
    }

    public PName createBoundName(NsBinding nsb)
    {
        PName1 newName = new PName1(mPrefixedName, mPrefix, mLocalName,
                                    mHash, mQuad);
        newName.mNsBinding = nsb;
        return newName;
    }

    public boolean equals(int quad1, int quad2)
    {
        return (quad1 == mQuad) && (quad2 == 0);
    }

    public boolean equals(int[] quads, int qlen)
    {
        return (qlen == 1 && quads[0] == mQuad);
    }

    public int getFirstQuad() {
        return mQuad;
    }

    public final int getLastQuad() {
        return mQuad;
    }

    public int getQuad(int index) {
        return (index == 0) ? mQuad : 0;
    }

    public int sizeInQuads() { return 1; }
}
