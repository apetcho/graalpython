/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.modules.codecs;

import static com.oracle.graal.python.builtins.PythonBuiltinClassType.IndexError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.UnicodeDecodeError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.UnicodeEncodeError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.UnicodeTranslateError;
import static com.oracle.graal.python.builtins.modules.UnicodeDataModuleBuiltins.getUnicodeName;
import static com.oracle.graal.python.nodes.StringLiterals.T_BACKSLASHREPLACE;
import static com.oracle.graal.python.nodes.StringLiterals.T_EMPTY_STRING;
import static com.oracle.graal.python.nodes.StringLiterals.T_IGNORE;
import static com.oracle.graal.python.nodes.StringLiterals.T_QUESTIONMARK;
import static com.oracle.graal.python.nodes.StringLiterals.T_REPLACE;
import static com.oracle.graal.python.nodes.StringLiterals.T_STRICT;
import static com.oracle.graal.python.nodes.StringLiterals.T_SURROGATEPASS;
import static com.oracle.graal.python.nodes.StringLiterals.T_XMLCHARREFREPLACE;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;
import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;
import static com.oracle.graal.python.util.PythonUtils.tsLiteral;
import static com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere;

import java.nio.ByteOrder;

import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.modules.codecs.CodecsRegistry.PyCodecLookupErrorNode;
import com.oracle.graal.python.builtins.objects.buffer.PythonBufferAccessLibrary;
import com.oracle.graal.python.builtins.objects.buffer.PythonBufferAcquireLibrary;
import com.oracle.graal.python.builtins.objects.bytes.BytesUtils;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes;
import com.oracle.graal.python.builtins.objects.exception.PBaseException;
import com.oracle.graal.python.builtins.objects.exception.UnicodeDecodeErrorBuiltins.MakeDecodeExceptionNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeDecodeErrorBuiltins.PyUnicodeDecodeErrorGetEncodingNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeDecodeErrorBuiltins.PyUnicodeDecodeErrorGetEndNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeDecodeErrorBuiltins.PyUnicodeDecodeErrorGetObjectNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeDecodeErrorBuiltins.PyUnicodeDecodeErrorGetStartNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeEncodeErrorBuiltins.MakeEncodeExceptionNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeEncodeErrorBuiltins.PyUnicodeEncodeErrorGetEncodingNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeEncodeErrorBuiltins.PyUnicodeEncodeOrTranslateErrorGetEndNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeEncodeErrorBuiltins.PyUnicodeEncodeOrTranslateErrorGetObjectNode;
import com.oracle.graal.python.builtins.objects.exception.UnicodeEncodeErrorBuiltins.PyUnicodeEncodeOrTranslateErrorGetStartNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.CastToTruffleStringCheckedNode;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.lib.PyBytesCheckNode;
import com.oracle.graal.python.lib.PyObjectSizeNode;
import com.oracle.graal.python.lib.PyObjectTypeCheck;
import com.oracle.graal.python.lib.PyUnicodeCheckNode;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.call.CallNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.util.CastToJavaIntExactNode;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.truffle.api.CompilerDirectives.ValueType;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleString.CodeRange;
import com.oracle.truffle.api.strings.TruffleString.Encoding;
import com.oracle.truffle.api.strings.TruffleString.ErrorHandling;
import com.oracle.truffle.api.strings.TruffleStringBuilder;

/**
 * Implementation of default error handlers and internal helper nodes for calling error handlers.
 */
public final class ErrorHandlers {

    private ErrorHandlers() {
    }

    /**
     * Equivalent of _Py_error_handler. Note that the order is important, see
     * {@link #getNativeValue()}.
     */
    public enum ErrorHandler {
        UNKNOWN,
        STRICT,
        SURROGATEESCAPE,
        REPLACE,
        IGNORE,
        BACKSLASHREPLACE,
        SURROGATEPASS,
        XMLCHARREFREPLACE,
        OTHER;

        /**
         * @return the integer value as defined in the {@code fileutils.h} header
         */
        public int getNativeValue() {
            return ordinal();
        }

        static {
            assert UNKNOWN.getNativeValue() == 0;
            assert STRICT.getNativeValue() == 1;
            assert SURROGATEESCAPE.getNativeValue() == 2;
            assert REPLACE.getNativeValue() == 3;
            assert IGNORE.getNativeValue() == 4;
            assert BACKSLASHREPLACE.getNativeValue() == 5;
            assert SURROGATEPASS.getNativeValue() == 6;
            assert XMLCHARREFREPLACE.getNativeValue() == 7;
            assert OTHER.getNativeValue() == 8;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    public abstract static class GetErrorHandlerNode extends Node {

        public abstract ErrorHandler execute(Node inliningTarget, TruffleString errors);

        @Specialization
        static ErrorHandler doIt(Node inliningTarget, TruffleString errors,
                        @Cached(inline = false) TruffleString.EqualNode equalNode,
                        @Cached InlinedConditionProfile strictProfile,
                        @Cached InlinedConditionProfile surrogateEscapeProfile,
                        @Cached InlinedConditionProfile replaceProfile,
                        @Cached InlinedConditionProfile ignoreProfile,
                        @Cached InlinedConditionProfile backslashReplaceProfile,
                        @Cached InlinedConditionProfile surrogatePassProfile,
                        @Cached InlinedConditionProfile xmlCharRefReplaceProfile) {
            if (strictProfile.profile(inliningTarget, equalNode.execute(T_STRICT, errors, TS_ENCODING))) {
                return ErrorHandler.STRICT;
            }
            if (surrogateEscapeProfile.profile(inliningTarget, equalNode.execute(T_SURROGATEPASS, errors, TS_ENCODING))) {
                return ErrorHandler.SURROGATEESCAPE;
            }
            if (replaceProfile.profile(inliningTarget, equalNode.execute(T_REPLACE, errors, TS_ENCODING))) {
                return ErrorHandler.REPLACE;
            }
            if (ignoreProfile.profile(inliningTarget, equalNode.execute(T_IGNORE, errors, TS_ENCODING))) {
                return ErrorHandler.IGNORE;
            }
            if (backslashReplaceProfile.profile(inliningTarget, equalNode.execute(T_BACKSLASHREPLACE, errors, TS_ENCODING))) {
                return ErrorHandler.BACKSLASHREPLACE;
            }
            if (surrogatePassProfile.profile(inliningTarget, equalNode.execute(T_SURROGATEPASS, errors, TS_ENCODING))) {
                return ErrorHandler.SURROGATEPASS;
            }
            if (xmlCharRefReplaceProfile.profile(inliningTarget, equalNode.execute(T_XMLCHARREFREPLACE, errors, TS_ENCODING))) {
                return ErrorHandler.XMLCHARREFREPLACE;
            }
            return ErrorHandler.OTHER;
        }
    }

    public static int appendXmlCharRefReplacement(byte[] dest, int pos, int cp) {
        int digits = getDigitCount(cp);
        dest[pos++] = '&';
        dest[pos++] = '#';
        pos += digits;
        for (int i = 0; i < digits; ++i) {
            dest[pos - i - 1] = (byte) ('0' + cp % 10);
            cp /= 10;
        }
        dest[pos++] = ';';
        return pos;
    }

    public static int getXmlCharRefReplacementLength(int cp) {
        return 2 + getDigitCount(cp) + 1;
    }

    private static int getDigitCount(int cp) {
        assert cp >= 0 && cp <= Character.MAX_CODE_POINT;
        if (cp < 10) {
            return 1;
        } else if (cp < 100) {
            return 2;
        } else if (cp < 1000) {
            return 3;
        } else if (cp < 10000) {
            return 4;
        } else if (cp < 100000) {
            return 5;
        } else if (cp < 1000000) {
            return 6;
        } else {
            return 7;
        }
    }

    abstract static class ErrorHandlerBaseNode extends PythonUnaryBuiltinNode {
        static boolean isDecode(Object o, PyObjectTypeCheck pyObjectTypeCheck) {
            return pyObjectTypeCheck.execute(o, UnicodeDecodeError);
        }

        static boolean isEncode(Object o, PyObjectTypeCheck pyObjectTypeCheck) {
            return pyObjectTypeCheck.execute(o, UnicodeEncodeError);
        }

        static boolean isTranslate(Object o, PyObjectTypeCheck pyObjectTypeCheck) {
            return pyObjectTypeCheck.execute(o, UnicodeTranslateError);
        }

        static boolean isEncodeOrTranslate(Object o, PyObjectTypeCheck pyObjectTypeCheck) {
            return isEncode(o, pyObjectTypeCheck) || isTranslate(o, pyObjectTypeCheck);
        }

        static boolean isEncodeOrDecode(Object o, PyObjectTypeCheck pyObjectTypeCheck) {
            return isEncode(o, pyObjectTypeCheck) || isDecode(o, pyObjectTypeCheck);
        }

        static boolean isNeither(Object o, PyObjectTypeCheck pyObjectTypeCheck) {
            return !isDecode(o, pyObjectTypeCheck) && !isEncode(o, pyObjectTypeCheck) && !isTranslate(o, pyObjectTypeCheck);
        }

        PException wrongExceptionType(Object o) {
            throw raise(TypeError, ErrorMessages.DONT_KNOW_HOW_TO_HANDLE_P_IN_ERROR_CALLBACK, o);
        }
    }

    @Builtin(name = "strict_errors", minNumOfPositionalArgs = 1, parameterNames = "e")
    abstract static class StrictErrorHandlerNode extends ErrorHandlerBaseNode {
        @Specialization
        Object doException(PBaseException exception) {
            throw getRaiseNode().raiseExceptionObject(exception);
        }

        @Fallback
        Object doFallback(@SuppressWarnings("unused") Object o) {
            throw raise(TypeError, ErrorMessages.CODEC_MUST_PASS_EXCEPTION_INSTANCE);
        }
    }

    @Builtin(name = "ignore_errors", minNumOfPositionalArgs = 1, parameterNames = "e")
    @SuppressWarnings("truffle-static-method")
    abstract static class IgnoreErrorHandlerNode extends ErrorHandlerBaseNode {

        @Specialization(guards = "isDecode(exception, pyObjectTypeCheck)")
        Object doDecodeException(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeDecodeErrorGetEndNode getEndNode) {
            return factory().createTuple(new Object[]{T_EMPTY_STRING, getEndNode.execute(inliningTarget, exception)});
        }

        @Specialization(guards = "isEncodeOrTranslate(exception, pyObjectTypeCheck)")
        Object doEncodeOrTranslateException(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetEndNode getEndNode) {
            return factory().createTuple(new Object[]{T_EMPTY_STRING, getEndNode.execute(inliningTarget, exception)});
        }

        @Specialization(guards = "isNeither(o, pyObjectTypeCheck)")
        Object doFallback(Object o,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck) {
            throw wrongExceptionType(o);
        }
    }

    @Builtin(name = "replace_errors", minNumOfPositionalArgs = 1, parameterNames = "e")
    @SuppressWarnings("truffle-static-method")
    abstract static class ReplaceErrorHandlerNode extends ErrorHandlerBaseNode {

        private static final TruffleString T_REPLACEMENT = tsLiteral("\uFFFD");

        @Specialization(guards = "isDecode(exception, pyObjectTypeCheck)")
        Object doDecodeException(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeDecodeErrorGetEndNode getEndNode) {
            return factory().createTuple(new Object[]{T_REPLACEMENT, getEndNode.execute(inliningTarget, exception)});
        }

        @Specialization(guards = "isEncodeOrTranslate(exception, pyObjectTypeCheck)")
        Object doEncodeOrTranslateException(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetStartNode getStartNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetEndNode getEndNode,
                        @Cached TruffleString.RepeatNode repeatNode) {
            TruffleString replacement = isEncode(exception, pyObjectTypeCheck) ? T_QUESTIONMARK : T_REPLACEMENT;
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            int n = end - start;
            // CPython raises SystemError for negative values, we return an empty string
            TruffleString result = n < 1 ? T_EMPTY_STRING : repeatNode.execute(replacement, n, TS_ENCODING);
            return factory().createTuple(new Object[]{result, end});
        }

        @Specialization(guards = "isNeither(o, pyObjectTypeCheck)")
        Object doFallback(Object o,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck) {
            throw wrongExceptionType(o);
        }
    }

    @Builtin(name = "xmlcharrefreplace_errors", minNumOfPositionalArgs = 1, parameterNames = "e")
    @SuppressWarnings("truffle-static-method")
    abstract static class XmlCharRefReplaceErrorHandlerNode extends ErrorHandlerBaseNode {

        @Specialization(guards = "isEncode(exception, pyObjectTypeCheck)")
        Object doEncode(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetStartNode getStartNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetEndNode getEndNode,
                        @Cached TruffleString.CodePointAtIndexNode codePointAtIndexNode,
                        @Cached TruffleString.FromByteArrayNode fromByteArrayNode,
                        @Cached TruffleString.SwitchEncodingNode switchEncodingNode) {
            TruffleString src = getObjectNode.execute(inliningTarget, exception);
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            int replacementLength = 0;
            for (int i = start; i < end; ++i) {
                replacementLength += getXmlCharRefReplacementLength(codePointAtIndexNode.execute(src, i, TS_ENCODING, ErrorHandling.BEST_EFFORT));
            }
            byte[] replacement = new byte[replacementLength];
            int pos = 0;
            for (int i = start; i < end; ++i) {
                pos = appendXmlCharRefReplacement(replacement, pos, codePointAtIndexNode.execute(src, i, TS_ENCODING, ErrorHandling.BEST_EFFORT));
            }
            TruffleString resultAscii = fromByteArrayNode.execute(replacement, Encoding.US_ASCII, false);
            return factory().createTuple(new Object[]{switchEncodingNode.execute(resultAscii, TS_ENCODING), end});
        }

        @Specialization(guards = "!isEncode(o, pyObjectTypeCheck)")
        Object doFallback(Object o,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck) {
            throw wrongExceptionType(o);
        }
    }

    @Builtin(name = "backslashreplace_errors", minNumOfPositionalArgs = 1, parameterNames = "e")
    @SuppressWarnings("truffle-static-method")
    abstract static class BackslashReplaceErrorHandlerNode extends ErrorHandlerBaseNode {

        @Specialization(guards = "isDecode(exception, pyObjectTypeCheck)")
        Object doDecodeException(VirtualFrame frame, PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeDecodeErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeDecodeErrorGetStartNode getStartNode,
                        @Cached PyUnicodeDecodeErrorGetEndNode getEndNode,
                        @CachedLibrary(limit = "3") PythonBufferAcquireLibrary acquireLib,
                        @CachedLibrary(limit = "3") PythonBufferAccessLibrary accessLib,
                        @Cached @Shared TruffleString.FromByteArrayNode fromByteArrayNode,
                        @Cached @Shared TruffleString.SwitchEncodingNode switchEncodingNode) {
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            Object object = getObjectNode.execute(inliningTarget, exception);
            if (start >= end) {
                return factory().createTuple(new Object[]{T_EMPTY_STRING, end});
            }
            byte[] replacement = new byte[4 * (end - start)];
            int pos = 0;
            Object srcBuf = acquireLib.acquireReadonly(object, frame, this);
            try {
                byte[] src = accessLib.getInternalOrCopiedByteArray(srcBuf);
                for (int i = start; i < end; i++) {
                    pos = BytesUtils.byteEscape(src[i] & 0xFF, pos, replacement);
                }
            } finally {
                accessLib.release(srcBuf, frame, this);
            }
            TruffleString resultAscii = fromByteArrayNode.execute(replacement, Encoding.US_ASCII, false);
            return factory().createTuple(new Object[]{switchEncodingNode.execute(resultAscii, TS_ENCODING), end});
        }

        @Specialization(guards = "isEncodeOrTranslate(exception, pyObjectTypeCheck)")
        Object doEncodeOrTranslateException(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetStartNode getStartNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetEndNode getEndNode,
                        @Cached TruffleString.CodePointAtIndexNode codePointAtIndexNode,
                        @Cached @Shared TruffleString.FromByteArrayNode fromByteArrayNode,
                        @Cached @Shared TruffleString.SwitchEncodingNode switchEncodingNode) {
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            TruffleString src = getObjectNode.execute(inliningTarget, exception);
            if (start >= end) {
                return factory().createTuple(new Object[]{T_EMPTY_STRING, end});
            }
            int len = 0;
            for (int i = start; i < end; ++i) {
                int cp = codePointAtIndexNode.execute(src, i, TS_ENCODING, ErrorHandling.BEST_EFFORT);
                if (cp >= 0x10000) {
                    len += 1 + 1 + 8;       // \\UNNNNNNNN
                } else if (cp >= 0x100) {
                    len += 1 + 1 + 4;       // \\uNNNN
                } else {
                    len += 1 + 1 + 2;       // \\xNN
                }
            }
            byte[] replacement = new byte[len];
            int pos = 0;
            for (int i = start; i < end; i++) {
                int cp = codePointAtIndexNode.execute(src, i, TS_ENCODING, ErrorHandling.BEST_EFFORT);
                pos = BytesUtils.unicodeNonAsciiEscape(cp, pos, replacement, true);
            }
            TruffleString resultAscii = fromByteArrayNode.execute(replacement, Encoding.US_ASCII, false);
            return factory().createTuple(new Object[]{switchEncodingNode.execute(resultAscii, TS_ENCODING), end});
        }

        @Specialization(guards = "isNeither(o, pyObjectTypeCheck)")
        Object doFallback(Object o,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck) {
            throw wrongExceptionType(o);
        }
    }

    @Builtin(name = "namereplace_errors", minNumOfPositionalArgs = 1, parameterNames = "e")
    @SuppressWarnings("truffle-static-method")
    abstract static class NameReplaceErrorHandlerNode extends ErrorHandlerBaseNode {

        @Specialization(guards = "isEncode(exception, pyObjectTypeCheck)")
        Object doEncode(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetStartNode getStartNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetEndNode getEndNode,
                        @Cached TruffleString.CodePointAtIndexNode codePointAtIndexNode,
                        @Cached TruffleString.FromByteArrayNode fromByteArrayNode,
                        @Cached TruffleString.SwitchEncodingNode switchEncodingNode,
                        @Cached TruffleString.FromJavaStringNode fromJavaStringNode,
                        @Cached TruffleStringBuilder.AppendStringNode appendStringNode,
                        @Cached TruffleStringBuilder.AppendCodePointNode appendCodePointNode,
                        @Cached TruffleStringBuilder.ToStringNode toStringNode) {
            TruffleString src = getObjectNode.execute(inliningTarget, exception);
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            if (start >= end) {
                return factory().createTuple(new Object[]{T_EMPTY_STRING, start});
            }
            TruffleStringBuilder tsb = TruffleStringBuilder.create(TS_ENCODING);
            byte[] buf = new byte[1 + 1 + 8];  // \UNNNNNNNN
            for (int i = start; i < end; ++i) {
                int cp = codePointAtIndexNode.execute(src, i, TS_ENCODING, ErrorHandling.BEST_EFFORT);
                String name = getUnicodeName(cp);
                if (name != null) {
                    appendCodePointNode.execute(tsb, '\\');
                    appendCodePointNode.execute(tsb, 'N');
                    appendCodePointNode.execute(tsb, '{');
                    appendStringNode.execute(tsb, fromJavaStringNode.execute(name, TS_ENCODING));
                    appendCodePointNode.execute(tsb, '}');
                } else {
                    int len = BytesUtils.unicodeNonAsciiEscape(cp, 0, buf, true);
                    appendStringNode.execute(tsb, switchEncodingNode.execute(fromByteArrayNode.execute(buf, 0, len, Encoding.US_ASCII, true), TS_ENCODING));
                }
            }
            return factory().createTuple(new Object[]{toStringNode.execute(tsb), end});
        }

        @Specialization(guards = "!isEncode(o, pyObjectTypeCheck)")
        Object doFallback(Object o,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck) {
            throw wrongExceptionType(o);
        }
    }

    @Builtin(name = "surrogatepass", minNumOfPositionalArgs = 1, parameterNames = "e")
    @SuppressWarnings("truffle-static-method")
    abstract static class SurrogatePassErrorHandlerNode extends ErrorHandlerBaseNode {

        @Specialization(guards = "isEncode(exception, pyObjectTypeCheck)")
        Object doEncode(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetStartNode getStartNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetEndNode getEndNode,
                        @Cached PyUnicodeEncodeErrorGetEncodingNode getEncodingNode,
                        @Cached @Shared GetStandardEncodingNode getStandardEncodingNode,
                        @Cached TruffleString.CodePointAtIndexNode codePointAtIndexNode) {
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            TruffleString src = getObjectNode.execute(inliningTarget, exception);
            TruffleString encodingName = getEncodingNode.execute(inliningTarget, exception);
            StandardEncoding encoding = getStandardEncodingNode.execute(inliningTarget, encodingName);
            if (encoding == StandardEncoding.UNKNOWN) {
                throw getRaiseNode().raiseExceptionObject(exception);
            }
            if (start >= end) {
                return factory().createTuple(new Object[]{factory().createBytes(new byte[0]), end});
            }
            byte[] result = new byte[encoding.byteLength * (end - start)];
            int pos = 0;
            for (int i = start; i < end; ++i) {
                int cp = codePointAtIndexNode.execute(src, i, TS_ENCODING, ErrorHandling.BEST_EFFORT);
                if (!isSurrogate(cp)) {
                    throw getRaiseNode().raiseExceptionObject(exception);
                }
                encodeCodepoint(encoding, result, pos, cp);
                pos += encoding.byteLength;
            }
            return factory().createTuple(new Object[]{factory().createBytes(result), end});
        }

        @Specialization(guards = "isDecode(exception, pyObjectTypeCheck)")
        Object doDecode(VirtualFrame frame, PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeDecodeErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeDecodeErrorGetStartNode getStartNode,
                        @Cached PyUnicodeDecodeErrorGetEndNode getEndNode,
                        @Cached PyUnicodeDecodeErrorGetEncodingNode getEncodingNode,
                        @Cached @Shared GetStandardEncodingNode getStandardEncodingNode,
                        @CachedLibrary(limit = "3") PythonBufferAcquireLibrary acquireLib,
                        @CachedLibrary(limit = "3") PythonBufferAccessLibrary accessLib,
                        @Cached TruffleString.FromCodePointNode fromCodePointNode) {
            int start = getStartNode.execute(inliningTarget, exception);
            getEndNode.execute(inliningTarget, exception);  // called for side effects only
            Object object = getObjectNode.execute(inliningTarget, exception);
            TruffleString encodingName = getEncodingNode.execute(inliningTarget, exception);
            StandardEncoding encoding = getStandardEncodingNode.execute(inliningTarget, encodingName);
            if (encoding == StandardEncoding.UNKNOWN) {
                throw getRaiseNode().raiseExceptionObject(exception);
            }
            Object srcBuf = acquireLib.acquireReadonly(object, frame, this);
            try {
                int cp = 0;
                int srcLen = accessLib.getBufferLength(srcBuf);
                if (srcLen - start >= encoding.byteLength) {
                    cp = decodeCodepoint(encoding, accessLib.getInternalOrCopiedByteArray(srcBuf), start);
                }
                if (!isSurrogate(cp)) {
                    throw getRaiseNode().raiseExceptionObject(exception);
                }
                return factory().createTuple(new Object[]{fromCodePointNode.execute(cp, TS_ENCODING, true), start + encoding.byteLength});
            } finally {
                accessLib.release(srcBuf, frame, this);
            }
        }

        @Specialization(guards = "!isEncodeOrDecode(o, pyObjectTypeCheck)")
        Object doFallback(Object o,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck) {
            throw wrongExceptionType(o);
        }

        private static void encodeCodepoint(StandardEncoding encoding, byte[] result, int pos, int cp) {
            switch (encoding) {
                case UTF8:
                    result[pos] = (byte) (0xe0 | (cp >> 12));
                    result[pos + 1] = (byte) (0x80 | ((cp >> 6) & 0x3f));
                    result[pos + 2] = (byte) (0x80 | (cp & 0x3f));
                    break;
                case UTF16LE:
                    result[pos] = (byte) cp;
                    result[pos + 1] = (byte) (cp >> 8);
                    break;
                case UTF16BE:
                    result[pos] = (byte) (cp >> 8);
                    result[pos + 1] = (byte) cp;
                    break;
                case UTF32LE:
                    result[pos] = (byte) cp;
                    result[pos + 1] = (byte) (cp >> 8);
                    result[pos + 2] = (byte) (cp >> 16);
                    result[pos + 3] = (byte) (cp >> 24);
                    break;
                case UTF32BE:
                    result[pos] = (byte) (cp >> 24);
                    result[pos + 1] = (byte) (cp >> 16);
                    result[pos + 2] = (byte) (cp >> 8);
                    result[pos + 3] = (byte) cp;
                    break;
                default:
                    throw shouldNotReachHere("Unexpected encoding");
            }
        }

        private static int decodeCodepoint(StandardEncoding encoding, byte[] src, int pos) {
            return switch (encoding) {
                case UTF8 -> ((src[pos] & 0xf0) == 0xe0 && (src[pos + 1] & 0xc0) == 0x80 && (src[pos + 2] & 0xc0) == 0x80)
                                ? ((src[pos] & 0x0f) << 12) + ((src[pos + 1] & 0x3f) << 6) + (src[pos + 2] & 0x3f)
                                : 0;
                case UTF16LE -> (src[pos + 1] & 0xFF) << 8 | (src[pos] & 0xFF);
                case UTF16BE -> (src[pos] & 0xFF) << 8 | (src[pos + 1] & 0xFF);
                case UTF32LE -> ((src[pos + 3] & 0xFF) << 24) | ((src[pos + 2] & 0xFF) << 16) | ((src[pos + 1] & 0xFF) << 8) | (src[pos] & 0xFF);
                case UTF32BE -> ((src[pos] & 0xFF) << 24) | ((src[pos + 1] & 0xFF) << 16) | ((src[pos + 2] & 0xFF) << 8) | (src[pos + 3] & 0xFF);
                default -> throw shouldNotReachHere("Unexpected encoding");
            };
        }

        private static boolean isSurrogate(int cp) {
            return cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE;
        }
    }

    @Builtin(name = "surrogateescape", minNumOfPositionalArgs = 1, parameterNames = "e")
    @SuppressWarnings("truffle-static-method")
    abstract static class SurrogateEscapeErrorHandlerNode extends ErrorHandlerBaseNode {

        @Specialization(guards = "isEncode(exception, pyObjectTypeCheck)")
        Object doEncode(PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetStartNode getStartNode,
                        @Cached PyUnicodeEncodeOrTranslateErrorGetEndNode getEndNode,
                        @Cached TruffleString.CodePointAtIndexNode codePointAtIndexNode) {
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            TruffleString src = getObjectNode.execute(inliningTarget, exception);
            if (start >= end) {
                return factory().createTuple(new Object[]{factory().createBytes(new byte[0]), end});
            }
            byte[] result = new byte[end - start];
            int pos = 0;
            for (int i = start; i < end; ++i) {
                int cp = codePointAtIndexNode.execute(src, i, TS_ENCODING, ErrorHandling.BEST_EFFORT);
                if (cp < 0xdc80 || cp > 0xdcff) {
                    throw getRaiseNode().raiseExceptionObject(exception);
                }
                result[pos++] = (byte) (cp - 0xdc00);
            }
            return factory().createTuple(new Object[]{factory().createBytes(result), end});
        }

        @Specialization(guards = "isDecode(exception, pyObjectTypeCheck)")
        Object doDecode(VirtualFrame frame, PBaseException exception,
                        @Bind("this") Node inliningTarget,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck,
                        @Cached PyUnicodeDecodeErrorGetObjectNode getObjectNode,
                        @Cached PyUnicodeDecodeErrorGetStartNode getStartNode,
                        @Cached PyUnicodeDecodeErrorGetEndNode getEndNode,
                        @CachedLibrary(limit = "3") PythonBufferAcquireLibrary acquireLib,
                        @CachedLibrary(limit = "3") PythonBufferAccessLibrary accessLib,
                        @Cached TruffleStringBuilder.AppendCodePointNode appendCodePointNode,
                        @Cached TruffleStringBuilder.ToStringNode toStringNode) {
            int start = getStartNode.execute(inliningTarget, exception);
            int end = getEndNode.execute(inliningTarget, exception);
            Object object = getObjectNode.execute(inliningTarget, exception);
            Object srcBuf = acquireLib.acquireReadonly(object, frame, this);
            TruffleStringBuilder tsb = TruffleStringBuilder.create(TS_ENCODING);
            try {
                byte[] src = accessLib.getInternalOrCopiedByteArray(srcBuf);
                int consumed = 0;
                while (consumed < 4 && consumed < end - start) {
                    int v = src[start + consumed] & 0xFF;
                    if (v < 128) {
                        break;
                    }
                    appendCodePointNode.execute(tsb, 0xdc00 + v, 1, true);
                    consumed++;
                }
                if (consumed == 0) {
                    throw getRaiseNode().raiseExceptionObject(exception);
                }
                return factory().createTuple(new Object[]{toStringNode.execute(tsb), start + consumed});
            } finally {
                accessLib.release(srcBuf, frame, this);
            }
        }

        @Specialization(guards = "!isEncodeOrDecode(o, pyObjectTypeCheck)")
        Object doFallback(Object o,
                        @SuppressWarnings("unused") @Cached @Shared PyObjectTypeCheck pyObjectTypeCheck) {
            throw wrongExceptionType(o);
        }
    }

    enum StandardEncoding {
        UNKNOWN(-1),
        UTF8(3),
        UTF16LE(2),
        UTF16BE(2),
        UTF32LE(4),
        UTF32BE(4);

        public final int byteLength;

        StandardEncoding(int byteLength) {
            this.byteLength = byteLength;
        }
    }

    // Equivalent of get_standard_encoding
    @GenerateInline
    @GenerateCached(false)
    abstract static class GetStandardEncodingNode extends Node {
        private static final TruffleString T_CP_UTF8 = tsLiteral("CP_UTF8");

        abstract StandardEncoding execute(Node inliningTarget, TruffleString encoding);

        @Specialization
        StandardEncoding doIt(TruffleString encodingName,
                        @Cached(inline = false) TruffleString.GetCodeRangeNode getCodeRangeNode,
                        @Cached(inline = false) TruffleString.SwitchEncodingNode switchEncodingNode,
                        @Cached(inline = false) TruffleString.CopyToByteArrayNode copyToByteArrayNode,
                        @Cached(inline = false) TruffleString.EqualNode equalNode) {
            if (getCodeRangeNode.execute(encodingName, TS_ENCODING) != CodeRange.ASCII) {
                return StandardEncoding.UNKNOWN;
            }
            TruffleString asciiEncodingName = switchEncodingNode.execute(encodingName, Encoding.US_ASCII);
            int byteLength = asciiEncodingName.byteLength(Encoding.US_ASCII);
            if (byteLength > 9) {
                // longest name is utf-32-be, no need to allocate & copy when the string longer
                return StandardEncoding.UNKNOWN;
            }
            // append a terminating zero which allows us to omit length checks
            byte[] encoding = new byte[byteLength + 1];
            copyToByteArrayNode.execute(asciiEncodingName, 0, encoding, 0, byteLength, Encoding.US_ASCII);

            if (isAny(encoding[0], 'u', 'U') &&
                            isAny(encoding[1], 't', 'T') &&
                            isAny(encoding[2], 'f', 'F')) {
                int pos = 3;
                if (isAny(encoding[pos], '-', '_')) {
                    ++pos;
                }
                if (encoding[pos] == '8' && encoding[pos + 1] == 0) {
                    return StandardEncoding.UTF8;
                }
                if (encoding[pos] == '1' && encoding[pos + 1] == '6') {
                    return handleUtf16Or32(encoding, pos + 2, StandardEncoding.UTF16LE, StandardEncoding.UTF16BE);
                }
                if (encoding[pos] == '3' && encoding[pos + 1] == '2') {
                    return handleUtf16Or32(encoding, pos + 2, StandardEncoding.UTF32LE, StandardEncoding.UTF32BE);
                }
            } else if (equalNode.execute(encodingName, T_CP_UTF8, TS_ENCODING)) {
                return StandardEncoding.UTF8;
            }
            return StandardEncoding.UNKNOWN;
        }

        private StandardEncoding handleUtf16Or32(byte[] encoding, int pos, StandardEncoding le, StandardEncoding be) {
            if (encoding[pos] == 0) {
                return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? le : be;
            }
            if (isAny(encoding[pos], '-', '_')) {
                ++pos;
            }
            if (encoding[pos] != 0 && isAny(encoding[pos + 1], 'e', 'E') && encoding[pos + 2] == 0) {
                if (isAny(encoding[pos], 'b', 'B')) {
                    return be;
                }
                if (isAny(encoding[pos], 'l', 'L')) {
                    return le;
                }
            }
            return StandardEncoding.UNKNOWN;
        }

        private static boolean isAny(int cp, char option1, char option2) {
            return cp == option1 || cp == option2;
        }
    }

    static final class ErrorHandlerCache {
        ErrorHandler errorHandlerEnum = ErrorHandler.UNKNOWN;
        Object errorHandlerObject;
        PBaseException exceptionObject;
    }

    @ValueType
    static final class DecodingErrorHandlerResult {
        TruffleString str;
        int newPos;
        Object newSrcObj;

        DecodingErrorHandlerResult(TruffleString str, int newPos) {
            this.str = str;
            this.newPos = newPos;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    abstract static class ParseDecodingErrorHandlerResultNode extends Node {
        abstract DecodingErrorHandlerResult execute(VirtualFrame frame, Node node, Object result);

        @Specialization
        static DecodingErrorHandlerResult doTuple(Node node, PTuple result,
                        @Bind("this") Node inliningTarget,
                        @Cached SequenceNodes.LenNode lenNode,
                        @Cached SequenceNodes.GetObjectArrayNode getObjectArrayNode,
                        @Cached(inline = false) CastToTruffleStringCheckedNode castToTruffleStringCheckedNode,
                        @Cached(inline = false) CastToJavaIntExactNode castToJavaIntExactNode,
                        @Cached @Shared("raiseNode") PRaiseNode.Lazy raiseNode) {
            if (lenNode.execute(inliningTarget, result) != 2) {
                throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.TypeError, ErrorMessages.DECODING_ERROR_HANDLER_MUST_RETURN_STR_INT_TUPLE);
            }
            Object[] array = getObjectArrayNode.execute(inliningTarget, result);
            TruffleString str = castToTruffleStringCheckedNode.cast(array[0], ErrorMessages.DECODING_ERROR_HANDLER_MUST_RETURN_STR_INT_TUPLE);
            int pos = castToJavaIntExactNode.execute(array[1]);
            return new DecodingErrorHandlerResult(str, pos);
        }

        @Fallback
        static DecodingErrorHandlerResult doOther(Node inliningTarget, @SuppressWarnings("unused") Object result,
                        @Cached @Shared("raiseNode") PRaiseNode.Lazy raiseNode) {
            throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.TypeError, ErrorMessages.DECODING_ERROR_HANDLER_MUST_RETURN_STR_INT_TUPLE);
        }
    }

    // Contains logic from unicode_decode_call_errorhandler_writer
    @GenerateInline
    @GenerateCached(false)
    abstract static class CallDecodingErrorHandlerNode extends Node {

        abstract DecodingErrorHandlerResult execute(VirtualFrame frame, Node inliningTarget, ErrorHandlerCache cache, TruffleString errors, TruffleString encoding, Object srcObj, int startPos,
                        int endPos, TruffleString reason);

        @Specialization
        static DecodingErrorHandlerResult doIt(VirtualFrame frame, Node inliningTarget, ErrorHandlerCache cache, TruffleString errors, TruffleString encoding, Object srcObj, int startPos, int endPos,
                        TruffleString reason,
                        @Cached PyCodecLookupErrorNode lookupErrorNode,
                        @Cached MakeDecodeExceptionNode makeDecodeExceptionNode,
                        @Cached(inline = false) CallNode callNode,
                        @Cached ParseDecodingErrorHandlerResultNode parseResultNode,
                        @Cached PyUnicodeDecodeErrorGetObjectNode getObjectNode,
                        @Cached(inline = false) PyObjectSizeNode sizeNode,
                        @Cached PRaiseNode.Lazy raiseNode) {
            cache.errorHandlerObject = cache.errorHandlerObject == null ? lookupErrorNode.execute(inliningTarget, errors) : cache.errorHandlerObject;
            cache.exceptionObject = makeDecodeExceptionNode.execute(inliningTarget, cache.exceptionObject, encoding, srcObj, startPos, endPos, reason);
            Object resultObj = callNode.execute(frame, cache.errorHandlerObject, cache.exceptionObject);
            DecodingErrorHandlerResult result = parseResultNode.execute(frame, inliningTarget, resultObj);
            result.newSrcObj = getObjectNode.execute(inliningTarget, cache.exceptionObject);
            int newSize = sizeNode.execute(frame, result.newSrcObj);
            result.newPos = adjustAndCheckPos(result.newPos, newSize, inliningTarget, raiseNode);
            return result;
        }
    }

    @ValueType
    static final class EncodingErrorHandlerResult {
        Object replacement;
        int newPos;
        boolean isUnicode;  // whether `replacement` satisfies PyUnicode_Check or PyBytes_Check

        EncodingErrorHandlerResult(Object replacement, int newPos, boolean isUnicode) {
            this.replacement = replacement;
            this.newPos = newPos;
            this.isUnicode = isUnicode;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    abstract static class ParseEncodingErrorHandlerResultNode extends Node {
        abstract EncodingErrorHandlerResult execute(VirtualFrame frame, Node node, Object result);

        @Specialization
        static EncodingErrorHandlerResult doTuple(Node inliningTarget, PTuple result,
                        @Cached SequenceNodes.LenNode lenNode,
                        @Cached SequenceNodes.GetObjectArrayNode getObjectArrayNode,
                        @Cached(inline = false) CastToJavaIntExactNode castToJavaIntExactNode,
                        @Cached(inline = false) PyUnicodeCheckNode pyUnicodeCheckNode,
                        @Cached PyBytesCheckNode pyBytesCheckNode,
                        @Cached @Shared PRaiseNode.Lazy raiseNode) {
            if (lenNode.execute(inliningTarget, result) != 2) {
                throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.TypeError, ErrorMessages.ENCODING_ERROR_HANDLER_MUST_RETURN_STR_BYTES_INT_TUPLE);
            }
            Object[] array = getObjectArrayNode.execute(inliningTarget, result);
            boolean isUnicode;
            if (pyUnicodeCheckNode.execute(array[0])) {
                isUnicode = true;
            } else if (pyBytesCheckNode.execute(inliningTarget, array[0])) {
                isUnicode = false;
            } else {
                throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.TypeError, ErrorMessages.ENCODING_ERROR_HANDLER_MUST_RETURN_STR_BYTES_INT_TUPLE);
            }
            int pos = castToJavaIntExactNode.execute(array[1]);
            return new EncodingErrorHandlerResult(array[0], pos, isUnicode);
        }

        @Fallback
        static EncodingErrorHandlerResult doOther(Node inliningTarget, @SuppressWarnings("unused") Object result,
                        @Cached @Shared PRaiseNode.Lazy raiseNode) {
            throw raiseNode.get(inliningTarget).raise(PythonBuiltinClassType.TypeError, ErrorMessages.ENCODING_ERROR_HANDLER_MUST_RETURN_STR_BYTES_INT_TUPLE);
        }
    }

    // Contains logic from unicode_encode_call_errorhandler
    @GenerateInline
    @GenerateCached(false)
    abstract static class CallEncodingErrorHandlerNode extends Node {

        abstract EncodingErrorHandlerResult execute(VirtualFrame frame, Node inliningTarget, ErrorHandlerCache cache, TruffleString errors, TruffleString encoding, TruffleString srcObj, int startPos,
                        int endPos, TruffleString reason);

        @Specialization
        static EncodingErrorHandlerResult doIt(VirtualFrame frame, Node inliningTarget, ErrorHandlerCache cache, TruffleString errors, TruffleString encoding, TruffleString srcObj, int startPos,
                        int endPos, TruffleString reason,
                        @Cached PyCodecLookupErrorNode lookupErrorNode,
                        @Cached MakeEncodeExceptionNode makeEncodeExceptionNode,
                        @Cached(inline = false) CallNode callNode,
                        @Cached ParseEncodingErrorHandlerResultNode parseResultNode,
                        @Cached(inline = false) TruffleString.CodePointLengthNode codePointLengthNode,
                        @Cached PRaiseNode.Lazy raiseNode) {
            cache.errorHandlerObject = cache.errorHandlerObject == null ? lookupErrorNode.execute(inliningTarget, errors) : cache.errorHandlerObject;
            int len = codePointLengthNode.execute(srcObj, TS_ENCODING);
            cache.exceptionObject = makeEncodeExceptionNode.execute(inliningTarget, cache.exceptionObject, encoding, srcObj, startPos, endPos, reason);
            Object resultObj = callNode.execute(frame, cache.errorHandlerObject, cache.exceptionObject);
            EncodingErrorHandlerResult result = parseResultNode.execute(frame, inliningTarget, resultObj);
            result.newPos = adjustAndCheckPos(result.newPos, len, inliningTarget, raiseNode);
            return result;
        }
    }

    // Equivalent of raise_encode_exception
    @GenerateInline
    @GenerateCached(false)
    abstract static class RaiseEncodeException extends Node {

        abstract void execute(Node inliningTarget, ErrorHandlerCache cache, TruffleString encoding, TruffleString srcObj, int startPos, int endPos, TruffleString reason);

        @Specialization
        static void doIt(Node inliningTarget, ErrorHandlerCache cache, TruffleString encoding, TruffleString srcObj, int startPos, int endPos, TruffleString reason,
                        @Cached MakeEncodeExceptionNode makeEncodeExceptionNode,
                        @Cached(inline = false) PRaiseNode raiseNode) {
            cache.exceptionObject = makeEncodeExceptionNode.execute(inliningTarget, cache.exceptionObject, encoding, srcObj, startPos, endPos, reason);
            raiseNode.raiseExceptionObject(cache.exceptionObject);
        }
    }

    private static int adjustAndCheckPos(int newPos, int len, Node inliningTarget, PRaiseNode.Lazy raiseNode) {
        if (newPos < 0) {
            newPos += len;
        }
        if (newPos < 0 || newPos > len) {
            throw raiseNode.get(inliningTarget).raise(IndexError, ErrorMessages.POSITION_D_FROM_ERROR_HANDLER_OUT_OF_BOUNDS, newPos);
        }
        return newPos;
    }
}
