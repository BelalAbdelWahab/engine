/*
 * Developed by Koji Hisano <koji.hisano@eflow.jp>
 *
 * Copyright (C) 2009 eflow Inc. <http://www.eflow.jp/en/>
 *
 * This file is a part of Android Dalvik VM on Java.
 * http://code.google.com/p/android-dalvik-vm-on-java/
 *
 * This project is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This project is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package droidefense.om.machine.reader;

import apkr.external.modules.helpers.log4j.Log;
import apkr.external.modules.helpers.log4j.LoggerType;
import droidefense.om.machine.base.AbstractDVMThread;
import droidefense.om.machine.base.DalvikVM;
import droidefense.om.machine.base.DynamicUtils;
import droidefense.om.machine.base.constants.AccessFlag;
import droidefense.om.machine.base.constants.ValueFormat;
import droidefense.om.machine.base.exceptions.NotSupportedValueTypeException;
import droidefense.om.machine.base.struct.fake.DVMTaintClass;
import droidefense.om.machine.base.struct.fake.DVMTaintField;
import droidefense.om.machine.base.struct.fake.EncapsulatedClass;
import droidefense.om.machine.base.struct.generic.IAtomClass;
import droidefense.om.machine.base.struct.generic.IAtomField;
import droidefense.om.machine.base.struct.generic.IAtomFrame;
import droidefense.om.machine.base.struct.generic.IAtomMethod;
import droidefense.om.machine.base.struct.model.DVMClass;
import droidefense.om.machine.base.struct.model.DVMField;
import droidefense.om.machine.base.struct.model.DVMMethod;
import droidefense.sdk.helpers.DroidDefenseParams;
import droidefense.sdk.model.base.DroidefenseProject;
import droidefense.sdk.model.dex.DexBodyModel;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Hashtable;

public final class DexClassReader implements Serializable {

    private static final int SINGLE_VALUE = 1;
    private static final int SIGNED_BYTE_LENGTH = 8;
    //Singleton class
    private static DexClassReader instance;

    private final DalvikVM vm;
    private final Hashtable<String, IAtomClass> classes = new Hashtable<>();
    private final Object loadClassesMutex = new Object();
    private final DexBodyModel dexBodyModel;
    private DroidefenseProject currentProject;
    private byte[] dexFileContent;
    private int[] oldOffset = new int[5];
    private int oldOffsetIndex = 0;

    private DexClassReader[] readerList;

    private DexClassReader(DalvikVM dalvikVM, DroidefenseProject currentProject) {
        super();
        this.vm = dalvikVM;
        this.dexBodyModel = new DexBodyModel();
        //link this loader to a currentProject
        this.currentProject = currentProject;
    }

    public static DexClassReader init(DalvikVM dalvikVM, DroidefenseProject currentProject) {
        if (instance == null)
            instance = new DexClassReader(dalvikVM, currentProject);
        return instance;
    }

    public static DexClassReader getInstance() {
        return instance;
    }

    private static boolean hasNoValue(final int value) {
        return value == -1;
    }

    public IAtomClass load(String name) {
        IAtomClass cls;
        name = name.replace(".", "/");
        if (classes.containsKey(name)) {
            //class exist on dex file
            cls = classes.get(name);
        } else {
            cls = findClass(name);
            classes.put(name, cls);
        }
        if (!cls.isBinded() && !cls.isFake()) {
            cls.setBinded(true);
            IAtomMethod clinit = cls.getDirectMethod("<init>", "()V", true);
            if (clinit != null && !clinit.isFake()) {
                //TOdo changed this. may explode
                AbstractDVMThread firstThread = getVm().getThread(0);
                if (firstThread != null) {
                    IAtomFrame frame = firstThread.pushFrame();
                    frame.init(clinit);
                }
                /*try {
                    loadThread.run();
                } catch (ChangeThreadException e) {
                    // TODO Implement here by checking the behavior of the class loading in The Java Virtual Machine Specification
                } catch (Throwable e) {
                    com.error(e);
                }*/
            }
        }
        return cls;
    }

    private IAtomClass findClass(final String name) {
        //class does not exists on .dex file.
        //check if class belongs to java sdk or to android sdk.
        //anyway, if does not exist, send a fake class

        IAtomClass javaClass = null;
        String cname = name.replace(".", "/");

        //1 try to load cls from java jdk via reflection
        try {
            Class<?> s = Class.forName(name.replace("/", "."));

            //TODO Object[] lastCallArgs = loadThread.getLastMethodArgs();
            Object[] lastCallArgs = null;
            Class<?>[] classes;
            if (name.equals("java/lang/Object")) {
                //Special case. this class ahs no super
                Object newInstance = s.newInstance();
                EncapsulatedClass newClass = buildFakeClss(name, newInstance);
                newClass.setClass(s);
                newClass.setJavaObject(newInstance);
                newClass.setSuperClass(null);
                currentProject.addDexClass(name, newClass);
                return newClass;
            } else if (name.startsWith("java/lang/")) {
                Object newInstance = s.newInstance();
                EncapsulatedClass newClass = buildFakeClss(name, newInstance);
                newClass.setClass(s);
                newClass.setJavaObject(newInstance);
                currentProject.addDexClass(name, newClass);
                return newClass;
            } else if (lastCallArgs == null) {
                Constructor<?> constructor = s.getConstructor();
                if (constructor != null) {
                    Object newInstance = constructor.newInstance();
                    EncapsulatedClass newClass = buildFakeClss(name, newInstance);
                    newClass.setClass(s);
                    newClass.setJavaObject(newInstance);
                    currentProject.addDexClass(name, newClass);
                    return newClass;
                }
            } else {
                classes = new Class[lastCallArgs.length];
                int i = 0;
                for (Object obj : lastCallArgs) {
                    classes[i] = obj.getClass();
                    i++;
                }
                Constructor<?> constructor = s.getConstructor(classes);
                if (constructor != null) {
                    Object newInstance = constructor.newInstance(lastCallArgs);
                    IAtomClass newClass = buildFakeClss(name, newInstance);
                    currentProject.addDexClass(name, newClass);
                    return newClass;
                }
            }
        } catch (ClassNotFoundException e) {
            Log.write(LoggerType.ERROR, "Could not find class on java SDK " + name, e);
        } catch (Exception e) {
            Log.write(LoggerType.ERROR, "Error when loading class from java SDK " + name, e);
        }

        //Last option, emulate cls behaviour, emulate it!
        if (cname.contains("$")) {
            String[] data = cname.split("\\$");
            cname = data[0];
            javaClass = new DVMTaintClass(name);
            for (int i = 1; i < data.length; i++) {
                ((DVMTaintClass) javaClass).addDVMTaintField(new DVMTaintField(data[i], javaClass));
            }
            currentProject.addDexClass(name, javaClass);
        } else {
            javaClass = new DVMTaintClass(name);
            currentProject.addDexClass(name, javaClass);
        }

        return javaClass;
    }

    private EncapsulatedClass buildFakeClss(String name, Object newInstance) {
        EncapsulatedClass newClass = new EncapsulatedClass(name);
        newClass.setName(name);
        newClass.setJavaObject(newInstance);
        newClass.setSuperClass(DroidDefenseParams.SUPERCLASS);
        return newClass;
    }

    public void loadClasses(final byte[] dexFileContent, boolean multidex) {
        //todo add support for multiple dex files
        synchronized (loadClassesMutex) {
            this.dexFileContent = dexFileContent;
            dexBodyModel.setOffset(-1);

            checkData("magic number", "6465780A30333500");

            skip("checksum", 4);
            skip("SHA-1 signature", 20);

            checkUInt("file size", dexFileContent.length);
            checkUInt("header size", 0x70);
            checkUInt("endian", 0x12345678);

            checkUInt("link size", 0);
            checkUInt("link offset", 0);

            readMap();
            readStrings();
            readTypes();
            readDescriptors();
            readFields();
            readMethods();
            readClassContents();
        }
        currentProject.addDexBodyModel(dexBodyModel);
    }

    private void readClassContents() {
        int count = readUInt();
        int offset = readUInt();
        if (offset != 0) {
            pushOffset(offset);
            for (int i = 0; i < count; i++) {
                IAtomClass cls = new DVMClass();

                String clsName = DynamicUtils.fromTypeToClassName(dexBodyModel.getTypes()[readUInt()]);
                DexBodyModel.pool.addClassName(clsName);
                cls.setName(clsName);

                cls.setFlag(readUInt());
                boolean isInterface = ((cls.getFlag() & AccessFlag.ACC_INTERFACE.getValue()) != 0) | ((cls.getFlag() & AccessFlag.ACC_ABSTRACT.getValue()) != 0);
                cls.setInterface(isInterface);

                int superClassIndex = readUInt();
                if (hasNoValue(superClassIndex)) {
                    cls.setSuperClass(DroidDefenseParams.SUPERCLASS);
                } else {
                    cls.setSuperClass(DynamicUtils.fromTypeToClassName(dexBodyModel.getTypes()[superClassIndex]));
                }

                int interfacesOffset = readUInt();
                if (interfacesOffset != 0) {
                    pushOffset(interfacesOffset);

                    int interfaceCount = readUInt();
                    String[] interfaces = new String[interfaceCount];
                    cls.setInterfaces(interfaces);
                    for (int j = 0; j < interfaceCount; j++) {
                        interfaces[j] = DynamicUtils.fromTypeToClassName(dexBodyModel.getTypes()[readUShort()]);
                    }

                    popOffset();
                }
                skip("source file index", 4);
                skip("anotations offset", 4);

                int classDataOffset = readUInt();
                if (classDataOffset != 0) {
                    pushOffset(classDataOffset);

                    IAtomField[] staticFields = new IAtomField[readULEB128()];
                    IAtomField[] instanceFields = new IAtomField[readULEB128()];
                    DVMMethod[] directMethods = new DVMMethod[readULEB128()];
                    DVMMethod[] virtualMethods = new DVMMethod[readULEB128()];

                    readFields(cls, staticFields, false);
                    readFields(cls, instanceFields, true);
                    readMethodContents(cls, directMethods);
                    readMethodContents(cls, virtualMethods);

                    cls.setStaticFields(staticFields);
                    cls.setStaticFieldMap(new Hashtable());
                    for (IAtomField field : staticFields) {
                        cls.getStaticFieldMap().put(field.getName(), field);
                    }
                    cls.setInstanceFields(instanceFields);
                    cls.setDirectMethods(directMethods);
                    cls.setVirtualMethods(virtualMethods);

                    popOffset();
                }

                int staticValuesOffset = readUInt();
                if (staticValuesOffset != 0) {
                    pushOffset(staticValuesOffset);

                    int length = readULEB128();
                    for (int j = 0; j < length; j++) {
                        IAtomField staticField = cls.getStaticFields()[j];

                        int data = readUByte();
                        int valueType = data & 0x1F;
                        int valueArgument = data >> 5;
                        ValueFormat dataEnum = ValueFormat.getDataType(valueType);
                        boolean valueTypeSupported = dataEnum.setValue(staticField, valueArgument, this);
                        if (!valueTypeSupported) {
                            throw new NotSupportedValueTypeException(dataEnum);
                        }
                    }
                    popOffset();
                }

                classes.put(cls.getName(), cls);
            }
            popOffset();
        }
    }

    public long readValueByTypeArgument(final int typeArgument) {
        return readSigned(typeArgument + 1);
    }

    private void readMethodContents(final IAtomClass cls, final IAtomMethod[] methods) {

        DexBodyModel.pool.setStrings(dexBodyModel.getStrings());
        DexBodyModel.pool.setTypes(dexBodyModel.getTypes());
        DexBodyModel.pool.setDescriptors(dexBodyModel.getDescriptors());
        DexBodyModel.pool.setFieldClasses(dexBodyModel.getFieldClasses());
        DexBodyModel.pool.setFieldTypes(dexBodyModel.getFieldTypes());
        DexBodyModel.pool.setFieldNames(dexBodyModel.getFieldNames());
        DexBodyModel.pool.setMethodClasses(dexBodyModel.getMethodClasses());
        DexBodyModel.pool.setMethodTypes(dexBodyModel.getMethodTypes());
        DexBodyModel.pool.setMethodNames(dexBodyModel.getMethodNames());

        int methodIndex = 0;
        for (int i = 0, length = methods.length; i < length; i++) {
            if (i == 0) {
                methodIndex = readULEB128();
            } else {
                methodIndex += readULEB128();
            }
            IAtomMethod method = new DVMMethod(cls);

            method.setFlag(readULEB128());
            method.setInstance(((byte) method.getFlag() & AccessFlag.ACC_STATIC.getValue()) == 0);
            method.setSynchronized((method.getFlag() & AccessFlag.ACC_SYNCHRONIZED.getValue()) != 0);

            method.setName(dexBodyModel.getMethodNames()[methodIndex]);
            method.setDescriptor(dexBodyModel.getMethodTypes()[methodIndex]);

            int codeOffset = readULEB128();
            if (codeOffset != 0) {
                pushOffset(codeOffset);

                method.setRegisterCount(readUShort());
                method.setIncomingArgumentCount(readUShort());
                method.setOutgoingArgumentCount(readUShort());

                int tryItemCount = readUShort();
                int debugInfoOffset = readUInt();

                int codeLength = readUInt();
                int[] lowerCodes = new int[codeLength];
                int[] upperCodes = new int[codeLength];
                int[] codes = new int[codeLength];

                method.setOpcodes(lowerCodes);
                method.setRegistercodes(upperCodes);
                method.setIndex(codes);

                for (int j = 0; j < codeLength; j++) {
                    int data = readUShort();
                    lowerCodes[j] = data & 0xFF;
                    upperCodes[j] = data >> 8;
                    codes[j] = data;
                }
                if (codeLength % 2 != 0 && tryItemCount != 0) {
                    skip("padding", 2);
                }

                int[] exceptionStartAddresses = new int[tryItemCount];
                int[] exceptionEndAddresses = new int[tryItemCount];
                int[] exceptionHandlerIndex = new int[tryItemCount];

                method.setExceptionStartAddresses(exceptionStartAddresses);
                method.setExceptionEndAdresses(exceptionEndAddresses);
                method.setExceptionHandlerIndexes(exceptionHandlerIndex);

                if (tryItemCount != 0) {
                    for (int j = 0; j < tryItemCount; j++) {
                        int startAddress = exceptionStartAddresses[j] = readUInt();
                        exceptionEndAddresses[j] = startAddress + readUShort();
                        exceptionHandlerIndex[j] = readUShort();
                    }

                    int baseOffset = dexBodyModel.getOffset();
                    int listCount = readULEB128();
                    String[][] exceptionHandlerTypes = new String[listCount][];
                    int[][] exceptionHandlerAddresses = new int[listCount][];

                    method.setExceptionHandlerTypes(exceptionHandlerTypes);
                    method.setExceptionHandlerAddresses(exceptionHandlerAddresses);
                    for (int j = 0; j < listCount; j++) {
                        int comaredOffset = dexBodyModel.getOffset() - baseOffset;
                        for (int k = 0, k_length = exceptionStartAddresses.length; k < k_length; k++) {
                            if (exceptionHandlerIndex[k] == comaredOffset) {
                                exceptionHandlerIndex[k] = j;
                            }
                        }
                        int handlerCount = readSLEB128();
                        if (handlerCount <= 0) {
                            exceptionHandlerTypes[j] = new String[-handlerCount + 1];
                            exceptionHandlerAddresses[j] = new int[-handlerCount + 1];
                        } else {
                            exceptionHandlerTypes[j] = new String[handlerCount];
                            exceptionHandlerAddresses[j] = new int[handlerCount];
                        }
                        for (int k = 0, k_length = Math.abs(handlerCount); k < k_length; k++) {
                            exceptionHandlerTypes[j][k] = DynamicUtils.toDotSeparatorClassName(dexBodyModel.getTypes()[readULEB128()]);
                            exceptionHandlerAddresses[j][k] = readULEB128();
                        }
                        if (handlerCount <= 0) {
                            exceptionHandlerTypes[j][-handlerCount] = "java.lang.Throwable";
                            exceptionHandlerAddresses[j][-handlerCount] = readULEB128();
                        }
                    }
                }

                popOffset();
            }

            methods[i] = method;
        }
    }

    private void readFields(final IAtomClass cls, final IAtomField[] fields, final boolean isInstance) {
        int fieldIndex = 0;
        for (int i = 0, length = fields.length; i < length; i++) {
            if (i == 0) {
                fieldIndex = readULEB128();
            } else {
                fieldIndex += readULEB128();
            }
            IAtomField field = new DVMField(cls);

            field.setFlag(readULEB128());
            field.setInstance(isInstance);

            field.setName(dexBodyModel.getFieldNames()[fieldIndex]);
            field.setType(dexBodyModel.getFieldTypes()[fieldIndex]);

            fields[i] = field;
        }
    }

    private void readMethods() {
        int count = readUInt();
        dexBodyModel.setMethodClasses(new String[count]);
        dexBodyModel.setMethodTypes(new String[count]);
        dexBodyModel.setMethodNames(new String[count]);
        int offset = readUInt();
        if (offset != 0) {
            pushOffset(offset);
            for (int i = 0; i < count; i++) {
                String classType = dexBodyModel.getTypes()[readUShort()];
                dexBodyModel.getMethodClasses()[i] = classType.substring(1, classType.length() - 1);
                dexBodyModel.getMethodTypes()[i] = dexBodyModel.getDescriptors()[readUShort()];
                dexBodyModel.getMethodNames()[i] = dexBodyModel.getStrings()[readUInt()];
            }
            popOffset();
        }
    }

    private void readFields() {
        int count = readUInt();
        dexBodyModel.setFieldClasses(new String[count]);
        dexBodyModel.setFieldTypes(new String[count]);
        dexBodyModel.setFieldNames(new String[count]);
        int offset = readUInt();
        if (offset != 0) {
            pushOffset(offset);
            for (int i = 0; i < count; i++) {
                String classType = dexBodyModel.getTypes()[readUShort()];
                dexBodyModel.getFieldClasses()[i] = classType.substring(1, classType.length() - 1);
                dexBodyModel.getFieldTypes()[i] = dexBodyModel.getTypes()[readUShort()];
                dexBodyModel.getFieldNames()[i] = dexBodyModel.getStrings()[readUInt()];
            }
            popOffset();
        }
    }

    private void readDescriptors() {
        int count = readUInt();
        dexBodyModel.setDescriptors(new String[count]);
        pushOffset(readUInt());
        for (int i = 0; i < count; i++) {
            StringBuilder buffer = new StringBuilder();
            skip("shorty index", 4);
            String returnType = dexBodyModel.getTypes()[readUInt()];

            int offset = readUInt();
            if (offset == 0) {
                buffer.append("()");
            } else {
                pushOffset(offset);

                buffer.append("(");
                int typeCount = readUInt();
                for (int j = 0; j < typeCount; j++) {
                    buffer.append(dexBodyModel.getTypes()[readUShort()]);
                }
                buffer.append(")");
                popOffset();
            }

            buffer.append(returnType);
            dexBodyModel.getDescriptors()[i] = buffer.toString();
        }
        popOffset();
    }

    private void readTypes() {
        int count = readUInt();
        dexBodyModel.setTypes(new String[count]);
        pushOffset(readUInt());
        for (int i = 0; i < count; i++) {
            dexBodyModel.getTypes()[i] = dexBodyModel.getStrings()[readUInt()];
        }
        popOffset();
    }

    private void readStrings() {
        int count = readUInt();
        dexBodyModel.setStrings(new String[count]);
        pushOffset(readUInt());
        for (int i = 0; i < count; i++) {
            pushOffset(readUInt());

            int stringLength = readULEB128();
            char[] chars = new char[stringLength];
            for (int j = 0, j_length = chars.length; j < j_length; j++) {
                int data = readUByte();
                switch (data >> 4) {
                    case 0:
                        //break;
                    case 1:
                        //break;
                    case 2:
                        //break;
                    case 3:
                        //break;
                    case 4:
                        //break;
                    case 5:
                        //break;
                    case 6:
                        //break;
                    case 7:
                        chars[j] = (char) data;
                        break;
                    case 12:
                        //break;
                    case 13:
                        chars[j] = (char) (((data & 0x1F) << 6) | (readUByte() & 0x3F));
                        break;
                    case 14:
                        chars[j] = (char) (((data & 0x0F) << 12) | ((readUByte() & 0x3F) << 6) | (readUByte() & 0x3F));
                        break;
                    default:
                        throw new NoClassDefFoundError("illegal modified utf-8");
                }
            }
            dexBodyModel.getStrings()[i] = DynamicUtils.convertStringBuilderToStringBuffer(new String(chars));

            popOffset();
        }
        popOffset();
    }

    private int readSLEB128() {
        int value = 0;
        int shiftCount = 0;
        boolean hasNext = true;
        while (hasNext) {
            int data = readUByte();
            value |= (data & 0x7F) << shiftCount;
            shiftCount += 7;
            hasNext = (data & 0x80) != 0;
        }
        return (value << (32 - shiftCount)) >> (32 - shiftCount);
    }

    private int readULEB128() {
        int value = 0;
        int shiftCount = 0;
        boolean hasNext = true;
        while (hasNext) {
            int data = readUByte();
            value |= (data & 0x7F) << shiftCount;
            shiftCount += 7;
            hasNext = (data & 0x80) != 0;
        }
        return value;
    }

    private void readMap() {
        pushOffset(readUInt());

        int count = readUInt();
        if (count == 0) {
            System.out.println("No map detected");
        }
        for (int i = 0; i < count; i++) {
            int type = readUShort();
            skip("unused", 2);
            int itemSize = readUInt();
            int itemOffset = readUInt();
        }

        popOffset();
    }

    private void popOffset() {
        dexBodyModel.setOffset(oldOffset[--oldOffsetIndex]);
    }

    private void pushOffset(final int offset) {
        oldOffset[oldOffsetIndex++] = this.dexBodyModel.getOffset();
        this.dexBodyModel.setOffset(offset);
    }

    private void checkUInt(final String type, final int valueToCheck) {
        if (readUInt() != valueToCheck) {
            throw new NoClassDefFoundError("illegal " + type);
        }
    }

    private void skip(final String type, final int count) {
        System.out.println(type + " skipping data block...");
        dexBodyModel.setOffset(dexBodyModel.getOffset() + count);
    }

    // TODO Change the return value from int to long
    private int readUInt() {
        return readUByte() | (readUByte() << SIGNED_BYTE_LENGTH) | (readUByte() << 16) | (readUByte() << 24);
    }

    private int readUShort() {
        return readUByte() | (readUByte() << SIGNED_BYTE_LENGTH);
    }

    public int readByte() {
        int index = dexBodyModel.increaseIndex(SINGLE_VALUE);
        return dexFileContent[index];
    }

    private int readUByte() {
        int index = dexBodyModel.increaseIndex(1);
        return dexFileContent[index] & 0xFF;
    }

    private long readSigned(final int byteLength) {
        long value = 0;
        for (int i = 0; i < byteLength; i++) {
            value |= (long) readUByte() << (SIGNED_BYTE_LENGTH * i);
        }
        int shift = SIGNED_BYTE_LENGTH * byteLength;
        return (value << shift) >> shift;
    }

    private void checkData(final String type, final String valueToCheck) {
        for (int i = 0, length = valueToCheck.length() / 2; i < length; i++) {
            int valueToCheckB = Integer.parseInt(valueToCheck.substring(i * 2, i * 2 + 2), 16);
            int readedValue = readUByte();
            if (readedValue != valueToCheckB) {
                throw new NoClassDefFoundError("illegal " + type);
            }
        }
    }

    //GETTERS AND SETTERS

    public String[] getMethodNames() {
        return dexBodyModel.getMethodNames();
    }

    public void setMethodNames(String[] methodNames) {
        this.dexBodyModel.setMethodNames(methodNames);
    }

    public DalvikVM getVm() {
        return vm;
    }

    public Hashtable<String, IAtomClass> getClasses() {
        return classes;
    }

    public Object getLoadClassesMutex() {
        return loadClassesMutex;
    }

    public byte[] getDexFileContent() {
        return dexFileContent;
    }

    public void setDexFileContent(byte[] dexFileContent) {
        this.dexFileContent = dexFileContent;
    }

    public int getOffset() {
        return dexBodyModel.getOffset();
    }

    public void setOffset(int offset) {
        this.dexBodyModel.setOffset(offset);
    }

    public int[] getOldOffset() {
        return oldOffset;
    }

    public void setOldOffset(int[] oldOffset) {
        this.oldOffset = oldOffset;
    }

    public int getOldOffsetIndex() {
        return oldOffsetIndex;
    }

    public void setOldOffsetIndex(int oldOffsetIndex) {
        this.oldOffsetIndex = oldOffsetIndex;
    }

    public String[] getStrings() {
        return dexBodyModel.getStrings();
    }

    public void setStrings(String[] strings) {
        this.dexBodyModel.setStrings(strings);
    }

    public String[] getTypes() {
        return dexBodyModel.getTypes();
    }

    public void setTypes(String[] types) {
        this.dexBodyModel.setTypes(types);
    }

    public String[] getDescriptors() {
        return dexBodyModel.getDescriptors();
    }

    public void setDescriptors(String[] descriptors) {
        this.dexBodyModel.setDescriptors(descriptors);
    }

    public String[] getFieldClasses() {
        return dexBodyModel.getFieldClasses();
    }

    public void setFieldClasses(String[] fieldClasses) {
        this.dexBodyModel.setFieldClasses(fieldClasses);
    }

    public String[] getFieldTypes() {
        return dexBodyModel.getFieldTypes();
    }

    public void setFieldTypes(String[] fieldTypes) {
        this.dexBodyModel.setFieldTypes(fieldTypes);
    }

    public String[] getFieldNames() {
        return dexBodyModel.getFieldNames();
    }

    public void setFieldNames(String[] fieldNames) {
        this.dexBodyModel.setFieldNames(fieldNames);
    }

    public String[] getMethodClasses() {
        return dexBodyModel.getMethodClasses();
    }

    public void setMethodClasses(String[] methodClasses) {
        this.dexBodyModel.setMethodClasses(methodClasses);
    }

    public String[] getMethodTypes() {
        return dexBodyModel.getMethodTypes();
    }

    public void setMethodTypes(String[] methodTypes) {
        this.dexBodyModel.setMethodTypes(methodTypes);
    }

    public IAtomClass[] getAllClasses() {
        Collection<IAtomClass> data = classes.values();
        return data.toArray(new IAtomClass[data.size()]);
    }
}
