/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2018 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantCP;
import org.apache.bcel.classfile.ConstantInvokeDynamic;
import org.apache.bcel.classfile.ConstantMethodHandle;
import org.apache.bcel.classfile.ConstantNameAndType;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.Unknown;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.CodeByteUtils;
import com.mebigfatguy.fbcontrib.utils.OpcodeUtils;
import com.mebigfatguy.fbcontrib.utils.StopOpcodeParsingException;
import com.mebigfatguy.fbcontrib.utils.ToString;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * looks for issues around use of @FunctionalInterface classes, especially in use with Streams..
 */
@CustomUserValue
public class FunctionalInterfaceIssues extends BytecodeScanningDetector {

    private static final int REF_invokeStatic = 6;

    private BugReporter bugReporter;
    private JavaClass cls;
    private OpcodeStack stack;
    private Attribute bootstrapAtt;
    private Map<String, List<FIInfo>> functionalInterfaceInfo;
    private boolean isLambda;
    private AnonState anonState;

    enum AnonState {
        SEEN_NOTHING, SEEN_ALOAD_0, SEEN_INVOKE
    };

    public FunctionalInterfaceIssues(BugReporter bugReporter) {
        this.bugReporter = bugReporter;
    }

    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            cls = classContext.getJavaClass();
            if (cls.getMajor() >= Constants.MAJOR_1_8) {
                bootstrapAtt = getBootstrapAttribute(cls);
                if (bootstrapAtt != null) {
                    stack = new OpcodeStack();
                    functionalInterfaceInfo = new HashMap<>();
                    super.visitClassContext(classContext);

                    for (Map.Entry<String, List<FIInfo>> entry : functionalInterfaceInfo.entrySet()) {
                        for (FIInfo fii : entry.getValue()) {
                            bugReporter.reportBug(new BugInstance(this, BugType.FII_USE_METHOD_HANDLE.name(), NORMAL_PRIORITY).addClass(this)
                                    .addMethod(cls, fii.getMethod()).addSourceLine(fii.getSrcLine()));
                        }
                    }
                }
            }
        } finally {
            functionalInterfaceInfo = null;
            bootstrapAtt = null;
            stack = null;
            cls = null;
        }
    }

    @Override
    public void visitCode(Code obj) {

        Method m = getMethod();
        if ((m.getAccessFlags() & ACC_SYNTHETIC) != 0) {
            // This assumes lambda methods follow regular methods
            List<FIInfo> fiis = functionalInterfaceInfo.get(m.getName());
            if (fiis != null) {
                try {
                    isLambda = true;
                    anonState = AnonState.SEEN_NOTHING;
                    super.visitCode(obj);
                } catch (StopOpcodeParsingException e) {
                }
            }
        } else if (prescreen(m)) {
            stack.resetForMethodEntry(this);
            isLambda = false;
            super.visitCode(obj);
        }
    }

    @Override
    public void sawOpcode(int seen) {

        try {
            if (isLambda) {
                switch (anonState) {
                    case SEEN_NOTHING:
                        if (seen == ALOAD_0) {
                            anonState = AnonState.SEEN_ALOAD_0;
                        } else {
                            functionalInterfaceInfo.remove(getMethod().getName());
                            throw new StopOpcodeParsingException();
                        }
                    break;

                    case SEEN_ALOAD_0:
                        if ((seen == INVOKEVIRTUAL) || (seen == INVOKEINTERFACE)) {
                            String signature = getSigConstantOperand();
                            if (signature.startsWith("()")) {
                                anonState = AnonState.SEEN_INVOKE;
                            } else {
                                functionalInterfaceInfo.remove(getMethod().getName());
                                throw new StopOpcodeParsingException();
                            }
                        } else {
                            functionalInterfaceInfo.remove(getMethod().getName());
                            throw new StopOpcodeParsingException();
                        }
                    break;

                    case SEEN_INVOKE:
                        if (!OpcodeUtils.isReturn(seen)) {
                            functionalInterfaceInfo.remove(getMethod().getName());
                        }
                        throw new StopOpcodeParsingException();

                    default:
                        functionalInterfaceInfo.remove(getMethod().getName());
                        throw new StopOpcodeParsingException();
                }
            } else {
                switch (seen) {
                    case Constants.INVOKEDYNAMIC:
                        ConstantInvokeDynamic cid = (ConstantInvokeDynamic) getConstantRefOperand();

                        ConstantMethodHandle cmh = getMethodHandle(cid.getBootstrapMethodAttrIndex());
                        String anonName = getAnonymousName(cmh);
                        if (anonName != null) {

                            List<FIInfo> fiis = functionalInterfaceInfo.get(anonName);
                            if (fiis == null) {
                                fiis = new ArrayList<>();
                                functionalInterfaceInfo.put(anonName, fiis);
                            }

                            FIInfo fii = new FIInfo(getMethod(), SourceLineAnnotation.fromVisitedInstruction(this));
                            fiis.add(fii);
                        }
                    break;

                }
            }
        } finally {
            stack.sawOpcode(this, seen);
        }
    }

    /**
     * looks for methods that contain a INVOKEDYNAMIC opcodes
     *
     * @param method
     *            the context object of the current method
     * @return if the class uses synchronization
     */
    private boolean prescreen(Method method) {
        BitSet bytecodeSet = getClassContext().getBytecodeSet(method);
        return (bytecodeSet != null) && (bytecodeSet.get(Constants.INVOKEDYNAMIC));
    }

    private Attribute getBootstrapAttribute(JavaClass clz) {
        for (Attribute att : clz.getAttributes()) {
            if ("BootstrapMethods".equals(att.getName())) {
                return att;
            }
        }

        return null;
    }

    private ConstantMethodHandle getMethodHandle(int bootstrapIndex) {
        byte[] attBytes = ((Unknown) bootstrapAtt).getBytes();

        int offset = 2; // num methods
        for (int i = 0; i < bootstrapIndex; i++) {
            offset += 2; // method ref
            int numArgs = CodeByteUtils.getshort(attBytes, offset);
            offset += 2 + (numArgs * 2);
        }
        offset += 2; // method ref

        int numArgs = CodeByteUtils.getshort(attBytes, offset);
        offset += 2; // args
        for (int i = 0; i < numArgs; i++) {
            int arg = CodeByteUtils.getshort(attBytes, offset);
            offset += 2; // arg
            Constant c = getConstantPool().getConstant(arg);
            if (c instanceof ConstantMethodHandle) {
                return (ConstantMethodHandle) c;
            }
        }

        return null;
    }

    private String getAnonymousName(ConstantMethodHandle cmh) {
        if (cmh.getReferenceKind() != REF_invokeStatic) {
            return null;
        }

        ConstantPool cp = getConstantPool();
        ConstantCP methodRef = (ConstantCP) cp.getConstant(cmh.getReferenceIndex());
        String clsName = methodRef.getClass(cp);
        if (!clsName.equals(cls.getClassName())) {
            return null;
        }

        ConstantNameAndType nameAndType = (ConstantNameAndType) cp.getConstant(methodRef.getNameAndTypeIndex());

        String signature = nameAndType.getSignature(cp);
        if (signature.endsWith("V")) {
            return null;
        }

        return nameAndType.getName(cp);
    }

    class FIInfo {
        private Method method;
        private SourceLineAnnotation srcLine;

        public FIInfo(Method method, SourceLineAnnotation srcLine) {
            this.method = method;
            this.srcLine = srcLine;
        }

        public Method getMethod() {
            return method;
        }

        public SourceLineAnnotation getSrcLine() {
            return srcLine;
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }

    }
}