/* Aalto XML processor
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

package com.fasterxml.aalto.async;

import java.io.IOException;
import javax.xml.stream.XMLStreamException;


import com.fasterxml.aalto.ErrorConsts;
import com.fasterxml.aalto.in.*;
import com.fasterxml.aalto.util.DataUtil;

/**
 * This is the base class for asynchronous (non-blocking) xml
 * scanners. Due to basic complexity of async approach, character-based
 * doesn't make much sense, so only byte-based input is supported.
 */
public abstract class AsyncByteScanner
    extends ByteBasedScanner
{
    /*
    ////////////////////////////////////////////////
    // Generic/public constants
    ////////////////////////////////////////////////
     */

    /**
     * As per javadocs of {@link javax.xml.stream.XMLStreamConstants},
     * event codes 0 through 256 (inclusive?) are reserved by the Stax
     * specs, so we'll use the next available code.
     */
    public final static int EVENT_INCOMPLETE = 257;

    /*
    ////////////////////////////////////////////////
    // State consts
    ////////////////////////////////////////////////
     */

    /**
     * Default starting state for many events/contexts -- nothing has been
     * seen so far, no  event incomplete. Not used for all event types.
     */
    final static int STATE_DEFAULT = 0;

    // // // States for prolog/epilog major state:

    /**
     * State in which a less-than sign has been seen
     */
    final static int STATE_PROLOG_SEEN_LT = 1; // "<" seen
    final static int STATE_PROLOG_DECL = 2; // "<!" seen

    // // // States for in-tree major state:

    final static int STATE_TREE_SEEN_LT = 1; // "<" seen
    final static int STATE_TREE_SEEN_AMP = 2; // "&" seen
    final static int STATE_TREE_SEEN_EXCL = 3; // "<!" seen
    final static int STATE_TREE_SEEN_SLASH = 4; // "</" seen

    // // // States within event types (STATE_DEFAULT is shared):

    // For CHARACTERS, default is the basic state
    /**
     * This state is set when an entity is being handled
     */
    final static int STATE_TEXT_ENTITY = 1;
    final static int STATE_TEXT_BRACKET1 = 2;
    final static int STATE_TEXT_BRACKET2 = 3;

    // For comments, STATE_DEFAULT means "<!-" has been seen
    final static int STATE_COMMENT_CONTENT = 1; // "<!--"
    final static int STATE_COMMENT_HYPHEN = 2; // content, and one '-'
    final static int STATE_COMMENT_HYPHEN2 = 3; // content, "--"

    // For PIs, default means that '<?' has been seen, nothing else

    // (note: funny ordering, starting with "quick path" entries)
    final static int STATE_PI_AFTER_TARGET = 1; // "<?", target ?>
    final static int STATE_PI_AFTER_TARGET_WS = 2; // "<?", target, ws
    final static int STATE_PI_AFTER_TARGET_QMARK = 3; // "<?", target, "?"
    final static int STATE_PI_IN_TARGET = 4; // "<?", part of target
    final static int STATE_PI_IN_DATA = 5; // "<?", target, ws, part of data

    // For start element, DEFAULT means that only '<' has been seen
    final static int STATE_SE_ELEM_NAME = 1; // "<" and part of name
    final static int STATE_SE_SPACE_OR_END = 2; // after elem name or attr, but need space
    final static int STATE_SE_SPACE_OR_ATTRNAME = 3; // after elem/attr and space
    
    final static int STATE_SE_ATTR_NAME = 4; // in attribute name
    final static int STATE_SE_SPACE_OR_EQ = 5;
    final static int STATE_SE_SPACE_OR_ATTRVALUE = 6;
    final static int STATE_SE_ATTR_VALUE_NORMAL = 7;
    final static int STATE_SE_ATTR_VALUE_NSDECL = 8;
    final static int STATE_SE_SEEN_SLASH = 9;
    final static int STATE_SE_ENTITY = 10;

    // For END_ELEMENT, default means we are parsing name
    final static int STATE_EE_NEED_GT = 1;


    final static int STATE_ENT_SEEN_HASH = 1; // seen &#
    final static int STATE_ENT_SEEN_HASHX = 2; // seen &#x
    final static int STATE_ENT_IN_DEC_DIGIT = 3; // seen &# and 1 or more decimals
    final static int STATE_ENT_IN_HEX_DIGIT = 4; // seen &#x and 1 or more hex digits
    final static int STATE_ENT_IN_NAME = 5; // seen & and part of the name


    /*
    ////////////////////////////////////////////////
    // Input buffer handling
    ////////////////////////////////////////////////
     */

    /**
     * This buffer is actually provided by caller
     */
    protected byte[] _inputBuffer;

    /**
     * In addition to current buffer pointer, and end pointer,
     * we will also need to know number of bytes originally
     * contained. This is needed to correctly update location
     * information when the block has been completed.
     */
    protected int mOrigBufferLen;

    /*
    ////////////////////////////////////////////////
    // General state tracking
    ////////////////////////////////////////////////
     */

    /**
     * Due to asynchronous nature of parsing, we may know what
     * event we are trying to parse, even if it's not yet
     * complete. Type of that event is stored here.
     */
    protected int mNextEvent = EVENT_INCOMPLETE;

    /**
     * In addition to the event type, there is need for additional
     * state information
     */
    protected int mState = STATE_DEFAULT;

    /**
     * For token/state combinations that are 'shared' between
     * events (or embedded in them), this is where the surrounding
     * event state is retained.
     */
    protected int mSurroundingEvent = EVENT_INCOMPLETE;

    /**
     * There are some multi-byte combinations that must be handled
     * as a unit: CR+LF linefeeds, multi-byte UTF-8 characters, and
     * multi-character end markers for comments and PIs.
     * Since they can be split across input buffer
     * boundaries, first byte(s) may need to be temporarily stored.
     *<p>
     * If so, this int will store byte(s), in little-endian format
     * (that is, first pending byte is at 0x000000FF, second [if any]
     * at 0x0000FF00, and third at 0x00FF0000). This can be
     * (and is) used to figure out actual number of bytes pending,
     * for multi-byte (UTF-8) character decoding.
     *<p>
     * Note: it is assumed that if value is 0, there is no data.
     * Thus, if 0 needed to be added pending, it has to be masked.
     */
    protected int mPendingInput = 0;

    /*
    ////////////////////////////////////////////////
    // Name parsing state
    ////////////////////////////////////////////////
     */

    /**
     * Number of complete quads parsed for current name (quads
     * themselves are stored in {@link #mQuadBuffer}).
     */
    protected int mQuadCount;

    /**
     * Bytes parsed for the current, incomplete, quad
     */
    protected int _currQuad;

    /**
     * Number of bytes pending/buffered, stored in {@link #_currQuad}
     */
    protected int _currQuadBytes = 0;

    /*
    ////////////////////////////////////////////////
    // (Start) element parsing state
    ////////////////////////////////////////////////
     */

    protected boolean mElemAllNsBound;

    protected boolean mElemAttrCount;

    protected byte mElemAttrQuote;

    protected PName mElemAttrName;

    protected int mElemAttrPtr;

    /*
    ////////////////////////////////////////////////
    // Instance construction
    ////////////////////////////////////////////////
     */

    public AsyncByteScanner(ReaderConfig cfg)
    {
        super(cfg);
    }

    @Override
    public String toString()
    {
        return "[curr="+_currToken+" next="+mNextEvent+", state = "+mState+"]";
    }

    /*
    ////////////////////////////////////////////////
    // Input feeding
    ////////////////////////////////////////////////
     */

    public final boolean hasInput() { return _inputPtr < _inputEnd; }

    public void addInput(byte[] buf, int start, int len)
        throws XMLStreamException
    {
        // Must not have remaining input
        if (hasInput()) {
            throw new XMLStreamException("Still have "+(_inputEnd - _inputPtr)+" unread bytes");
        }
        // Time to update pointers first
        mPastBytes += mOrigBufferLen;
        mRowStartOffset -= mOrigBufferLen;

        // And then update buffer settings
        _inputBuffer = buf;
        _inputPtr = start;
        _inputEnd = start+len;
        mOrigBufferLen = len;
    }

    /**
     * Since the async scanner has no access to whatever passes content,
     * there is no input source in same sense as with blocking scanner;
     * and there is nothing to close.
     */
    @Override
    protected void _closeSource()
        throws IOException
    {
        // nothing to do, we are done.
    }

    /*
    ////////////////////////////////////////////////
    // Implementation of parsing API
    ////////////////////////////////////////////////
     */

    public final int nextFromProlog(boolean isProlog)
        throws XMLStreamException
    {
        // Had fully complete event? Need to reset state etc:
        if (_currToken != EVENT_INCOMPLETE) {
            assert(!_tokenIncomplete); // only CHARACTERS can be
            _currToken = mNextEvent = EVENT_INCOMPLETE;
            mState = STATE_DEFAULT;
        }

        // Ok, do we know which event it will be?
        if (mNextEvent == EVENT_INCOMPLETE) { // nope
            // First: did we have a lone CR at the end of the buffer?
            if (mPendingInput != 0) { // yup
                if (!handlePartialCR()) {
                    return _currToken;
                }
            }
            while (mState == STATE_DEFAULT) {
                if (_inputPtr >= _inputEnd) { // no more input available
                    return _currToken;
                }
                byte b = _inputBuffer[_inputPtr++];

                /* Really should get white space or '<'... anything else is
                 * pretty much an error.
                 */
                if (b == BYTE_LT) { // root element, comment, proc instr?
                    mState = STATE_PROLOG_SEEN_LT;
                    break;
                }
                if (b == BYTE_SPACE || b == BYTE_CR
                    || b == BYTE_LF || b == BYTE_TAB) {
                    // Prolog/epilog ws is to be skipped, not part of Infoset
                    if (!asyncSkipSpace()) { // ran out of input?
                        return _currToken;
                    }
                } else {
                    reportPrologUnexpChar(isProlog, decodeCharForError(b), null);
                }
            }

            if (mState == STATE_PROLOG_SEEN_LT) {
                if (_inputPtr >= _inputEnd) {
                    return _currToken;
                }
                byte b = _inputBuffer[_inputPtr++];
                if (b == BYTE_EXCL) { // comment or DOCTYPE declaration?
                    mState = STATE_PROLOG_DECL;
                    return handlePrologDeclStart(isProlog);
                }
                if (b == BYTE_QMARK) { // PI
                    mNextEvent = PROCESSING_INSTRUCTION;
                    mState = STATE_DEFAULT;
                    return handlePI();
                }
                if (b == BYTE_SLASH || !isProlog) {
                    reportPrologUnexpChar(isProlog, decodeCharForError(b), " (unbalanced start/end tags?)");
                }
                return handleStartElementStart(b);
            }
            if (mState == STATE_PROLOG_DECL) {
                return handlePrologDeclStart(isProlog);
            }
            // should never have anything else...
            return throwInternal();
        }

        // At this point, we do know the event type
        switch (mNextEvent) {
        case START_ELEMENT:
            return handleStartElement();
        case PROCESSING_INSTRUCTION:
            return handlePI();
        case COMMENT:
            return handleComment();
        case DTD:
            return handleDTD();
        }
        return throwInternal(); // should never get here
    }

    public int nextFromTree()
        throws XMLStreamException
    {
        // Had a fully complete event? Need to reset state:
        if (_currToken != EVENT_INCOMPLETE) {
            /* First, need to handle some complications arising from
             * empty elements, and namespace binding/unbinding:
             */
            if (_currToken == START_ELEMENT) {
                if (_isEmptyTag) {
                    --_depth;
                    return (_currToken = END_ELEMENT);
                }
            } else if (_currToken == END_ELEMENT) {
                _currElem = _currElem.getParent();
                // Any namespace declarations that need to be unbound?
                while (_lastNsDecl != null && _lastNsDecl.getLevel() >= _depth) {
                    _lastNsDecl = _lastNsDecl.unbind();
                }
            }

            /* Only CHARACTERS can remain incomplete: this happens iff
             * first character is decoded, but coalescing mode is NOT
             * set. Skip can not therefore block, nor will add pending
             * input.
             */
            if (_tokenIncomplete) {
                skipCharacters();
                _tokenIncomplete = false;
            }
            _currToken = mNextEvent = EVENT_INCOMPLETE;
            mState = STATE_DEFAULT;
        }

        // Don't yet know the type?
        if (mNextEvent == EVENT_INCOMPLETE) {
            if (mState == STATE_DEFAULT) {
                /* We can only have pending input for (incomplete)
                 * CHARACTERS event.
                 */
                if (mPendingInput != 0) { // CR, or multi-byte?
                    mNextEvent = CHARACTERS;
                    return startCharactersPending();
                }
                if (_inputPtr >= _inputEnd) { // nothing we can do?
                    return _currToken; // i.e. EVENT_INCOMPLETE
                }
                byte b = _inputBuffer[_inputPtr++];
                if (b == BYTE_LT) { // root element, comment, proc instr?
                    mState = STATE_TREE_SEEN_LT;
                } else if (b == BYTE_AMP) {
                    mState = STATE_TREE_SEEN_AMP;
                } else {
                    mNextEvent = CHARACTERS;
                    return startCharacters(b);
                }
            }

            if (_inputPtr >= _inputEnd) {
                return _currToken; // i.e. EVENT_INCOMPLETE
            }
            if (mState == STATE_TREE_SEEN_LT) {
                // Ok, so we've just seen the less-than char...
                byte b = _inputBuffer[_inputPtr++];
                if (b == BYTE_EXCL) { // comment or CDATA
                    mState = STATE_TREE_SEEN_EXCL;
                } else if (b == BYTE_QMARK) {
                    mNextEvent = PROCESSING_INSTRUCTION;
                    mState = STATE_DEFAULT;
                    return handlePI();
                } else if (b == BYTE_SLASH) {
                    return handleEndElementStart();
                } else {
                    // Probably start element -- need to retain first char tho
                    return handleStartElementStart(b);
                }
            } else if (mState == STATE_TREE_SEEN_AMP) {
                return handleEntityStart(EVENT_INCOMPLETE, _inputBuffer[_inputPtr++]);
            }
                
            if (mState == STATE_TREE_SEEN_EXCL) {
                if (_inputPtr >= _inputEnd) {
                    return _currToken; // i.e. EVENT_INCOMPLETE
                }
                byte b = _inputBuffer[_inputPtr++];
                // Comment or CDATA?
                if (b == BYTE_HYPHEN) { // Comment
                    mNextEvent = COMMENT;
                    mState = STATE_DEFAULT;
                } else if (b == BYTE_LBRACKET) { // CDATA
                    mNextEvent = CDATA;
                    mState = STATE_DEFAULT;
                } else {
                    reportTreeUnexpChar(decodeCharForError(b), " (expected either '-' for COMMENT or '[CDATA[' for CDATA section)");
                }
            } else {
                throwInternal();
            }
        }

        /* We know the type; event is usually partially processed
         * and needs to be completely read.
         */
        switch (mNextEvent) {
        case START_ELEMENT:
            return handleStartElement();
        case END_ELEMENT:
            return handleEndElement();
        case PROCESSING_INSTRUCTION:
            return handlePI();
        case COMMENT:
            return handleComment();
        case CDATA:
            return handleCData();
        case CHARACTERS:
            if (!_cfgLazyParsing) {
                // !!! TBI: how would non-lazy mode work?
                if (_cfgCoalescing) {
                    return finishCharactersCoalescing();
                }
            }
            // Otherwise, should not get here
            throwInternal();
        }

        return throwInternal(); // never gets here
    }

    /*
    ////////////////////////////////////////////////
    // Second-level parsing
    ////////////////////////////////////////////////
    */

    /**
     * @return True, if was able to skip through the space and find
     *   a non-space byte; false if reached end-of-buffer
     */
    private boolean asyncSkipSpace()
        throws XMLStreamException
    {
        while (_inputPtr < _inputEnd) {
            byte b = _inputBuffer[_inputPtr];
            if ((b & 0xFF) > INT_SPACE) {
                return true;
            }
            ++_inputPtr;
            if (b == BYTE_LF) {
                markLF();
            } else if (b == BYTE_CR) {
                if (_inputPtr >= _inputEnd) {
                    mPendingInput = INT_CR;
                    break;
                }
                if (_inputBuffer[_inputPtr] == BYTE_LF) {
                    ++_inputPtr;
                }
                markLF();
            } else if (b != BYTE_SPACE && b != BYTE_TAB) {
                throwInvalidSpace(b);
            }
        }
        return false;
    }

    private final int handlePrologDeclStart(boolean isProlog)
        throws XMLStreamException
    {
        if (_inputPtr >= _inputEnd) { // nothing we can do?
            return EVENT_INCOMPLETE;
        }
        byte b = _inputBuffer[_inputPtr++];
        // So far, we have seen "<!", need to know if it's DTD or COMMENT 
        if (b == BYTE_HYPHEN) {
            mNextEvent = COMMENT;
            mState = STATE_DEFAULT;
            return handleComment();
        }
        if (b == BYTE_D) {
            mNextEvent = DTD;
            mState = STATE_DEFAULT;
            return handleDTD();
        }
        reportPrologUnexpChar(isProlog, decodeCharForError(b), " (expected '-' for COMMENT)");
        return EVENT_INCOMPLETE; // never gets here
    }

    private int handlePI()
        throws XMLStreamException
    {
        // Most common case fisrt:
        if (mState == STATE_PI_IN_DATA) {
            return parsePIData();
        }

        main_loop:
        while (true) {
            if (_inputPtr >= _inputEnd) {
                return EVENT_INCOMPLETE;
            }
            switch (mState) {
            case STATE_DEFAULT:
                _textBuilder.resetWithEmpty();
                _tokenName = parseNewName(_inputBuffer[_inputPtr++]);
                if (_tokenName == null) {
                    mState = STATE_PI_IN_TARGET;
                    return EVENT_INCOMPLETE;
                }
                checkPITargetName(_tokenName);
                mState = STATE_PI_AFTER_TARGET;
                // fall through
            case STATE_PI_AFTER_TARGET:
                // Need ws or "?>"
                {
                    byte b = _inputBuffer[_inputPtr++];
                    if (b == BYTE_QMARK) {
                        // Quick check, can we see '>' as well? All done, if so
                        if (_inputPtr < _inputEnd
                            && _inputBuffer[_inputPtr] == BYTE_GT) {
                            ++_inputPtr;
                            break main_loop; // means we are done
                        }
                        // If not (whatever reason), let's move to check state
                        mState = STATE_PI_AFTER_TARGET_QMARK;
                        break;
                    }
                    if (b == BYTE_SPACE || b == BYTE_CR
                               || b == BYTE_LF || b == BYTE_TAB) {
                        if (!asyncSkipSpace()) { // ran out of input?
                            mState = STATE_PI_AFTER_TARGET_WS;
                            return EVENT_INCOMPLETE;
                        }
                        // Quick check, perhaps we'll see end marker?
                        if ((_inputPtr+1) < _inputEnd
                            && _inputBuffer[_inputPtr] == BYTE_QMARK
                            && _inputBuffer[_inputPtr+1] == BYTE_GT) {
                            _inputPtr += 2;
                            break main_loop; // means we are done
                        }
                        // If not, we'll move to 'data' portion of PI
                        mState = STATE_PI_IN_DATA;
                        return parsePIData();
                    }
                    // Otherwise, it's an error
                    reportMissingPISpace(decodeCharForError(b));
                }
                // fall through
            case STATE_PI_AFTER_TARGET_WS:
                if (!asyncSkipSpace()) { // ran out of input?
                    return EVENT_INCOMPLETE;
                }
                // Can just move to "data" portion right away
                mState = STATE_PI_IN_DATA;
                return parsePIData();
            case STATE_PI_AFTER_TARGET_QMARK:
                {
                    // Must get '>' following '?' we saw right after name
                    byte b = _inputBuffer[_inputPtr++];
                    // Otherwise, it's an error
                    if (b != BYTE_GT) {
                        reportMissingPISpace(decodeCharForError(b));
                    }
                }
                // but if it's ok, we are done
                break main_loop;
            case STATE_PI_IN_TARGET:
                _tokenName = parsePName();
                if (_tokenName == null) {
                    return EVENT_INCOMPLETE;
                }
                checkPITargetName(_tokenName);
                mState = STATE_PI_AFTER_TARGET;
                break;
                
            default:
                return throwInternal();
            }
        }
        
        mState = STATE_DEFAULT;
        mNextEvent = EVENT_INCOMPLETE;
        return PROCESSING_INSTRUCTION;
    }

    private int handleComment()
        throws XMLStreamException
    {
        if (mState == STATE_COMMENT_CONTENT) {
            return parseCommentContents();
        }

        if (_inputPtr >= _inputEnd) {
            return EVENT_INCOMPLETE;
        }
        byte b = _inputBuffer[_inputPtr++];
        
        if (mState == STATE_DEFAULT) {
            if (b != BYTE_HYPHEN) {
                reportTreeUnexpChar(decodeCharForError(b), " (expected '-' for COMMENT)");
            }
            mState = STATE_COMMENT_CONTENT;
            _textBuilder.resetWithEmpty();
            return parseCommentContents();
        }
        if (mState == STATE_COMMENT_HYPHEN2) {
            // We are almost done, just need to get '>' at the end
            if (b != BYTE_GT) {
                reportDoubleHyphenInComments();
            }
            mState = STATE_DEFAULT;
            mNextEvent = EVENT_INCOMPLETE;
            return COMMENT;
        }
        return throwInternal();
    }

    protected abstract int parseCommentContents()
        throws XMLStreamException;

    protected abstract int parsePIData()
        throws XMLStreamException;

    /**
     * Method called to initialize state for CHARACTERS event, after
     * just a single byte has been seen. What needs to be done next
     * depends on whether coalescing mode is set or not: if it is not
     * set, just a single character needs to be decoded, after which
     * current event will be incomplete, but defined as CHARACTERS.
     * In coalescing mode, the whole content must be read before
     * current event can be defined. The reason for difference is
     * that when <code>XMLStreamReader.next()</code> returns, no
     * blocking can occur when calling other methods.
     *
     * @return Event type detected; either CHARACTERS, if at least
     *   one full character was decoded (and can be returned),
     *   EVENT_INCOMPLETE if not (part of a multi-byte character
     *   split across input buffer boundary)
     */
    protected abstract int startCharacters(byte b)
        throws XMLStreamException;

    /**
     * This method gets called, if the first character of a
     * CHARACTERS event could not be fully read (multi-byte,
     * split over buffer boundary). If so, there is some
     * pending data to be handled.
     */
    protected abstract int startCharactersPending()
        throws XMLStreamException;

    protected abstract int finishCharactersCoalescing()
        throws XMLStreamException;

    private int handleCData()
        throws XMLStreamException
    {
        // !!! TBI
        return 0;
    }

    private int handleDTD()
        throws XMLStreamException
    {
        // !!! TBI
        return 0;
    }

    /**
     * @param surroundingEvent Context (next event at the time
     *   ampersand was encountered) in which entity is found. Will
     *   often be the next event set after entity is resolve.
     */
    protected int handleEntityStart(int surroundingEvent, byte b)
        throws XMLStreamException
    {
        mNextEvent = ENTITY_REFERENCE;
        mSurroundingEvent = surroundingEvent;

        if (b == BYTE_HASH) {
            mState = STATE_ENT_SEEN_HASH;
            return handleEntity();
        }

        PName n = parseNewName(b);
        if (n != null) {
            return handleGeneralEntity(n);
        }
        mState = STATE_ENT_IN_NAME;
        return EVENT_INCOMPLETE;
    }

    protected int handleEntity()
        throws XMLStreamException
    {
        // !!! TBI
        return 0;
    }

    protected final int handleGeneralEntity(PName entityName)
    {
        String name = entityName.getPrefixedName();
        if (name == "amp") {
        } else if (name == "lt") {
        } else if (name == "apos") {
        } else if (name == "quot") {
        } else if (name == "gt") {
        }

        // !!! TBI
        return EVENT_INCOMPLETE;
    }

    /**
     * Method called when '<' and (what appears to be) a name
     * start character have been seen.
     */
    protected int handleStartElementStart(byte b)
        throws XMLStreamException
    {
        PName elemName = parseNewName(b);
        mNextEvent = START_ELEMENT;
        if (elemName == null) {
            mState = STATE_SE_ELEM_NAME;
            return EVENT_INCOMPLETE;
        }
        initStartElement(elemName);
        return handleStartElement();
    }

    protected int handleStartElement()
        throws XMLStreamException
    {
        main_loop:
        while (true) {
            if (_inputPtr >= _inputEnd) {
                return EVENT_INCOMPLETE;
            }

            byte b;
            int c;

            switch (mState) {
            case STATE_SE_ELEM_NAME:
                {
                    PName elemName = parsePName();
                    if (elemName == null) {
                        return EVENT_INCOMPLETE;
                    }
                    initStartElement(elemName);
                }
                if (_inputPtr >= _inputEnd) {
                    return EVENT_INCOMPLETE;
                }
                // Fall through to next state
                
            case STATE_SE_SPACE_OR_END: // obligatory space, or end
                if (mPendingInput != 0) {
                    if (!handlePartialCR()) {
                        return EVENT_INCOMPLETE;
                    }
                    // Ok, got a space, can move on
                } else {
                    b = _inputBuffer[_inputPtr++];
                    c = (int) b & 0xFF;

                    if (c <= INT_SPACE) {
                        if (c == INT_LF) {
                            markLF();
                        } else if (c == INT_CR) {
                            if (_inputPtr >= _inputEnd) {
                                mPendingInput = c;
                                return EVENT_INCOMPLETE;
                            }
                            if (_inputBuffer[_inputPtr] == BYTE_LF) {
                                ++_inputPtr;
                            }
                            markLF();
                        } else if (c != INT_SPACE && c != INT_TAB) {
                            throwInvalidSpace(c);
                        }
                    } else if (c == INT_GT) { // must be '/' or '>'
                        return finishStartElement(false);
                    } else if (c == INT_SLASH) {
                        mState = STATE_SE_SEEN_SLASH;
                    } else {
                        throwUnexpectedChar(decodeCharForError(b), " expected space, or '>' or \"/>\"");
                    }
                }
                mState = STATE_SE_SPACE_OR_ATTRNAME;
                if (_inputPtr >= _inputEnd) {
                    return EVENT_INCOMPLETE;
                }
                // can fall through, again:

            case STATE_SE_SPACE_OR_ATTRNAME:
            case STATE_SE_SPACE_OR_EQ:
            case STATE_SE_SPACE_OR_ATTRVALUE:
                /* Common to these states is that there may
                 * be leading space(s), so let's see if any
                 * has to be skipped
                 */

                if (mPendingInput != 0) {
                    if (!handlePartialCR()) {
                        return EVENT_INCOMPLETE;
                    }
                    if (_inputPtr >= _inputEnd) {
                        return EVENT_INCOMPLETE;
                    }
                }
                b = _inputBuffer[_inputPtr++];
                c = (int) b & 0xFF;

                while (c <= INT_SPACE) {
                    if (c == INT_LF) {
                        markLF();
                    } else if (c == INT_CR) {
                        if (_inputPtr >= _inputEnd) {
                            mPendingInput = c;
                            return EVENT_INCOMPLETE;
                        }
                        if (_inputBuffer[_inputPtr] == BYTE_LF) {
                            ++_inputPtr;
                        }
                        markLF();
                    } else if (c != INT_SPACE && c != INT_TAB) {
                        throwInvalidSpace(c);
                    }
                    if (_inputPtr >= _inputEnd) {
                        return EVENT_INCOMPLETE;
                    }
                    b = _inputBuffer[_inputPtr];
                    c = (int) b & 0xFF;
                }

                switch (mState) {
                case STATE_SE_SPACE_OR_ATTRNAME:
                    {
                        PName n = parseNewName(b);
                        if (n == null) {
                            mState = STATE_SE_ATTR_NAME;
                            return EVENT_INCOMPLETE;
                        }
                        mState = STATE_SE_SPACE_OR_EQ;
                        mElemAttrName = n;
                    }
                    continue main_loop;

                case STATE_SE_SPACE_OR_EQ:
                    if (b != BYTE_EQ) {
                        throwUnexpectedChar(decodeCharForError(b), " expected '='");
                    }
                    mState = STATE_SE_SPACE_OR_ATTRVALUE;
                    continue main_loop;

                case STATE_SE_SPACE_OR_ATTRVALUE:
                    if (b != BYTE_QUOT && b != BYTE_APOS) {
                        throwUnexpectedChar(decodeCharForError(b), " Expected a quote");
                    }
                    initAttribute(b);
                    continue main_loop;

                default:
                    throwInternal();
                }
                
            case STATE_SE_ATTR_NAME:
                {
                    PName n = parsePName();
                    if (n == null) {
                        return EVENT_INCOMPLETE;
                    }
                    mElemAttrName = n;
                    mState = STATE_SE_SPACE_OR_EQ;
                }
                break;

            case STATE_SE_ATTR_VALUE_NORMAL:
                if (!handleAttrValue()) {
                    return EVENT_INCOMPLETE;
                }
                mState = STATE_SE_SPACE_OR_END;
                break;

            case STATE_SE_ATTR_VALUE_NSDECL:
                if (!handleNsDecl()) {
                    return EVENT_INCOMPLETE;
                }
                mState = STATE_SE_SPACE_OR_END;
                break;

            case STATE_SE_SEEN_SLASH:
                {
                    b = _inputBuffer[_inputPtr++];
                    if (b != BYTE_GT) {
                        throwUnexpectedChar(decodeCharForError(b), " expected '>'");
                    }
                    return finishStartElement(true);
                }
            default:
                throwInternal();
            }
        }
    }

    private void initStartElement(PName elemName)
    {
        String prefix = elemName.getPrefix();
        if (prefix == null) { // element in default ns
            mElemAllNsBound = true; // which need not be bound
        } else {
            elemName = bindName(elemName, prefix);
            mElemAllNsBound = elemName.isBound();
        }
        _tokenName = elemName;
        _currElem = new ElementScope(elemName, _currElem);
        _attrCount = 0;
        mElemAttrPtr = 0;
        mState = STATE_SE_SPACE_OR_END;
    }

    private void initAttribute(byte quoteChar)
    {
        mElemAttrQuote = quoteChar;

        PName attrName = mElemAttrName;
        String prefix = attrName.getPrefix();
        boolean nsDecl;
 
        if (prefix == null) { // can be default ns decl:
            nsDecl = (attrName.getLocalName() == "xmlns");
        } else {
            // May be a namespace decl though?
            if (prefix == "xmlns") {
                nsDecl = true;
            } else {
                attrName = bindName(attrName, prefix);
                if (mElemAllNsBound) {
                    mElemAllNsBound = attrName.isBound();
                }
                nsDecl = false;
            }
        }

        if (nsDecl) {
            mState = STATE_SE_ATTR_VALUE_NSDECL;
            // Ns decls use name buffer transiently
            mElemAttrPtr = 0;
        } else {
            mState = STATE_SE_ATTR_VALUE_NORMAL;
            // Regular attributes are appended, shouldn't reset ptr
            _attrCollector.startNewValue(attrName, mElemAttrPtr);
        }
    }

    protected abstract boolean handleAttrValue()
        throws XMLStreamException;

    protected abstract boolean handleNsDecl()
        throws XMLStreamException;

    /**
     * Method called to wrap up settings when the whole start
     * (or empty) element has been parsed.
     */
    private int finishStartElement(boolean emptyTag)
        throws XMLStreamException
    {
        _isEmptyTag = emptyTag;

        // Note: this call also checks attribute uniqueness
        int act = _attrCollector.finishLastValue(mElemAttrPtr);
        if (act < 0) { // error, dup attr indicated by -1
            act = _attrCollector.getCount(); // let's get correct count
            reportInputProblem(_attrCollector.getErrorMsg());
        }
        _attrCount = act;
        ++_depth;

        /* Was there any prefix that wasn't bound prior to use?
         * That's legal, assuming declaration was found later on...
         * let's check
         */
        if (!mElemAllNsBound) {
            if (!_tokenName.isBound()) { // element itself unbound
                reportUnboundPrefix(_tokenName, false);
            }
            for (int i = 0, len = _attrCount; i < len; ++i) {
                PName attrName = _attrCollector.getName(i);
                if (!attrName.isBound()) {
                    reportUnboundPrefix(attrName, true);
                }
            }
        }

        return (_currToken = START_ELEMENT);
    }

    private int handleEndElementStart()
        throws XMLStreamException
    {
        --_depth;
        _tokenName = _currElem.getName();

        /* Ok, perhaps we can do this quickly? This works, if we
         * are expected to have the full name (plus one more byte
         * to indicate name end) in the current buffer:
         */
        int size = _tokenName.sizeInQuads();
        if ((_inputEnd - _inputPtr) < ((size << 2) + 1)) { // may need to load more
            mNextEvent = END_ELEMENT;
            mState = STATE_DEFAULT;
            mQuadCount = _currQuad = _currQuadBytes = 0;
            /* No, need to take it slow. Can not yet give up, though,
             * without reading remainder of the buffer
             */
            return handleEndElement();
        }
        byte[] buf = _inputBuffer;
        
        // First all full chunks of 4 bytes (if any)
        --size;
        for (int qix = 0; qix < size; ++qix) {
            int ptr = _inputPtr;
            int q = (buf[ptr] << 24)
                | ((buf[ptr+1] & 0xFF) << 16)
                | ((buf[ptr+2] & 0xFF) << 8)
                | ((buf[ptr+3] & 0xFF))
                ;
            _inputPtr += 4;
            // match?
            if (q != _tokenName.getQuad(qix)) {
                reportUnexpectedEndTag(_tokenName.getPrefixedName());
            }
        }
        
        /* After which we can deal with the last entry: it's bit
         * tricky as we don't actually fully know byte length...
         */
        int lastQ = _tokenName.getQuad(size);
        int q = buf[_inputPtr++] & 0xFF;
        if (q != lastQ) { // need second byte?
            q = (q << 8) | (buf[_inputPtr++] & 0xFF);
            if (q != lastQ) { // need third byte?
                q = (q << 8) | (buf[_inputPtr++] & 0xFF);
                if (q != lastQ) { // need full 4 bytes?
                    q = (q << 8) | (buf[_inputPtr++] & 0xFF);
                    if (q != lastQ) { // still no match? failure!
                        reportUnexpectedEndTag(_tokenName.getPrefixedName());
                    }
                }
            }
        }
        // Trailing space?
        int i2 = _inputBuffer[_inputPtr++] & 0xFF;
        while (i2 <= INT_SPACE) {
            if (i2 == INT_LF) {
                markLF();
            } else if (i2 == INT_CR) {
                if (_inputPtr >= _inputEnd) {
                    mPendingInput = i2;
                    mNextEvent = END_ELEMENT;
                    mState = STATE_EE_NEED_GT;
                    return EVENT_INCOMPLETE;
                }
                if (_inputBuffer[_inputPtr] == BYTE_LF) {
                    ++_inputPtr;
                }
                markLF();
            } else if (i2 != INT_SPACE && i2 != INT_TAB) {
                throwInvalidSpace(i2);
            }
            if (_inputPtr >= _inputEnd) {
                mNextEvent = END_ELEMENT;
                mState = STATE_EE_NEED_GT;
                return EVENT_INCOMPLETE;
            }
            i2 = _inputBuffer[_inputPtr++] & 0xFF;
        }
        if (i2 != INT_GT) {
            throwUnexpectedChar(decodeCharForError((byte)i2), " expected space or closing '>'");
        }
        return (_currToken = END_ELEMENT);
    }

    /**
     * This method is "slow" version of above, used when name of
     * the end element can split input buffer boundary
     */
    private int handleEndElement()
        throws XMLStreamException
    {
        if (mState == STATE_DEFAULT) { // parsing name
            final PName elemName = _tokenName;
            final int quadSize = elemName.sizeInQuads() - 1; // need to ignore last for now
            for (; mQuadCount < quadSize; ++mQuadCount) { // first, full quads
                for (; _currQuadBytes < 4; ++_currQuadBytes) {
                    if (_inputPtr >= _inputEnd) {
                        return EVENT_INCOMPLETE;
                    }
                    _currQuad = (_currQuad << 8) | (_inputBuffer[_inputPtr++] & 0xFF);
                }
                // match?
                if (_currQuad != elemName.getQuad(mQuadCount)) {
                    reportUnexpectedEndTag(elemName.getPrefixedName());
                }
                _currQuad = _currQuadBytes = 0;
            }
            // So far so good! Now need to check the last quad:
            int lastQ = elemName.getLastQuad();
        
            while (true) {
                if (_inputPtr >= _inputEnd) {
                    return EVENT_INCOMPLETE;
                }
                int q = (_currQuad << 8);
                q |= (_inputBuffer[_inputPtr++] & 0xFF);
                _currQuad = q;
                if (q == lastQ) { // match
                    break;
                }
                if (++_currQuadBytes > 3) { // no match, error
                    reportUnexpectedEndTag(elemName.getPrefixedName());
                    break; // never gets here
                }
            }
            // Bueno. How about optional space, '>'?
            mState = STATE_EE_NEED_GT;
        } else if (mState != STATE_EE_NEED_GT) {
            throwInternal();
        }

        if (mPendingInput != 0) {
            if (!handlePartialCR()) {
                return EVENT_INCOMPLETE;
            }
            // it's ignorable ws
        }
        
        // Trailing space?
        while (true) {
            if (_inputPtr >= _inputEnd) {
                return EVENT_INCOMPLETE;
            }
            int i2 = _inputBuffer[_inputPtr++] & 0xFF;
            if (i2 <= INT_SPACE) {
                if (i2 == INT_LF) {
                    markLF();
                } else if (i2 == INT_CR) {
                    if (_inputPtr >= _inputEnd) {
                        mPendingInput = i2;
                        return EVENT_INCOMPLETE;
                    }
                    if (_inputBuffer[_inputPtr] == BYTE_LF) {
                        ++_inputPtr;
                    }
                    markLF();
                } else if (i2 != INT_SPACE && i2 != INT_TAB) {
                    throwInvalidSpace(i2);
                }
                continue;
            }

            if (i2 != INT_GT) {
                throwUnexpectedChar(decodeCharForError((byte)i2), " expected space or closing '>'");
            }
            // Hah, done!
            return (_currToken = END_ELEMENT);
        }
    }

    /*
    ////////////////////////////////////////////////
    // Abstract methods from base class, parsing
    ////////////////////////////////////////////////
    */

    protected abstract void finishCharacters()
        throws XMLStreamException;

    protected void finishCData()
        throws XMLStreamException
    {
        // N/A
        throwInternal();
    }

    protected void finishComment()
        throws XMLStreamException
    {
        // N/A
        throwInternal();
    }

    protected void finishDTD(boolean copyContents)
        throws XMLStreamException
    {
        // N/A
        throwInternal();
    }

    protected void finishPI()
        throws XMLStreamException
    {
        // N/A
        throwInternal();
    }

    protected void finishSpace()
        throws XMLStreamException
    {
        // N/A
        throwInternal();
    }

    // // token-skip methods

    protected abstract boolean skipCharacters()
        throws XMLStreamException;

    protected void skipCData()
        throws XMLStreamException
    {
        // !!! TBI
    }

    protected void skipComment()
        throws XMLStreamException
    {
        // !!! TBI
    }

    protected void skipPI()
        throws XMLStreamException
    {
        // !!! TBI
    }

    protected void skipSpace()
        throws XMLStreamException
    {
        // !!! TBI
    }

    protected boolean loadMore()
        throws XMLStreamException
    {
        // should never get called
        throwInternal();
        return false; // never gets here
    }

    /*
    ////////////////////////////////////////////////
    // Common name/entity parsing
    ////////////////////////////////////////////////
     */

    protected PName parseNewName(byte b)
        throws XMLStreamException
    {
        int q = b & 0xFF;

        /* Let's do just quick sanity check first; a thorough check will be
         * done later on if necessary, now we'll just do the very cheap
         * check to catch extra spaces etc.
         */
        if (q < INT_A) { // lowest acceptable start char, except for ':' that would be allowed in non-ns mode
            throwUnexpectedChar(q, "; expected a name start character");
        }
        mQuadCount = 0;
        _currQuad = q;
        _currQuadBytes = 1;
        return parsePName();
    }

    protected PName parseNewEntityName(byte b)
        throws XMLStreamException
    {
        int q = b & 0xFF;
        if (q < INT_A) {
            throwUnexpectedChar(q, "; expected a name start character");
        }
        mQuadCount = 0;
        _currQuad = q;
        _currQuadBytes = 1;
        return parseEntityName();
    }

    /**
     * This method can (for now?) be shared between all Ascii-based
     * encodings, since it only does coarse validity checking -- real
     * checks are done in different method.
     *<p>
     * Some notes about assumption implementation makes:
     *<ul>
     * <li>Well-formed xml content can not end with a name: as such,
     *    end-of-input is an error and we can throw an exception
     *  </li>
     * </ul>
     */
    protected PName parsePName()
        throws XMLStreamException
    {
        int q = _currQuad;

        while (true) {
            int i;

            switch (_currQuadBytes) {
            case 0:
                if (_inputPtr >= _inputEnd) {
                    return null; // all pointers have been set
                }
                q = _inputBuffer[_inputPtr++] & 0xFF;
                /* Since name char validity is checked later on, we only
                 * need to be able to reliably see the end of the name...
                 * and those are simple enough so that we can just
                 * compare; lookup table won't speed things up (according
                 * to profiler)
                 */
                if (q < 65) { // 'A'
                    // Ok; "_" (45), "." (46) and "0"-"9"/":" (48 - 57/58) still name chars
                    if (q < 45 || q > 58 || q == 47) {
                        // End of name
                        return findPName(q, 0);
                    }
                }
                // fall through

            case 1:
                if (_inputPtr >= _inputEnd) { // need to store pointers
                    _currQuad = q;
                    _currQuadBytes = 1;
                    return null;
                }
                i = _inputBuffer[_inputPtr++] & 0xFF;
                if (i < 65) { // 'A'
                    if (i < 45 || i > 58 || i == 47) {
                        return findPName(q, 1);
                    }
                }
                q = (q << 8) | i;
                // fall through

            case 2:
                if (_inputPtr >= _inputEnd) { // need to store pointers
                    _currQuad = q;
                    _currQuadBytes = 2;
                    return null;
                }
                i = _inputBuffer[_inputPtr++] & 0xFF;
                if (i < 65) { // 'A'
                    if (i < 45 || i > 58 || i == 47) {
                        return findPName(q, 2);
                    }
                }
                q = (q << 8) | i;
                // fall through

            case 3:
                if (_inputPtr >= _inputEnd) { // need to store pointers
                    _currQuad = q;
                    _currQuadBytes = 3;
                    return null;
                }
                i = _inputBuffer[_inputPtr++] & 0xFF;
                if (i < 65) { // 'A'
                    if (i < 45 || i > 58 || i == 47) {
                        return findPName(q, 3);
                    }
                }
                q = (q << 8) | i;
            }

            /* If we get this far, need to add full quad into
             * result array and update state
             */
            if (mQuadCount == 0) { // first quad
                mQuadBuffer[0] = q;
                mQuadCount = 1;
            } else {
                if (mQuadCount >= mQuadBuffer.length) { // let's just double?
                    mQuadBuffer = DataUtil.growArrayBy(mQuadBuffer, mQuadBuffer.length);
                }
                mQuadBuffer[mQuadCount++] = q;
            }
            _currQuadBytes = 0;
        }
    }

    protected PName parseEntityName()
        throws XMLStreamException
    {
        int q = _currQuad;

        while (true) {
            int i;

            switch (_currQuadBytes) {
            case 0:
                if (_inputPtr >= _inputEnd) {
                    return null; // all pointers have been set
                }
                q = _inputBuffer[_inputPtr++] & 0xFF;
                /* Since name char validity is checked later on, we only
                 * need to be able to reliably see the end of the name...
                 * and those are simple enough so that we can just
                 * compare; lookup table won't speed things up (according
                 * to profiler)
                 */
                if (q < 65) { // 'A'
                    // Ok; "_" (45), "." (46) and "0"-"9"/":" (48 - 57/58) still name chars
                    if (q < 45 || q > 58 || q == 47) {
                        // apos, quot?
                        if (mQuadCount == 1) {
                            q = mQuadBuffer[0];
                            if (q == EntityNames.ENTITY_APOS_QUAD) {
                                return EntityNames.ENTITY_APOS;
                            }
                            if (q == EntityNames.ENTITY_QUOT_QUAD) {
                                return EntityNames.ENTITY_QUOT;
                            }
                        }
                        // Nope, generic:
                        return findPName(q, 0);
                    }
                }
                // fall through

            case 1:
                if (_inputPtr >= _inputEnd) { // need to store pointers
                    _currQuad = q;
                    _currQuadBytes = 1;
                    return null;
                }
                i = _inputBuffer[_inputPtr++] & 0xFF;
                if (i < 65) { // 'A'
                    if (i < 45 || i > 58 || i == 47) {
                        return findPName(q, 1);
                    }
                }
                q = (q << 8) | i;
                // fall through

            case 2:
                if (_inputPtr >= _inputEnd) { // need to store pointers
                    _currQuad = q;
                    _currQuadBytes = 2;
                    return null;
                }
                i = _inputBuffer[_inputPtr++] & 0xFF;
                if (i < 65) { // 'A'
                    if (i < 45 || i > 58 || i == 47) {
                        // lt or gt?
                        if (mQuadCount == 0) {
                            if (q == EntityNames.ENTITY_GT_QUAD) {
                                return EntityNames.ENTITY_GT;
                            }
                            if (q == EntityNames.ENTITY_LT_QUAD) {
                                return EntityNames.ENTITY_LT;
                            }
                        }
                        return findPName(q, 2);
                    }
                }
                q = (q << 8) | i;
                // fall through

            case 3:
                if (_inputPtr >= _inputEnd) { // need to store pointers
                    _currQuad = q;
                    _currQuadBytes = 3;
                    return null;
                }
                i = _inputBuffer[_inputPtr++] & 0xFF;
                if (i < 65) { // 'A'
                    if (i < 45 || i > 58 || i == 47) {
                        // amp?
                        if (mQuadCount == 0) {
                            if (q == EntityNames.ENTITY_AMP_QUAD) {
                                return EntityNames.ENTITY_AMP;
                            }
                        }
                        return findPName(q, 3);
                    }
                }
                q = (q << 8) | i;
            }

            /* If we get this far, need to add full quad into
             * result array and update state
             */
            if (mQuadCount == 0) { // first quad
                mQuadBuffer[0] = q;
                mQuadCount = 1;
            } else {
                if (mQuadCount >= mQuadBuffer.length) { // let's just double?
                    mQuadBuffer = DataUtil.growArrayBy(mQuadBuffer, mQuadBuffer.length);
                }
                mQuadBuffer[mQuadCount++] = q;
            }
            _currQuadBytes = 0;
        }
    }

    /**
     * Method called to process a sequence of bytes that is likely to
     * be a PName. At this point we encountered an end marker, and
     * may either hit a formerly seen well-formed PName; an as-of-yet
     * unseen well-formed PName; or a non-well-formed sequence (containing
     * one or more non-name chars without any valid end markers).
     *
     * @param lastQuad Word with last 0 to 3 bytes of the PName; not included
     *   in the quad array
     * @param lastByteCount Number of bytes contained in lastQuad; 0 to 3.
     * @param firstQuad First 1 to 4 bytes of the PName (4 if length
     *    at least 4 bytes; less only if not). 
     * @param qlen Number of quads in the array, except if less than 2
     *    (in which case only firstQuad and lastQuad are used)
     * @param quads Array that contains all the quads, except for the
     *    last one, for names with more than 8 bytes (i.e. more than
     *    2 quads)
     */
    private final PName findPName(int lastQuad, int lastByteCount)
        throws XMLStreamException
    {
        // First, need to push back the byte read but not used:
        --_inputPtr;
        int qlen = mQuadCount;
        // Also: if last quad is empty, will need take last from qbuf.
        if (lastByteCount == 0) {
            lastQuad = mQuadBuffer[--qlen];
            lastByteCount = 4;
        }
        // Separate handling for short names:
        if (qlen <= 1) { // short name?
            if (qlen == 0) { // 4-bytes or less; only has 'lastQuad' defined
                int hash = ByteBasedPNameTable.calcHash(lastQuad);
                PName name = mSymbols.findSymbol(hash, lastQuad, 0);
                if (name == null) {
                    // Let's simplify things a bit, and just use array based one then:
                    mQuadBuffer[0] = lastQuad;
                    name = addPName(hash, mQuadBuffer, 1, lastByteCount);
                }
                return name;
            }
            int firstQuad = mQuadBuffer[0];
            int hash = ByteBasedPNameTable.calcHash(firstQuad, lastQuad);
            PName name = mSymbols.findSymbol(hash, firstQuad, lastQuad);
            if (name == null) {
                // As above, let's just use array, then
                mQuadBuffer[1] = lastQuad;
                name = addPName(hash, mQuadBuffer, 2, lastByteCount);
            }
            return name;
        }
        /* Nope, long (3 quads or more). At this point, the last quad is
         * not yet in the array, let's add:
         */
        if (qlen >= mQuadBuffer.length) { // let's just double?
            mQuadBuffer = DataUtil.growArrayBy(mQuadBuffer, mQuadBuffer.length);
        }
        mQuadBuffer[qlen++] = lastQuad;
        int hash = ByteBasedPNameTable.calcHash(mQuadBuffer, qlen);
        PName name = mSymbols.findSymbol(hash, mQuadBuffer, qlen);
        if (name == null) {
            name = addPName(hash, mQuadBuffer, qlen, lastByteCount);
        }
        return name;
    }

    /*
    ///////////////////////////////////////////////////
    // methods from base class, name handling
    ///////////////////////////////////////////////////
    */

    protected abstract PName addPName(int hash, int[] quads, int qlen, int lastQuadBytes)
        throws XMLStreamException;

    /*
    /////////////////////////////////////////////////////
    // methods from base class, error reporting
    /////////////////////////////////////////////////////
    */

    protected int decodeCharForError(byte b)
        throws XMLStreamException
    {
        // !!! TBI
        return (int) b;
    }

    private void checkPITargetName(PName targetName)
        throws XMLStreamException
    {
        String ln = targetName.getLocalName();
        if (ln.length() == 3 && ln.equalsIgnoreCase("xml") &&
            !targetName.hasPrefix()) {
            reportInputProblem(ErrorConsts.ERR_WF_PI_XML_TARGET);
        }
    }

    /*
    ///////////////////////////////////////////////////
    // Internal methods, entity expansion
    ///////////////////////////////////////////////////
    */
    
    protected int handleCharacterEntity()
        throws XMLStreamException
    {
        // !!! TBI
        return 0;
    }

    /*
    ///////////////////////////////////////////////////
    // Internal methods, LF handling
    ///////////////////////////////////////////////////
    */

    /**
     * Method called when there is a pending \r (from past buffer),
     * and we need to see
     *
     * @return True if the linefeed was succesfully processed (had
     *   enough input data to do that); or false if there is no
     *   data available to check this
     */
    protected final boolean handlePartialCR()
    {
        // sanity check
        if (mPendingInput != INT_CR) {
            throwInternal();
        }
        if (_inputPtr >= _inputEnd) {
            return false;
        }
        mPendingInput = 0;
        if (_inputBuffer[_inputPtr] == BYTE_LF) {
            ++_inputPtr;
        }
        ++_currRow;
        mRowStartOffset = _inputPtr;
        return true;
    }

    /*
    ///////////////////////////////////////////////////
    // Internal methods, error handling
    ///////////////////////////////////////////////////
    */

    protected int throwInternal()
    {
        throw new IllegalStateException("Internal error: should never execute this code path");
    }

}
