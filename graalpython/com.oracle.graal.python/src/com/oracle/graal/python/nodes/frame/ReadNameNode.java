/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.nodes.frame;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.common.HashingStorage;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.nodes.PNodeWithContext;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.strings.TruffleString;

@GenerateUncached
public abstract class ReadNameNode extends PNodeWithContext implements AccessNameNode {
    public final Object execute(VirtualFrame frame, TruffleString attributeId) {
        CompilerAsserts.partialEvaluationConstant(attributeId);
        return executeImpl(frame, attributeId);
    }

    public abstract Object executeImpl(VirtualFrame frame, TruffleString attributeId);

    public static ReadNameNode create() {
        return ReadNameNodeGen.create();
    }

    private Object readGlobalsIfKeyError(VirtualFrame frame, TruffleString attributeId, ReadGlobalOrBuiltinNode readGlobalNode, PException e, IsBuiltinClassProfile keyError) {
        e.expect(PythonBuiltinClassType.KeyError, keyError);
        return readGlobalNode.execute(frame, attributeId);
    }

    protected static HashingStorage getStorage(VirtualFrame frame) {
        return ((PDict) PArguments.getSpecialArgument(frame)).getDictStorage();
    }

    @Specialization(guards = "!hasLocals(frame)")
    protected Object readFromLocals(VirtualFrame frame, TruffleString attributeId,
                    @Cached ReadGlobalOrBuiltinNode readGlobalNode) {
        return readGlobalNode.execute(frame, attributeId);
    }

    @Specialization(guards = "hasLocals(frame)")
    protected Object readFromLocalsDict(VirtualFrame frame, TruffleString attributeId,
                    @Cached ReadGlobalOrBuiltinNode readGlobalOrBuiltinNode,
                    @Cached ReadFromLocalsNode readFromLocals) {
        Object result = readFromLocals.execute(frame, PArguments.getSpecialArgument(frame), attributeId);
        if (result == PNone.NO_VALUE) {
            return readGlobalOrBuiltinNode.execute(frame, attributeId);
        } else {
            return result;
        }
    }
}
